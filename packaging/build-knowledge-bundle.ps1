#Requires -Version 7.0
<#
.SYNOPSIS
    Build the official course-knowledge bundle zip (v3.4.3 OKB-1).

.DESCRIPTION
    Reads an Obsidian-style markdown directory, rewrites `![[image]]` wiki embeds to
    standard markdown images under attachments/<docId>/, and produces:
      target\knowledge\<bundleId>-<version>.zip        (manifest.json + docs/ + attachments/)
      target\knowledge\<bundleId>-<version>.zip.sha256 (archive checksum, hex)
      target\knowledge\<bundleId>-manifest.json        (cloud manifest snippet for upload)
    Artifacts are build outputs and must never be committed.
#>
param(
    [Parameter(Mandatory = $true)][string]$SourceDirectory,
    [string]$BundleId = "official-db-concepts",
    [string]$Version = "1.0.0",
    [string]$Title = "数据库系统概念",
    [string]$OutputDir = "target\knowledge",
    [string]$GeneratedAt = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sourceRoot = (Resolve-Path -LiteralPath $SourceDirectory).Path
if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) {
    throw "Source directory not found: $sourceRoot"
}
$outputRoot = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    [System.IO.Path]::GetFullPath($OutputDir)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDir))
}
$targetRoot = Join-Path $projectRoot "target"
foreach ($candidate in @($outputRoot)) {
    $parent = [System.IO.Path]::GetFullPath($targetRoot).TrimEnd('\', '/')
    if (-not $candidate.StartsWith($parent + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to write outside '$targetRoot': $candidate"
    }
}

$stageRoot = Join-Path $targetRoot "knowledge-stage\$BundleId-$Version"
if (Test-Path -LiteralPath $stageRoot) { Remove-Item -LiteralPath $stageRoot -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $stageRoot "docs") -Force | Out-Null
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    return ([System.Security.Cryptography.SHA256]::HashData($Bytes) |
        ForEach-Object { $_.ToString("x2") }) -join ""
}

function Get-EncodedSegment {
    param([string]$Segment)
    return [uri]::EscapeDataString($Segment)
}

$markdownFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter *.md |
    Sort-Object FullName)
if ($markdownFiles.Count -eq 0) { throw "No markdown files under $sourceRoot" }

$attachmentExtensions = @(".png", ".jpg", ".jpeg")
$documents = @()
$missingAttachments = 0
$copiedAttachments = 0

foreach ($file in $markdownFiles) {
    $relativePath = [System.IO.Path]::GetRelativePath($sourceRoot, $file.FullName) `
        -replace '\\', '/'
    $docId = $relativePath -replace '\.md$', ''
    $segments = $docId -split '/'
    $sectionTitle = if ($segments.Count -gt 1) { $segments[0] } else { "未分组" }

    $content = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8

    # Title: first level-1 heading, else file name (matches DefaultObsidianVaultImportService).
    $docTitle = $segments[-1]
    $headingMatch = [regex]::Match($content, '(?m)^#\s+(.+?)\s*$')
    if ($headingMatch.Success) { $docTitle = $headingMatch.Groups[1].Value }

    $docAttachments = @()
    $docDirectory = $file.DirectoryName

    # Rewrite Obsidian image embeds ![[name|alias]] to standard markdown images.
    $content = [regex]::Replace($content, '!\[\[([^\]\|]+)(?:\|[^\]]*)?\]\]', {
        param($match)
        $name = $match.Groups[1].Value.Trim()
        $extension = [System.IO.Path]::GetExtension($name).ToLowerInvariant()
        if ($attachmentExtensions -notcontains $extension) { return $match.Value }

        # Locate the file: same directory, sibling Attachments directory, then whole vault.
        $resolved = $null
        $candidates = @(
            (Join-Path $docDirectory $name),
            (Join-Path (Join-Path $docDirectory "Attachments") $name)
        )
        foreach ($candidate in $candidates) {
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $resolved = $candidate; break }
        }
        if (-not $resolved) {
            $found = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter `
                ([System.IO.Path]::GetFileName($name)) | Select-Object -First 1)
            if ($found.Count -gt 0) { $resolved = $found[0].FullName }
        }
        if (-not $resolved) {
            $script:missingAttachments++
            return $match.Value
        }

        $fileName = [System.IO.Path]::GetFileName($resolved)
        $assetRelative = (@("attachments") + $segments + @($fileName)) -join '/'
        $assetStagePath = Join-Path $stageRoot (($assetRelative -split '/') -join [System.IO.Path]::DirectorySeparatorChar)
        $assetDirectory = Split-Path -Parent $assetStagePath
        if (-not (Test-Path -LiteralPath $assetDirectory)) {
            New-Item -ItemType Directory -Path $assetDirectory -Force | Out-Null
        }
        Copy-Item -LiteralPath $resolved -Destination $assetStagePath -Force
        $script:copiedAttachments++

        $bytes = [System.IO.File]::ReadAllBytes($assetStagePath)
        $script:docAttachments += [ordered]@{
            path   = $assetRelative
            sha256 = (Get-Sha256Hex -Bytes $bytes)
        }

        $encodedSrc = ((@("attachments") + ($segments | ForEach-Object { Get-EncodedSegment $_ }) +
                @((Get-EncodedSegment $fileName))) -join '/')
        return "![$name]($encodedSrc)"
    })

    $docRelative = (@("docs") + ($relativePath -split '/')) -join '/'
    $docStagePath = Join-Path $stageRoot (((@("docs") + $segments) -join [System.IO.Path]::DirectorySeparatorChar) + ".md")
    $docStageDirectory = Split-Path -Parent $docStagePath
    if (-not (Test-Path -LiteralPath $docStageDirectory)) {
        New-Item -ItemType Directory -Path $docStageDirectory -Force | Out-Null
    }
    $docBytes = [System.Text.Encoding]::UTF8.GetBytes($content)
    [System.IO.File]::WriteAllBytes($docStagePath, $docBytes)

    $documents += [ordered]@{
        id            = $docId
        path          = $docRelative
        title         = $docTitle
        sectionTitle  = $sectionTitle
        sha256        = (Get-Sha256Hex -Bytes $docBytes)
        attachments   = @($docAttachments)
    }
}

# Build-time self-check: every manifest path/attachment must exist in the staging tree,
# so a manifest/entry mismatch (e.g. a doubled extension) fails the build instead of shipping.
foreach ($document in $documents) {
    foreach ($entryPath in (@($document.path) + @($document.attachments | ForEach-Object { $_.path }))) {
        $staged = Join-Path $stageRoot (($entryPath -split '/') -join [System.IO.Path]::DirectorySeparatorChar)
        if (-not (Test-Path -LiteralPath $staged -PathType Leaf)) {
            throw "Bundle manifest references a missing staged file: $entryPath"
        }
    }
}

if (-not $GeneratedAt) { $GeneratedAt = [DateTime]::UtcNow.ToString("o") }
$manifest = [ordered]@{
    bundleId    = $BundleId
    version     = $Version
    title       = $Title
    generatedAt = $GeneratedAt
    documents   = @($documents)
}
$manifestJson = $manifest | ConvertTo-Json -Depth 10
[System.IO.File]::WriteAllBytes(
    (Join-Path $stageRoot "manifest.json"),
    [System.Text.Encoding]::UTF8.GetBytes($manifestJson))

$zipPath = Join-Path $outputRoot "$BundleId-$Version.zip"
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory($stageRoot, $zipPath)

$archiveSha256 = Get-Sha256Hex -Bytes ([System.IO.File]::ReadAllBytes($zipPath))
$archiveSize = (Get-Item -LiteralPath $zipPath).Length
Set-Content -LiteralPath "$zipPath.sha256" -Value $archiveSha256 -NoNewline -Encoding ascii

$cloudManifest = [ordered]@{
    bundleId    = $BundleId
    version     = $Version
    title       = $Title
    sizeBytes   = $archiveSize
    sha256      = $archiveSha256
    generatedAt = $GeneratedAt
}
$cloudManifestPath = Join-Path $outputRoot "$BundleId-manifest.json"
[System.IO.File]::WriteAllBytes(
    $cloudManifestPath,
    [System.Text.Encoding]::UTF8.GetBytes(($cloudManifest | ConvertTo-Json -Depth 5)))

Write-Host "Bundle built: $zipPath"
Write-Host ("  documents: {0}, attachments copied: {1}, missing attachments: {2}" -f `
    $documents.Count, $copiedAttachments, $missingAttachments)
Write-Host ("  archive: {0:N0} bytes, sha256 {1}" -f $archiveSize, $archiveSha256)
Write-Host "  cloud manifest snippet: $cloudManifestPath"
