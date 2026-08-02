[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Get-Location).Path
)

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$files = @(
    Get-ChildItem -LiteralPath $root -Recurse -Filter '*.md' -File |
        Where-Object { $_.FullName -notmatch '[\\/](target|app-data|\.git)[\\/]' }
)
$broken = [System.Collections.Generic.List[string]]::new()

foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $links = [regex]::Matches($content, '(?!!)\[[^\]]*\]\(([^)]+)\)')
    foreach ($link in $links) {
        $target = $link.Groups[1].Value.Trim('<', '>')
        if ($target -match '^(https?://|mailto:|#)') {
            continue
        }

        $pathPart = [uri]::UnescapeDataString(($target -split '#')[0])
        if ([string]::IsNullOrWhiteSpace($pathPart) -or $pathPart -match '[*`$]') {
            continue
        }

        $resolved = [System.IO.Path]::GetFullPath((Join-Path $file.DirectoryName $pathPart))
        if (-not (Test-Path -LiteralPath $resolved)) {
            $relativeFile = [System.IO.Path]::GetRelativePath($root, $file.FullName)
            $broken.Add("$relativeFile -> $target")
        }
    }
}

if ($broken.Count -gt 0) {
    $broken | ForEach-Object { Write-Error $_ }
    throw "Found $($broken.Count) broken local Markdown link(s)."
}

Write-Output "Markdown link check passed: $($files.Count) file(s), 0 broken local links."
