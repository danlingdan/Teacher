param(
    [string]$OutputDir = "target\installer"
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
$appImageDir = Join-Path $outputPath "SQLTeacherStage1"

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

if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw "jpackage was not found. Use JDK 21 or newer with jpackage available on PATH."
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

    Copy-Item -LiteralPath "target\Teacher-1.0-SNAPSHOT.jar" `
        -Destination (Join-Path $inputDir "Teacher-1.0-SNAPSHOT.jar") `
        -Force

    New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
    if (Test-Path -LiteralPath $appImageDir) {
        Remove-Item -LiteralPath $appImageDir -Recurse -Force
    }

    jpackage `
        --type app-image `
        --name SQLTeacherStage1 `
        --input $inputDir `
        --main-jar Teacher-1.0-SNAPSHOT.jar `
        --main-class com.sqlteacher.desktop.SqlTeacherFxApp `
        --java-options "--enable-native-access=ALL-UNNAMED" `
        --java-options '--module-path=$APPDIR\javafx-modules' `
        --java-options "--add-modules=javafx.controls,javafx.fxml" `
        --java-options "-Dfile.encoding=UTF-8" `
        --dest $outputPath
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed with exit code $LASTEXITCODE."
    }

    $launcher = Join-Path $appImageDir "SQLTeacherStage1.exe"
    if (-not (Test-Path -LiteralPath $launcher)) {
        throw "App-image launcher was not created: $launcher"
    }

    Write-Host "Created app-image: $appImageDir"
} finally {
    Pop-Location
}
