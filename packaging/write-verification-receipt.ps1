<#
.SYNOPSIS
    LEG-15 (v3.4.2): 本地全量测试通过后生成 verification-receipt.json，供 release
    workflow 在凭据绑定发布提交且两项摘要均为成功时跳过 CI 重复测试。
.DESCRIPTION
    发布流程顺序：本地全量 mvn test + npm test → 提交发布内容 → 运行本脚本（要求
    工作区干净，凭据绑定当前 HEAD）→ 提交凭据 → 打 tag → 推送。CI 接受两种绑定：
    凭据 commit 等于 tag 提交，或等于 tag 提交的父提交且两者差异仅为本凭据文件。
    凭据缺失、SHA 不符或记录任何失败时，CI 一律回退全量测试。
#>
param(
    [Parameter(Mandatory = $true)][int]$MvnTests,
    [int]$MvnFailures = 0,
    [int]$MvnErrors = 0,
    [int]$MvnSkipped = 0,
    [Parameter(Mandatory = $true)][int]$NpmTests,
    [int]$NpmFailures = 0
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$receiptPath = Join-Path $projectRoot "verification-receipt.json"

Push-Location $projectRoot
try {
    $commit = (git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') {
        throw "Unable to determine the HEAD commit for the verification receipt."
    }
    $dirty = @(git status --porcelain)
    if ($dirty.Count -gt 0) {
        throw "Working tree has uncommitted changes; commit the release content before writing the receipt:`n$($dirty -join "`n")"
    }

    $javaExe = "java"
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path -LiteralPath $candidate) { $javaExe = $candidate }
    }
    $jdkLine = [string]((& $javaExe -version 2>&1) | Select-Object -First 1)
    $nodeLine = [string](& node --version)
    if ([string]::IsNullOrWhiteSpace($jdkLine) -or [string]::IsNullOrWhiteSpace($nodeLine)) {
        throw "Unable to detect the local JDK/Node versions for the receipt."
    }

    $receipt = [ordered]@{
        schemaVersion  = 1
        commit         = $commit
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
        jdkVersion     = $jdkLine.Trim('"')
        nodeVersion    = $nodeLine
        maven          = [ordered]@{
            testsRun = $MvnTests; failures = $MvnFailures
            errors   = $MvnErrors; skipped = $MvnSkipped
        }
        npm            = [ordered]@{ testsRun = $NpmTests; failures = $NpmFailures }
    }
    $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptPath -Encoding utf8
    Write-Host "Wrote verification receipt for commit ${commit}: $receiptPath"
    if ($MvnFailures -ne 0 -or $MvnErrors -ne 0 -or $NpmFailures -ne 0) {
        Write-Host "::warning::Receipt records failures; CI will NOT skip tests and the release must not proceed."
    }
} finally {
    Pop-Location
}
