param(
    [string]$OutputDir = "target\installer",
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = Join-Path $projectRoot "target"
$outputPath = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    [System.IO.Path]::GetFullPath($OutputDir)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDir))
}
$portableStage = Join-Path $targetRoot "v3-portable"
$portableRoot = Join-Path $portableStage "SQLTeacher"
$sidecarRoot = Join-Path $projectRoot "ui-web\src-tauri\sidecar"
$tauriRelease = Join-Path $projectRoot "ui-web\src-tauri\target\release"

function Assert-ChildPath {
    param([string]$Candidate, [string]$Parent)
    $candidatePath = [System.IO.Path]::GetFullPath($Candidate)
    $parentPath = [System.IO.Path]::GetFullPath($Parent).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    if (-not $candidatePath.StartsWith(
        $parentPath + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Refusing to modify path outside '$parentPath': $candidatePath"
    }
}

Assert-ChildPath -Candidate $outputPath -Parent $targetRoot
Assert-ChildPath -Candidate $portableStage -Parent $targetRoot

[xml]$pom = Get-Content -LiteralPath (Join-Path $projectRoot "pom.xml") -Raw
$projectVersion = [string]$pom.project.version
if ($projectVersion -notmatch '^3\.[0-9]+\.[0-9]+(?:-(?:alpha|beta|rc)\.[1-9][0-9]*)?$') {
    throw "Tauri packaging requires a valid v3 release version, found: $projectVersion"
}

$installerPath = Join-Path $outputPath "SQLTeacher-$projectVersion.exe"
$archivePath = Join-Path $outputPath "SQLTeacher-$projectVersion-windows-x64.zip"
$checksumPath = Join-Path $outputPath "SHA256SUMS.txt"
$javaSbomPath = Join-Path $outputPath "sqlteacher-sbom.json"
$uiSbomPath = Join-Path $outputPath "sqlteacher-ui-sbom.json"

Push-Location $projectRoot
try {
    if ([string]::IsNullOrWhiteSpace($env:GITHUB_SHA)) {
        $env:GITHUB_SHA = (git rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0 -or $env:GITHUB_SHA -notmatch '^[0-9a-f]{40}$') {
            throw "Unable to determine the build commit."
        }
    }
    & (Join-Path $PSScriptRoot "build-v3-sidecar.ps1") -JavaHome $JavaHome
    if ($LASTEXITCODE -ne 0) { throw "Unable to build the Java sidecar." }

    Push-Location (Join-Path $projectRoot "ui-web")
    try {
        npm run tauri build -- --bundles nsis
        if ($LASTEXITCODE -ne 0) { throw "Unable to build the Tauri NSIS installer." }
    } finally {
        Pop-Location
    }

    $generatedNsisScript = Join-Path $tauriRelease "nsis\x64\installer.nsi"
    if (-not (Test-Path -LiteralPath $generatedNsisScript)) {
        throw "Tauri did not retain the generated NSIS script for upgrade validation."
    }
    $nsisContent = Get-Content -LiteralPath $generatedNsisScript -Raw
    $requiredNsisContracts = @(
        '!define MANUFACTURER "SQLTeacher Project"',
        '!define INSTALLMODE "perMachine"',
        'installer-hooks.nsh"',
        'StrCpy $INSTDIR "$PROGRAMFILES64\${PRODUCTNAME}"',
        'StrCmp "$R0$R1" "${PRODUCTNAME}${MANUFACTURER}" 0 wix_loop',
        'ExecWait ''$R1'' $0'
    )
    foreach ($contract in $requiredNsisContracts) {
        if (-not $nsisContent.Contains($contract)) {
            throw "Generated NSIS installer is missing the upgrade contract: $contract"
        }
    }
    $installerHooksPath = Join-Path $projectRoot "ui-web\src-tauri\windows\installer-hooks.nsh"
    $installerHooksContent = Get-Content -LiteralPath $installerHooksPath -Raw
    $requiredHookContracts = @(
        '!insertmacro SQLTEACHER_REMOVE_CURRENT_USER "SQLTeacher 3 Alpha"',
        '!insertmacro SQLTEACHER_REMOVE_CURRENT_USER "SQLTeacher 3 Beta"',
        '!insertmacro SQLTEACHER_REMOVE_CURRENT_USER "SQLTeacher"',
        '$R8 == "sqlteacher"',
        '$R8 == "SQLTeacher Project"',
        'ExecWait ''$R9 /S'' $R6',
        'Abort'
    )
    foreach ($contract in $requiredHookContracts) {
        if (-not $installerHooksContent.Contains($contract)) {
            throw "NSIS migration hook is missing the upgrade contract: $contract"
        }
    }
    if (Test-Path -LiteralPath $portableStage) {
        Assert-ChildPath -Candidate $portableStage -Parent $targetRoot
        Remove-Item -LiteralPath $portableStage -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $outputPath, $portableRoot | Out-Null
    Get-ChildItem -LiteralPath $outputPath -File | Where-Object {
        $_.Name -match '^SQLTeacher-[0-9]+\.[0-9]+\.[0-9]+(?:-(?:alpha|beta|rc)\.[0-9]+)?(?:-windows-x64\.zip|\.exe)$' -or
        $_.Name -in @('SHA256SUMS.txt', 'sqlteacher-sbom.json', 'sqlteacher-ui-sbom.json', 'update-payload.json', 'update-manifest.json')
    } | ForEach-Object {
        Assert-ChildPath -Candidate $_.FullName -Parent $outputPath
        Remove-Item -LiteralPath $_.FullName -Force
    }

    $generatedInstaller = Get-ChildItem -LiteralPath (Join-Path $tauriRelease "bundle\nsis") `
        -Filter "*_x64-setup.exe" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $generatedInstaller) { throw "Tauri did not produce an NSIS installer." }
    Copy-Item -LiteralPath $generatedInstaller.FullName -Destination $installerPath -Force

    $desktopExecutable = Join-Path $tauriRelease "sqlteacher-desktop.exe"
    if (-not (Test-Path -LiteralPath $desktopExecutable)) {
        throw "Tauri desktop executable is missing: $desktopExecutable"
    }
    Copy-Item -LiteralPath $desktopExecutable -Destination (Join-Path $portableRoot "SQLTeacher.exe") -Force
    Copy-Item -LiteralPath $sidecarRoot -Destination (Join-Path $portableRoot "sidecar") -Recurse -Force

    $legalRoot = Join-Path $portableRoot "legal"
    New-Item -ItemType Directory -Force -Path $legalRoot | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot "LICENSE") -Destination (Join-Path $legalRoot "LICENSE.txt") -Force
    Copy-Item -LiteralPath (Join-Path $projectRoot "src\main\resources\legal\THIRD-PARTY-LICENSES.txt") -Destination $legalRoot -Force
    Copy-Item -LiteralPath (Join-Path $projectRoot "src\main\resources\legal\PRIVACY.md") -Destination $legalRoot -Force

    $generatedJavaSbom = Join-Path $targetRoot "sqlteacher-sbom.json"
    if (-not (Test-Path -LiteralPath $generatedJavaSbom)) {
        throw "Maven CycloneDX SBOM is missing: $generatedJavaSbom"
    }
    Copy-Item -LiteralPath $generatedJavaSbom -Destination $javaSbomPath -Force
    Copy-Item -LiteralPath $generatedJavaSbom -Destination (Join-Path $legalRoot "sqlteacher-sbom.json") -Force

    Push-Location (Join-Path $projectRoot "ui-web")
    try {
        $npmSbom = npm sbom --sbom-format cyclonedx --omit=dev
        if ($LASTEXITCODE -ne 0) { throw "Unable to generate the npm CycloneDX SBOM." }
        Set-Content -LiteralPath $uiSbomPath -Value $npmSbom -Encoding utf8
    } finally {
        Pop-Location
    }
    Copy-Item -LiteralPath $uiSbomPath -Destination (Join-Path $legalRoot "sqlteacher-ui-sbom.json") -Force

    $requiredPortableFiles = @(
        (Join-Path $portableRoot "SQLTeacher.exe"),
        (Join-Path $portableRoot "sidecar\runtime\bin\java.exe"),
        (Join-Path $portableRoot "sidecar\sidecar.json")
    )
    foreach ($file in $requiredPortableFiles) {
        if (-not (Test-Path -LiteralPath $file)) { throw "Portable package is incomplete: $file" }
    }

    Compress-Archive -LiteralPath $portableRoot -DestinationPath $archivePath -CompressionLevel Optimal
    $releaseArtifacts = @($installerPath, $archivePath)
    $checksumLines = $releaseArtifacts | ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $([System.IO.Path]::GetFileName($_))"
    }
    Set-Content -LiteralPath $checksumPath -Value $checksumLines -Encoding ascii

    foreach ($jsonPath in @($javaSbomPath, $uiSbomPath)) {
        Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json | Out-Null
    }
    Write-Host "Created Tauri installer: $installerPath"
    Write-Host "Created portable archive: $archivePath"
    Write-Host "Created checksums and Java/npm SBOMs: $outputPath"
} finally {
    Pop-Location
}
