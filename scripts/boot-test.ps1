# Mili Platform Boot Test
#
# Usage:
#   .\scripts\boot-test.ps1                            # defaults to ./test_client
#   .\scripts\boot-test.ps1 -GameDir D:\mc\testserver  # custom
#
# Resolves the project root from this script's location (no hardcoded E:\loader),
# then finds the Mili platform shadow JAR via Gradle's standard layout.

param(
    [string]$GameDir = '',
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'

# Derive project root from this script's own location so the script is portable.
$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProjectDir = Resolve-Path (Join-Path $ScriptDir '..')

if (-not $GameDir) {
    $GameDir = Join-Path $ProjectDir 'test_client'
}

# Find the platform shadow JAR: build/libs/mili-<version>-mc26.2.jar
$platformJar = Get-ChildItem -Path (Join-Path $ProjectDir 'mili-loader\build\libs') -Filter 'mili-*.jar' |
    Where-Object { $_.Name -notmatch 'javadoc|sources|plain' } |
    Select-Object -First 1

if (-not $platformJar) {
    Write-Error "Platform JAR not found. Run: gradle :mili-loader:shadowJar"
    exit 1
}

# Resolve JDK: prefer JAVA_HOME, otherwise require env var.
$jhome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    Write-Error 'JAVA_HOME is not set. Point it to a JDK 21+ (JDK 25 recommended).'
    exit 1
}
$javaExe = Join-Path $jhome 'bin\java.exe'

Write-Host '=== Mili Platform Boot Test ===' -ForegroundColor Cyan
Write-Host "Project:  $ProjectDir"
Write-Host "Loader:   $($platformJar.FullName)"
Write-Host "GameDir:  $GameDir"
Write-Host "Java:     $javaExe"
Write-Host ''

$logFile = Join-Path $ProjectDir 'boot_test.log'
$errFile = Join-Path $ProjectDir 'boot_test.err'
foreach ($f in @($logFile, $errFile)) { if (Test-Path $f) { Remove-Item $f -Force } }

$proc = Start-Process -FilePath $javaExe `
    -ArgumentList @('-Xmx1G', '-Xms256m', '-jar', $platformJar.FullName, $GameDir) `
    -RedirectStandardOutput $logFile -RedirectStandardError $errFile `
    -PassThru -NoNewWindow

Write-Host "Started (PID $($proc.Id)). Waiting up to ${TimeoutSeconds}s..."
$timer = [Diagnostics.Stopwatch]::StartNew()
$bootOk    = $false
$tickCount = 0

while (-not $proc.HasExited -and $timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    Start-Sleep -Milliseconds 500
    if (Test-Path $logFile) {
        $c = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
        if ($c -match 'LWJGL version') { $bootOk = $true }
        if ($c -match 'client.tick')   { $tickCount++ }
        if ($c -match 'Game crashed')  { break }
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
    if ($errContent) {
        Write-Host "`n=== STDERR ===" -ForegroundColor Yellow
        Write-Host $errContent.Substring([Math]::Min(500, $errContent.Length))
    }
}
