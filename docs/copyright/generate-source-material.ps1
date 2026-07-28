param(
    [string]$OutputDirectory = "docs/copyright/generated",
    [int]$LinesPerPage = 50,
    [int]$PagesPerSegment = 30
)

$ErrorActionPreference = "Stop"

if ($LinesPerPage -lt 50) {
    throw "LinesPerPage must be at least 50 for the standard source-material layout."
}
if ($PagesPerSegment -lt 1) {
    throw "PagesPerSegment must be positive."
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$outputPath = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    [System.IO.Path]::GetFullPath($OutputDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
}

if (-not $outputPath.StartsWith($projectRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDirectory must be within the project workspace."
}

$sourceRoot = Join-Path $projectRoot "src/main/java"
$sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Filter "*.java" -File -Recurse | Sort-Object FullName)
if ($sourceFiles.Count -eq 0) {
    throw "No production Java source files found under src/main/java."
}

New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

$masterLines = [System.Collections.Generic.List[string]]::new()
$manifestLines = [System.Collections.Generic.List[string]]::new()
foreach ($file in $sourceFiles) {
    $relativePath = [System.IO.Path]::GetRelativePath($projectRoot, $file.FullName).Replace("\\", "/")
    $manifestLines.Add($relativePath)
    $masterLines.Add("// FILE: $relativePath")
    foreach ($line in Get-Content -LiteralPath $file.FullName) {
        $masterLines.Add($line)
    }
}

$masterPath = Join-Path $outputPath "source-program-master.txt"
$manifestPath = Join-Path $outputPath "source-file-manifest.txt"
$frontPath = Join-Path $outputPath "source-program-front-30-pages.txt"
$backPath = Join-Path $outputPath "source-program-back-30-pages.txt"
$metadataPath = Join-Path $outputPath "source-material-metadata.txt"

Set-Content -LiteralPath $masterPath -Value $masterLines -Encoding utf8
Set-Content -LiteralPath $manifestPath -Value $manifestLines -Encoding utf8

$pageCount = [math]::Ceiling($masterLines.Count / $LinesPerPage)
$segmentLineCount = $LinesPerPage * $PagesPerSegment
if ($pageCount -lt ($PagesPerSegment * 2)) {
    throw "The master source has only $pageCount pages at $LinesPerPage lines per page; submit the complete source instead."
}

Set-Content -LiteralPath $frontPath -Value $masterLines.GetRange(0, $segmentLineCount) -Encoding utf8
Set-Content -LiteralPath $backPath -Value $masterLines.GetRange($masterLines.Count - $segmentLineCount, $segmentLineCount) -Encoding utf8

$commit = (git -C $projectRoot rev-parse HEAD).Trim()
$generatedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss K")
$masterHash = (Get-FileHash -LiteralPath $masterPath -Algorithm SHA256).Hash.ToLowerInvariant()
$metadata = @(
    "git_commit=$commit",
    "generated_at=$generatedAt",
    "source_file_count=$($sourceFiles.Count)",
    "master_line_count=$($masterLines.Count)",
    "lines_per_page=$LinesPerPage",
    "master_page_count=$pageCount",
    "segment_page_count=$PagesPerSegment",
    "master_sha256=$masterHash"
)
Set-Content -LiteralPath $metadataPath -Value $metadata -Encoding utf8

Write-Host "Generated source material in $outputPath"
Write-Host "Master pages: $pageCount; files: $($sourceFiles.Count); lines: $($masterLines.Count)"
