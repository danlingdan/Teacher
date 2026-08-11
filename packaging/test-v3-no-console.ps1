param(
    [string]$Executable = "target/v3-portable/SQLTeacher/SQLTeacher.exe",
    [int]$StartupTimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"

$resolvedExecutable = (Resolve-Path -LiteralPath $Executable).Path
$desktopProcess = $null
$javaProcess = $null

try {
    $desktopProcess = Start-Process -FilePath $resolvedExecutable -PassThru
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)

    do {
        Start-Sleep -Milliseconds 200
        $desktopProcess.Refresh()
        $javaProcess = Get-CimInstance Win32_Process |
            Where-Object { $_.ParentProcessId -eq $desktopProcess.Id -and $_.Name -eq "java.exe" } |
            Select-Object -First 1
    } while (-not $desktopProcess.HasExited -and -not $javaProcess -and (Get-Date) -lt $deadline)

    if ($desktopProcess.HasExited) {
        throw "SQLTeacher exited before the Java sidecar was ready."
    }
    if (-not $javaProcess) {
        throw "Bundled Java sidecar was not found within $StartupTimeoutSeconds seconds."
    }

    $sidecar = Get-Process -Id $javaProcess.ProcessId -ErrorAction Stop
    if ($sidecar.MainWindowHandle -ne 0) {
        throw "Java sidecar opened a visible window (handle $($sidecar.MainWindowHandle))."
    }

    Write-Output "No-console smoke passed: desktopPid=$($desktopProcess.Id), javaPid=$($sidecar.Id), javaWindow=$($sidecar.MainWindowHandle)"
}
finally {
    if ($desktopProcess -and -not $desktopProcess.HasExited) {
        Stop-Process -Id $desktopProcess.Id -Force -ErrorAction SilentlyContinue
        $desktopProcess.WaitForExit(5000) | Out-Null
    }
    if ($javaProcess) {
        Stop-Process -Id $javaProcess.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

