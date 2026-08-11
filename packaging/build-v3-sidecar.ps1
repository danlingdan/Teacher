param(
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sidecarRoot = Join-Path $projectRoot "ui-web\src-tauri\sidecar"
$appRoot = Join-Path $sidecarRoot "app"
$libraryRoot = Join-Path $appRoot "lib"

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

function Get-JavaVersionLine {
    param([string]$Executable)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $lines = & $Executable -version 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to read the configured Java version."
        }
        return $lines | Select-Object -First 1 | ForEach-Object { $_.ToString() }
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javaCommand = Get-Command java -ErrorAction Stop
    $JavaHome = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
}
$javaExecutable = Join-Path $JavaHome "bin\java.exe"
$jlinkExecutable = Join-Path $JavaHome "bin\jlink.exe"
if (-not (Test-Path -LiteralPath $javaExecutable) -or -not (Test-Path -LiteralPath $jlinkExecutable)) {
    throw "JavaHome must point to a JDK 25 installation containing java.exe and jlink.exe."
}
$javaVersion = Get-JavaVersionLine -Executable $javaExecutable
if ($javaVersion -notmatch 'version "25(?:\.|\")') {
    throw "SQLTeacher 3 sidecar requires JDK 25, found: $javaVersion"
}

Assert-ChildPath -Candidate $sidecarRoot -Parent (Join-Path $projectRoot "ui-web\src-tauri")

[xml]$pom = Get-Content -LiteralPath (Join-Path $projectRoot "pom.xml") -Raw
$projectVersion = [string]$pom.project.version
$jarName = "Teacher-$projectVersion.jar"

Push-Location $projectRoot
try {
    if (Test-Path -LiteralPath $sidecarRoot) {
        Remove-Item -LiteralPath $sidecarRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $libraryRoot | Out-Null

    $env:JAVA_HOME = [System.IO.Path]::GetFullPath($JavaHome)
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    mvn -q -DskipTests clean package dependency:copy-dependencies `
        "-DincludeScope=runtime" `
        "-DoutputDirectory=$libraryRoot"
    if ($LASTEXITCODE -ne 0) { throw "Unable to build the Java 25 sidecar application." }

    Copy-Item -LiteralPath (Join-Path $projectRoot "target\$jarName") `
        -Destination (Join-Path $appRoot $jarName) -Force

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jarPath = Join-Path $appRoot $jarName
    $jarArchive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $forbiddenEntries = @($jarArchive.Entries.FullName | Where-Object {
            $_ -match '(?i)(^|/)(javafx|fxml)(/|$)|application/mock|MockApplicationServiceConfig|DesignSystemPage|MigrationPage|TestPage'
        })
        if ($forbiddenEntries.Count -gt 0) {
            throw "Java sidecar contains removed desktop or test entries: $($forbiddenEntries -join ', ')"
        }
    } finally {
        $jarArchive.Dispose()
    }

    & $jlinkExecutable `
        --add-modules java.se,jdk.crypto.ec,jdk.unsupported `
        --strip-debug `
        --no-header-files `
        --no-man-pages `
        --compress zip-6 `
        --output (Join-Path $sidecarRoot "runtime")
    if ($LASTEXITCODE -ne 0) { throw "Unable to create the Java 25 sidecar runtime image." }

    $metadata = [ordered]@{
        contractVersion = "3.0-v1"
        applicationVersion = $projectVersion
        javaVersion = $javaVersion
        mainClass = "com.sqlteacher.desktop.bridge.LocalAppHost"
        generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
    }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $sidecarRoot "sidecar.json") -Encoding utf8
    Write-Host "Created Java 25 sidecar resources: $sidecarRoot"
} finally {
    Pop-Location
}
