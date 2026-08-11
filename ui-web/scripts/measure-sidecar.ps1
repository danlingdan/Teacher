param(
    [string]$SidecarRoot = "..\src-tauri\sidecar",
    [string]$OutputPath = "..\..\target\v3-performance.json"
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "This performance probe requires PowerShell 7 or later. Run it with pwsh."
}
$sidecarPath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $SidecarRoot))
$outputFile = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $OutputPath))
$java = Join-Path $sidecarPath "runtime\bin\java.exe"
if (-not (Test-Path -LiteralPath $java)) {
    throw "SQLTeacher 3 sidecar runtime was not found. Run packaging/build-v3-sidecar.ps1 first."
}

function Invoke-LocalRequest {
    param([System.Diagnostics.Process]$Process, [string]$RequestId, [string]$Method, [hashtable]$Params = @{})
    $request = [ordered]@{ requestId = $RequestId; method = $Method; params = $Params; contractVersion = "3.0-v1" } |
        ConvertTo-Json -Compress
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $Process.StandardInput.WriteLine($request)
    $Process.StandardInput.Flush()
    do {
        $readTask = $Process.StandardOutput.ReadLineAsync()
        if (-not $readTask.Wait(15000)) {
            throw "Java Sidecar request '$Method' did not respond within 15 seconds."
        }
        $line = $readTask.Result
        if ($null -eq $line) { throw "Java Sidecar exited before responding to '$Method'." }
        $response = $line | ConvertFrom-Json
    } while ($response.type -eq "event" -or $response.requestId -ne $RequestId)
    $watch.Stop()
    if ($null -ne $response.error) { throw "Java Sidecar request '$Method' failed: $($response.error.code)" }
    [pscustomobject]@{ ElapsedMs = $watch.Elapsed.TotalMilliseconds; Result = $response.result }
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    $ordered = @($Values | Sort-Object)
    $index = [Math]::Ceiling($Percentile * $ordered.Count) - 1
    $ordered[[Math]::Max(0, [Math]::Min($ordered.Count - 1, $index))]
}

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $java
$psi.WorkingDirectory = $sidecarPath
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.Arguments = '-Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -cp "app/*;app/lib/*" com.sqlteacher.desktop.bridge.LocalAppHost'

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $psi
$startupWatch = [System.Diagnostics.Stopwatch]::StartNew()
if (-not $process.Start()) { throw "Unable to start the SQLTeacher 3 Java Sidecar." }
try {
    $health = Invoke-LocalRequest -Process $process -RequestId "health" -Method "system.health"
    $startupWatch.Stop()
    $coreInitialization = Invoke-LocalRequest -Process $process -RequestId "session" -Method "session.current"
    1..3 | ForEach-Object { $null = Invoke-LocalRequest -Process $process -RequestId "warmup-$_" -Method "system.health" }
    [double[]]$roundTrips = 1..10 | ForEach-Object {
        (Invoke-LocalRequest -Process $process -RequestId "health-$_" -Method "system.health").ElapsedMs
    }
    $homeResult = Invoke-LocalRequest -Process $process -RequestId "home" -Method "home.summary"
    $courseWorkspace = Invoke-LocalRequest -Process $process -RequestId "courses" -Method "course.workspace"
    $process.Refresh()
    $workingSetMb = [Math]::Round($process.WorkingSet64 / 1MB, 2)
    $shutdownWatch = [System.Diagnostics.Stopwatch]::StartNew()
    $null = Invoke-LocalRequest -Process $process -RequestId "shutdown" -Method "system.shutdown"
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(5000)) { throw "Java Sidecar did not exit within five seconds." }
    $shutdownWatch.Stop()

    $report = [ordered]@{
        schemaVersion = 1; contractVersion = "3.0-v1"; measuredAt = [DateTimeOffset]::UtcNow.ToString("o")
        environment = [ordered]@{ os = [System.Environment]::OSVersion.VersionString; processorCount = [System.Environment]::ProcessorCount; javaVersion = $health.Result.javaVersion; javaVendor = $health.Result.javaVendor }
        measurements = [ordered]@{
            sidecarStartToHealthMs = [Math]::Round($startupWatch.Elapsed.TotalMilliseconds, 2)
            coreInitializationMs = [Math]::Round($coreInitialization.ElapsedMs, 2)
            ipcRoundTripP50Ms = [Math]::Round((Get-Percentile $roundTrips 0.50), 2)
            ipcRoundTripP95Ms = [Math]::Round((Get-Percentile $roundTrips 0.95), 2)
            ipcRoundTripMaxMs = [Math]::Round(($roundTrips | Measure-Object -Maximum).Maximum, 2)
            homeSummaryMs = [Math]::Round($homeResult.ElapsedMs, 2); courseWorkspaceMs = [Math]::Round($courseWorkspace.ElapsedMs, 2)
            javaWorkingSetMb = $workingSetMb; shutdownMs = [Math]::Round($shutdownWatch.Elapsed.TotalMilliseconds, 2)
        }
        budgets = [ordered]@{ ipcRoundTripP95Ms = 20; regularLocalDataMs = 800; shutdownMs = 2000 }
        passed = ((Get-Percentile $roundTrips 0.95) -le 20 -and $homeResult.ElapsedMs -le 800 -and $shutdownWatch.Elapsed.TotalMilliseconds -le 2000)
    }
    $directory = Split-Path -Parent $outputFile
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $outputFile -Encoding utf8
    $report | ConvertTo-Json -Depth 6
} finally {
    if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
    $process.Dispose()
}
