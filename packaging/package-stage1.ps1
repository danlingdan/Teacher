param(
    [string]$OutputDir = "target\installer",
    [string]$CloudBaseUrl = "https://api.sqlteacher.tech",
    [switch]$SkipInstaller
)

$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = Join-Path $projectRoot "target"
$inputDir = Join-Path $targetRoot "stage1-input"
$dependencyDir = Join-Path $inputDir "lib"
$javaFxModuleDir = Join-Path $inputDir "javafx-modules"
$outputPath = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    [System.IO.Path]::GetFullPath($OutputDir)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDir))
}
$pomPath = Join-Path $projectRoot "pom.xml"
[xml]$pom = Get-Content -LiteralPath $pomPath -Raw
$projectVersion = $pom.project.version
$cloudUri = $null
if (-not [System.Uri]::TryCreate($CloudBaseUrl, [System.UriKind]::Absolute, [ref]$cloudUri) -or
    $cloudUri.Scheme -ne [System.Uri]::UriSchemeHttps -or
    [string]::IsNullOrWhiteSpace($cloudUri.Host)) {
    throw "CloudBaseUrl must be an absolute HTTPS URL."
}
$normalizedCloudBaseUrl = $cloudUri.AbsoluteUri.TrimEnd('/')
$cloudJavaOption = "-Dsqlteacher.cloud.base-url=$normalizedCloudBaseUrl"
$jarName = "Teacher-$projectVersion.jar"
$appName = "SQLTeacher"
$appImageDir = Join-Path $outputPath $appName
$archivePath = Join-Path $outputPath "$appName-$projectVersion-windows-x64.zip"
$installerPath = Join-Path $outputPath "$appName-$projectVersion.exe"
$checksumPath = Join-Path $outputPath "SHA256SUMS.txt"
$iconPath = Join-Path $projectRoot "packaging\sqlteacher.ico"
$wixVersion = "3.14.1"
$wixArchiveHash = "6AC824E1642D6F7277D0ED7EA09411A508F6116BA6FAE0AA5F2C7DAA2FF43D31"
$wixUrl = "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip"
$wixRoot = Join-Path $targetRoot "tools\wix-$wixVersion"

function Assert-ChildPath {
    param(
        [string]$Candidate,
        [string]$Parent
    )

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

Assert-ChildPath -Candidate $inputDir -Parent $targetRoot
Assert-ChildPath -Candidate $appImageDir -Parent $outputPath
Assert-ChildPath -Candidate $archivePath -Parent $outputPath
Assert-ChildPath -Candidate $installerPath -Parent $outputPath
Assert-ChildPath -Candidate $checksumPath -Parent $outputPath

if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw "jpackage was not found. Use JDK 21 or newer with jpackage available on PATH."
}
if (-not (Test-Path -LiteralPath $iconPath)) {
    throw "Application icon was not found: $iconPath"
}

function Ensure-WixToolset {
    $candle = Get-Command candle -ErrorAction SilentlyContinue
    $light = Get-Command light -ErrorAction SilentlyContinue
    if ($candle -and $light) {
        return Split-Path -Parent $candle.Source
    }

    $portableCandle = Join-Path $wixRoot "candle.exe"
    $portableLight = Join-Path $wixRoot "light.exe"
    if ((Test-Path -LiteralPath $portableCandle) -and (Test-Path -LiteralPath $portableLight)) {
        return $wixRoot
    }

    $toolDirectory = Split-Path -Parent $wixRoot
    $archive = Join-Path $toolDirectory "wix314-binaries.zip"
    New-Item -ItemType Directory -Force -Path $toolDirectory | Out-Null
    if (Test-Path -LiteralPath $archive) {
        $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        if ($actualHash -ne $wixArchiveHash) {
            [System.IO.File]::Delete($archive)
        }
    }
    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Host "Downloading WiX Toolset $wixVersion..."
        curl.exe -L --fail --retry 3 --output $archive $wixUrl
        if ($LASTEXITCODE -ne 0) {
            throw "WiX Toolset download failed with exit code $LASTEXITCODE."
        }
    }
    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    if ($actualHash -ne $wixArchiveHash) {
        throw "WiX archive checksum mismatch. Expected $wixArchiveHash but found $actualHash."
    }
    if (Test-Path -LiteralPath $wixRoot) {
        Remove-Item -LiteralPath $wixRoot -Recurse -Force
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $wixRoot
    if (-not (Test-Path -LiteralPath $portableCandle) -or -not (Test-Path -LiteralPath $portableLight)) {
        throw "WiX Toolset archive did not contain candle.exe and light.exe."
    }
    return $wixRoot
}

Push-Location $projectRoot
try {
    if (Test-Path -LiteralPath $inputDir) {
        Remove-Item -LiteralPath $inputDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $dependencyDir | Out-Null

    mvn -q -DskipTests package dependency:copy-dependencies `
        "-DincludeScope=runtime" `
        "-DoutputDirectory=$dependencyDir"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed with exit code $LASTEXITCODE."
    }

    New-Item -ItemType Directory -Force -Path $javaFxModuleDir | Out-Null
    Get-ChildItem -LiteralPath $dependencyDir -Filter "javafx-*-win.jar" |
        Copy-Item -Destination $javaFxModuleDir -Force

    Copy-Item -LiteralPath (Join-Path $targetRoot $jarName) `
        -Destination (Join-Path $inputDir $jarName) `
        -Force

    New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
    if (Test-Path -LiteralPath $appImageDir) {
        Remove-Item -LiteralPath $appImageDir -Recurse -Force
    }
    if (Test-Path -LiteralPath $archivePath) {
        Remove-Item -LiteralPath $archivePath -Force
    }
    if (Test-Path -LiteralPath $installerPath) {
        Remove-Item -LiteralPath $installerPath -Force
    }
    if (Test-Path -LiteralPath $checksumPath) {
        Remove-Item -LiteralPath $checksumPath -Force
    }

    jpackage `
        --type app-image `
        --name $appName `
        --app-version $projectVersion `
        --vendor "SQLTeacher Project" `
        --description "Local-first SQL teaching and practice desktop application" `
        --copyright "Copyright 2026 SQLTeacher Project" `
        --icon $iconPath `
        --input $inputDir `
        --main-jar $jarName `
        --main-class com.sqlteacher.desktop.SqlTeacherFxApp `
        --java-options "--enable-native-access=ALL-UNNAMED" `
        --java-options '--module-path=$APPDIR\javafx-modules' `
        --java-options "--add-modules=javafx.controls,javafx.fxml" `
        --java-options "-Dfile.encoding=UTF-8" `
        --java-options $cloudJavaOption `
        --dest $outputPath
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed with exit code $LASTEXITCODE."
    }

    $launcher = Join-Path $appImageDir "$appName.exe"
    if (-not (Test-Path -LiteralPath $launcher)) {
        throw "App-image launcher was not created: $launcher"
    }
    $launcherConfig = Join-Path $appImageDir "app\$appName.cfg"
    if (-not (Test-Path -LiteralPath $launcherConfig) -or
        -not (Select-String -LiteralPath $launcherConfig -SimpleMatch $cloudJavaOption -Quiet)) {
        throw "App-image does not contain the configured cloud API URL."
    }

    Compress-Archive -LiteralPath $appImageDir -DestinationPath $archivePath -CompressionLevel Optimal

    if (-not $SkipInstaller) {
        $wixBin = Ensure-WixToolset
        $env:Path = "$wixBin;$env:Path"
        jpackage `
            --type exe `
            --name $appName `
            --app-version $projectVersion `
            --vendor "SQLTeacher Project" `
            --description "Local-first SQL teaching and practice desktop application" `
            --copyright "Copyright 2026 SQLTeacher Project" `
            --app-image $appImageDir `
            --icon $iconPath `
            --dest $outputPath `
            --win-menu `
            --win-menu-group "SQLTeacher" `
            --win-shortcut `
            --win-dir-chooser `
            --win-per-user-install `
            --install-dir "SQLTeacher-App" `
            --win-upgrade-uuid "569f427c-d027-4420-8477-7222c9ba6c55"
        if ($LASTEXITCODE -ne 0) {
            throw "jpackage installer failed with exit code $LASTEXITCODE."
        }
        if (-not (Test-Path -LiteralPath $installerPath)) {
            throw "Windows installer was not created: $installerPath"
        }
    }

    $releaseArtifacts = @($archivePath)
    if (-not $SkipInstaller) {
        $releaseArtifacts += $installerPath
    }
    $checksumLines = $releaseArtifacts | ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $([System.IO.Path]::GetFileName($_))"
    }
    Set-Content -LiteralPath $checksumPath -Value $checksumLines -Encoding ascii

    Write-Host "Created app-image: $appImageDir"
    Write-Host "Created release archive: $archivePath"
    if (-not $SkipInstaller) {
        Write-Host "Created Windows installer: $installerPath"
    }
    Write-Host "Created checksums: $checksumPath"
} finally {
    Pop-Location
}
