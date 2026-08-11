param(
    [string]$SidecarRoot = "..\src-tauri\sidecar",
    [string]$OutputPath = "..\..\target\v3-alpha1-performance.json"
)

$ErrorActionPreference = "Stop"
$uiRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sidecarPath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $SidecarRoot))
$outputFile = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $OutputPath))
$java = Join-Path $sidecarPath "runtime\bin\java.exe"
if (-not (Test-Path -LiteralPath $java)) {
    throw "Alpha.1 sidecar runtime was not found. Run packaging/build-v3-sidecar.ps1 first."
}

function Invoke-LocalRequest {
    param(
        [System.Diagnostics.Process]$Process,
        [string]$RequestId,
        [string]$Method,
        [hashtable]$Params = @{}
    )
    $request = [ordered]@{
        requestId = $RequestId
        method = $Method
        params = $Params
        contractVersion = "3.0-alpha.1"
    } | ConvertTo-Json -Compress
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $Process.StandardInput.WriteLine($request)
    $Process.StandardInput.Flush()
    do {
        $line = $Process.StandardOutput.ReadLine()
        if ($null -eq $line) { throw "Java Sidecar exited before responding to '$Method'." }
        $response = $line | ConvertFrom-Json
    } while ($response.type -eq "event" -or $response.requestId -ne $RequestId)
    $watch.Stop()
    if ($null -ne $response.error) {
        throw "Java Sidecar request '$Method' failed: $($response.error.code)"
    }
    return [pscustomobject]@{ ElapsedMs = $watch.Elapsed.TotalMilliseconds; Result = $response.result }
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    $ordered = @($Values | Sort-Object)
    $index = [Math]::Ceiling($Percentile * $ordered.Count) - 1
    return $ordered[[Math]::Max(0, [Math]::Min($ordered.Count - 1, $index))]
}

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $java
$psi.WorkingDirectory = $sidecarPath
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.ArgumentList.Add("-Dfile.encoding=UTF-8")
$psi.ArgumentList.Add("--enable-native-access=ALL-UNNAMED")
$psi.ArgumentList.Add("-cp")
$psi.ArgumentList.Add("app/*;app/lib/*")
$psi.ArgumentList.Add("com.sqlteacher.desktop.bridge.LocalAppHost")

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $psi
$startupWatch = [System.Diagnostics.Stopwatch]::StartNew()
if (-not $process.Start()) { throw "Unable to start the Alpha.1 Java Sidecar." }
try {
    $health = Invoke-LocalRequest -Process $process -RequestId "health" -Method "system.health"
    $startupWatch.Stop()
    1..3 | ForEach-Object {
        $null = Invoke-LocalRequest -Process $process -RequestId "warmup-$_" -Method "benchmark.echo" `
            -Params @{ sequence = $_ }
    }
    [double[]]$roundTrips = 1..10 | ForEach-Object {
        (Invoke-LocalRequest -Process $process -RequestId "echo-$_" -Method "benchmark.echo" `
            -Params @{ sequence = $_ }).ElapsedMs
    }
    $homeResult = Invoke-LocalRequest -Process $process -RequestId "home" -Method "home.summary"
    $knowledge = Invoke-LocalRequest -Process $process -RequestId "knowledge" -Method "knowledge.sample"
    $process.Refresh()
    $workingSetMb = [Math]::Round($process.WorkingSet64 / 1MB, 2)
    $shutdownWatch = [System.Diagnostics.Stopwatch]::StartNew()
    $null = Invoke-LocalRequest -Process $process -RequestId "shutdown" -Method "system.shutdown"
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(5000)) {
        throw "Java Sidecar did not exit within five seconds."
    }
    $shutdownWatch.Stop()

    $report = [ordered]@{
        schemaVersion = 1
        contractVersion = "3.0-alpha.1"
        measuredAt = [DateTimeOffset]::UtcNow.ToString("o")
        environment = [ordered]@{
            os = [System.Environment]::OSVersion.VersionString
            processorCount = [System.Environment]::ProcessorCount
            javaVersion = $health.Result.javaVersion
            javaVendor = $health.Result.javaVendor
        }
        measurements = [ordered]@{
            sidecarStartToHealthMs = [Math]::Round($startupWatch.Elapsed.TotalMilliseconds, 2)
            ipcRoundTripP50Ms = [Math]::Round((Get-Percentile $roundTrips 0.50), 2)
            ipcRoundTripP95Ms = [Math]::Round((Get-Percentile $roundTrips 0.95), 2)
            ipcRoundTripMaxMs = [Math]::Round(($roundTrips | Measure-Object -Maximum).Maximum, 2)
            homeSummaryMs = [Math]::Round($homeResult.ElapsedMs, 2)
            knowledgeSampleMs = [Math]::Round($knowledge.ElapsedMs, 2)
            javaWorkingSetMb = $workingSetMb
            shutdownMs = [Math]::Round($shutdownWatch.Elapsed.TotalMilliseconds, 2)
        }
        budgets = [ordered]@{
            ipcRoundTripP95Ms = 20
            shutdownMs = 2000
        }
        passed = ((Get-Percentile $roundTrips 0.95) -le 20 -and $shutdownWatch.Elapsed.TotalMilliseconds -le 2000)
    }
    $directory = Split-Path -Parent $outputFile
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $outputFile -Encoding utf8
    $report | ConvertTo-Json -Depth 6
} finally {
    if (-not $process.HasExited) {
        $process.Kill($true)
        $process.WaitForExit()
    }
    $process.Dispose()
}
