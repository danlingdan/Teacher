$ErrorActionPreference = "Stop"
$uiRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$cargoTarget = [System.IO.Path]::GetFullPath((Join-Path $uiRoot "src-tauri\target"))
$releaseRoot = [System.IO.Path]::GetFullPath((Join-Path $cargoTarget "release"))
$sidecarTarget = [System.IO.Path]::GetFullPath((Join-Path $releaseRoot "sidecar"))
if (-not $sidecarTarget.StartsWith($cargoTarget + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean E2E resources outside the Cargo target directory."
}
if (Test-Path -LiteralPath $sidecarTarget) {
    Remove-Item -LiteralPath $sidecarTarget -Recurse -Force
}

Push-Location $uiRoot
try {
    & (Join-Path $uiRoot "..\packaging\build-v3-sidecar.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Unable to build the Alpha.7 Java sidecar." }
    npx tauri build --no-bundle --features e2e --config src-tauri/tauri.e2e.conf.json
    if ($LASTEXITCODE -ne 0) { throw "Unable to build the Alpha.7 E2E desktop binary." }
} finally {
    Pop-Location
}
