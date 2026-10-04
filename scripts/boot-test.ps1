# Mili Boot Test - Lightweight
param(
    [string]$GameDir = 'E:\loader\test_client',
    [int]$TimeoutSeconds = 90,
    [string]$Java25Home = 'C:\Users\Administrator\Downloads\jdk-25_windows-x64_bin\jdk-25.0.4'
)

$ErrorActionPreference = 'Stop'
$loader = (Get-ChildItem 'E:\loader\build\libs\minecraft-runtime-*.jar' | Select-Object -First 1).FullName
if (-not $loader) { Write-Error 'Loader JAR not found. Run: gradle jar'; exit 1 }

Write-Host '=== Mili Boot Test (Lightweight) ===' -ForegroundColor Cyan
Write-Host "Loader:  $loader"
Write-Host "GameDir: $GameDir"
Write-Host ''

$logFile = 'E:\loader\boot_test.log'
$errFile = 'E:\loader\boot_test.err'
if (Test-Path $logFile) { Remove-Item $logFile -Force }

$env:JAVA_HOME = $Java25Home
$env:JAVA_TOOL_OPTIONS = '--enable-native-access=ALL-UNNAMED'

$proc = Start-Process -FilePath "$Java25Home\bin\java" `
    -ArgumentList @('-Xmx1G', '-Xms256m', '-jar', $loader, $GameDir) `
    -RedirectStandardOutput $logFile -RedirectStandardError $errFile `
    -PassThru -NoNewWindow

Write-Host "Started (PID $($proc.Id)). Waiting up to ${TimeoutSeconds}s..."
$timer = [Diagnostics.Stopwatch]::StartNew()
$bootOk = $false
$tickCount = 0

while ($proc.HasExited -eq $false -and $timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    Start-Sleep -Milliseconds 500
    if (Test-Path $logFile) {
        $c = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
        if ($c -match 'LWJGL version') { $bootOk = $true }
        if ($c -match 'client.tick') { $tickCount++ }
        if ($c -match 'Game crashed') { break }
    }
}

if (-not $proc.HasExited) {
    Write-Host 'Timeout reached. Stopping...' -ForegroundColor Yellow
    try { $proc.Kill() } catch {}
    $proc.WaitForExit(5000) | Out-Null
}

$timer.Stop()
$exitCode = if ($proc.HasExited) { $proc.ExitCode } else { -1 }

Write-Host ''
Write-Host '=== Results ===' -ForegroundColor Cyan
Write-Host "Duration:  $($timer.Elapsed.TotalSeconds.ToString('F1'))s"
Write-Host "Exit code: $exitCode"
Write-Host "Boot ok:   $bootOk"

if ($exitCode -eq 0) {
    Write-Host 'SUCCESS' -ForegroundColor Green
} else {
    Write-Host 'FAILED' -ForegroundColor Red
    Get-Content $logFile -Tail 15
}

if (Test-Path $errFile) {
    $errContent = Get-Content $errFile -Raw
    if ($errContent) { Write-Host "`n=== STDERR ===" -ForegroundColor Yellow; Write-Host $errContent.Substring([Math]::Min(500, $errContent.Length)) }
}
