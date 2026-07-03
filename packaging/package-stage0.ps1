param(
    [string]$OutputDir = "target\installer"
)

$ErrorActionPreference = "Stop"

mvn -q -DskipTests package dependency:copy-dependencies "-DoutputDirectory=target\stage0-input\lib"
if ($LASTEXITCODE -ne 0) {
    throw "Maven package failed with exit code $LASTEXITCODE."
}

if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw "jpackage was not found. Use a JDK that includes jpackage."
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
New-Item -ItemType Directory -Force -Path "target\stage0-input" | Out-Null
Copy-Item -Force "target\Teacher-1.0-SNAPSHOT.jar" "target\stage0-input\Teacher-1.0-SNAPSHOT.jar"

$appImageDir = Join-Path $OutputDir "SQLTeacherStage0"
if (Test-Path $appImageDir) {
    Remove-Item -LiteralPath $appImageDir -Recurse -Force
}

jpackage `
    --type app-image `
    --name SQLTeacherStage0 `
    --input target\stage0-input `
    --main-jar Teacher-1.0-SNAPSHOT.jar `
    --main-class com.sqlteacher.TechnologyVerificationApp `
    --java-options "--enable-native-access=ALL-UNNAMED" `
    --dest $OutputDir

if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE."
}
