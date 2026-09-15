param(
    [string]$OutputDir = "target\installer",
    [string]$JavaHome = $env:JAVA_HOME,
    # v3.4.3 PUB-1: source directory of the official knowledge base markdown vault. When provided,
    # the bundle is rebuilt here (after 'mvn clean' wipes target\) so packaging is self-contained.
    [string]$KnowledgeSourceDirectory = ""
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

    # v3.4.2 LEG-5/LEG-6: the installer must ship the legal trio and the confirmed
    # copyright/license metadata. Single source of truth stays the root LICENSE and
    # src/main/resources/legal; the Tauri-side copy is build staging only (git-ignored).
    $tauriConfig = Get-Content -LiteralPath (Join-Path $projectRoot "ui-web\src-tauri\tauri.conf.json") -Raw | ConvertFrom-Json
    if ($tauriConfig.bundle.resources -notcontains "legal/**/*") {
        throw "tauri.conf.json bundle.resources must include 'legal/**/*' so the installer ships the legal files."
    }
    $expectedCopyright = [string]$tauriConfig.bundle.copyright
    if ([string]::IsNullOrWhiteSpace($expectedCopyright)) {
        throw "tauri.conf.json bundle.copyright must carry the confirmed copyright line."
    }
    if ([string]::IsNullOrWhiteSpace($tauriConfig.bundle.licenseFile)) {
        throw "tauri.conf.json bundle.licenseFile must point at the root LICENSE for the NSIS license page."
    }
    $tauriLegalRoot = Join-Path $projectRoot "ui-web\src-tauri\legal"
    New-Item -ItemType Directory -Force -Path $tauriLegalRoot | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot "LICENSE") -Destination (Join-Path $tauriLegalRoot "LICENSE.txt") -Force
    Copy-Item -LiteralPath (Join-Path $projectRoot "src\main\resources\legal\THIRD-PARTY-LICENSES.txt") -Destination $tauriLegalRoot -Force
    Copy-Item -LiteralPath (Join-Path $projectRoot "src\main\resources\legal\PRIVACY.md") -Destination $tauriLegalRoot -Force
    foreach ($stagedLegal in @("LICENSE.txt", "THIRD-PARTY-LICENSES.txt", "PRIVACY.md")) {
        if (-not (Test-Path -LiteralPath (Join-Path $tauriLegalRoot $stagedLegal))) {
            throw "Legal staging for the Tauri bundle is incomplete: $stagedLegal"
        }
    }

    # v3.4.3 PUB-1: the installer must ship the official knowledge base built by
    # build-knowledge-bundle.ps1 into target\knowledge. The Tauri-side copy is build
    # staging only (git-ignored). Missing bundle fails packaging: it is a release promise.
    if ($tauriConfig.bundle.resources -notcontains "knowledge/**/*") {
        throw "tauri.conf.json bundle.resources must include 'knowledge/**/*' so the installer ships the official knowledge base."
    }
    if (-not [string]::IsNullOrWhiteSpace($KnowledgeSourceDirectory)) {
        # Rebuild after 'mvn clean' (build-v3-sidecar.ps1) wiped target\knowledge.
        & (Join-Path $PSScriptRoot "build-knowledge-bundle.ps1") -SourceDirectory $KnowledgeSourceDirectory
        if ($LASTEXITCODE -ne 0) { throw "Unable to build the official knowledge bundle." }
    }
    $knowledgeSourceRoot = Join-Path $targetRoot "knowledge"
    $bundleZip = Get-ChildItem -LiteralPath $knowledgeSourceRoot -Filter "*.zip" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $bundleZip) {
        # v3.4.3: CI has no Obsidian source vault, so fall back to the versioned bundle committed
        # under packaging/knowledge-bundles (the official distribution artifact). A locally
        # rebuilt bundle in target\knowledge (or -KnowledgeSourceDirectory) always wins.
        $committedRoot = Join-Path $projectRoot "packaging\knowledge-bundles"
        $committedZip = Get-ChildItem -LiteralPath $committedRoot -Filter "*.zip" -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($committedZip) {
            New-Item -ItemType Directory -Force -Path $knowledgeSourceRoot | Out-Null
            Copy-Item -LiteralPath $committedZip.FullName -Destination $knowledgeSourceRoot -Force
            $committedSha = "$($committedZip.FullName).sha256"
            if (Test-Path -LiteralPath $committedSha) {
                Copy-Item -LiteralPath $committedSha -Destination $knowledgeSourceRoot -Force
            }
            $bundleZip = Get-ChildItem -LiteralPath $knowledgeSourceRoot -Filter "*.zip" -File |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
        }
    }
    if (-not $bundleZip) {
        throw "No knowledge bundle zip in target\knowledge or packaging\knowledge-bundles. Run packaging\build-knowledge-bundle.ps1 first; the bundled knowledge base is required for this release."
    }
    $bundleChecksumFile = "$($bundleZip.FullName).sha256"
    if (Test-Path -LiteralPath $bundleChecksumFile) {
        $expectedHash = (Get-Content -LiteralPath $bundleChecksumFile -Raw).Trim().ToLowerInvariant()
        $actualHash = (Get-FileHash -LiteralPath $bundleZip.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($expectedHash -ne $actualHash) {
            throw "Knowledge bundle checksum mismatch: $bundleZip does not match its .sha256 sidecar."
        }
    }
    $tauriKnowledgeRoot = Join-Path $projectRoot "ui-web\src-tauri\knowledge"
    New-Item -ItemType Directory -Force -Path $tauriKnowledgeRoot | Out-Null
    Copy-Item -LiteralPath $bundleZip.FullName -Destination $tauriKnowledgeRoot -Force
    if (-not (Test-Path -LiteralPath (Join-Path $tauriKnowledgeRoot $bundleZip.Name))) {
        throw "Knowledge bundle staging for the Tauri bundle is incomplete: $($bundleZip.Name)"
    }

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
        'StrCpy $INSTDIR "$PROGRAMFILES64\${PRODUCTNAME}"',
        'StrCmp "$R0$R1" "${PRODUCTNAME}${MANUFACTURER}" 0 wix_loop',
        'ExecWait ''$R1'' $0'
    )
    foreach ($contract in $requiredNsisContracts) {
        if (-not $nsisContent.Contains($contract)) {
            throw "Generated NSIS installer is missing the upgrade contract: $contract"
        }
    }
    # CMP-0 (v3.4.0): the pre-3.x current-user uninstall hook was removed; the generated
    # script must never reference it again.
    if ($nsisContent.Contains('SQLTEACHER_REMOVE_CURRENT_USER')) {
        throw "Generated NSIS installer unexpectedly references the removed legacy uninstall hook."
    }
    # v3.4.2 LEG-6: the license agreement page and copyright metadata must reach the
    # generated installer; an empty LICENSE define silently drops the page.
    if ($nsisContent.Contains('!define LICENSE ""')) {
        throw "Generated NSIS installer has an empty LICENSE define; the license agreement page would be skipped."
    }
    if (-not $nsisContent.Contains('MUI_PAGE_LICENSE')) {
        throw "Generated NSIS installer is missing the license agreement page (MUI_PAGE_LICENSE)."
    }
    if (-not $nsisContent.Contains("!define COPYRIGHT `"$expectedCopyright`"")) {
        throw "Generated NSIS installer is missing the confirmed COPYRIGHT define: $expectedCopyright"
    }
    # v3.4.2 LEG-5: bundled resources must place the legal trio into <install dir>\legal.
    if (-not $nsisContent.Contains('CreateDirectory "$INSTDIR\legal"')) {
        throw "Generated NSIS installer does not create the legal resource directory."
    }
    # v3.4.3 PUB-1: bundled resources must place the knowledge bundle into <install dir>\knowledge.
    if (-not $nsisContent.Contains('CreateDirectory "$INSTDIR\knowledge"')) {
        throw "Generated NSIS installer does not create the knowledge resource directory."
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

    # v3.4.3 PUB-1: the portable package ships the same official knowledge bundle.
    $portableKnowledgeRoot = Join-Path $portableRoot "knowledge"
    New-Item -ItemType Directory -Force -Path $portableKnowledgeRoot | Out-Null
    Copy-Item -LiteralPath $bundleZip.FullName -Destination $portableKnowledgeRoot -Force

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
        (Join-Path $portableRoot "sidecar\sidecar.json"),
        (Join-Path $portableKnowledgeRoot $bundleZip.Name)
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
