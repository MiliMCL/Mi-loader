@echo off
REM ==========================================================================
REM  Mili Platform launcher (Windows)
REM
REM  First run downloads Minecraft from the official Mojang servers into the
REM  game directory (about 600 MB) and verifies it. Later runs start directly.
REM
REM  SETUP - answered once, then remembered in bin\mili-loader.cfg:
REM    * game name    shown in-game, and used to derive the offline UUID
REM    * access token only needed for online play; press Enter to skip and
REM                    stay fully offline
REM
REM  WARNING - three rules for editing this file:
REM    1. ASCII only. cmd.exe reads .bat using the OEM code page (GBK/936 on a
REM       zh-CN system), so CJK text is decoded into garbage and stray bytes
REM       can break command parsing.
REM    2. CRLF line endings. A Unix-style LF-only file is not split into
REM       commands correctly by cmd.exe, which corrupts FOR loops and
REM       percent-variable expansion.
REM    3. No literal percent sign inside a REM line. cmd expands percent
REM       variables even inside REM.
REM ==========================================================================

setlocal enabledelayedexpansion

set "BIN_DIR=%~dp0"
for %%I in ("%BIN_DIR%..") do set "DIST_DIR=%%~fI"
set "GAME_DIR=%DIST_DIR%\game"
set "MODS_DIR=%DIST_DIR%\mods"
set "CFG=%BIN_DIR%mili-loader.cfg"
set "ASSETS_DIR=%GAME_DIR%\assets"
set "NATIVES_DIR=%GAME_DIR%\natives"

REM --------------------------------------------------------------------------
REM  Locate the platform JAR
REM --------------------------------------------------------------------------
set "PLATFORM_JAR="
for %%F in ("%DIST_DIR%\core\mili-*.jar") do (
    if not defined PLATFORM_JAR set "PLATFORM_JAR=%%F"
)
if not defined PLATFORM_JAR (
    echo [Mili] ERROR: Platform JAR not found in "%DIST_DIR%\core\"
    exit /b 1
)

REM --------------------------------------------------------------------------
REM  Resolve the Minecraft version.
REM  Primary source: META-INF/mili/platform.json inside the platform JAR.
REM  Fallback: the -mcX.Y suffix in the JAR file name.
REM --------------------------------------------------------------------------
set "MC_VERSION="
set "MILI_JAR=%PLATFORM_JAR%"

set "PJ_TMP=%TEMP%\mili-platform-%RANDOM%.json"
if exist "%PJ_TMP%" del "%PJ_TMP%" >nul 2>&1
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Add-Type -AssemblyName System.IO.Compression.FileSystem; $z=[IO.Compression.ZipFile]::OpenRead($env:MILI_JAR); $e=$z.Entries | Where-Object { $_.FullName -eq 'META-INF/mili/platform.json' }; if ($e) { $r=New-Object IO.StreamReader($e.Open()); $r.ReadToEnd(); $r.Close() }; $z.Dispose() } catch { }" > "%PJ_TMP%" 2>nul
if exist "%PJ_TMP%" (
    for /f "usebackq tokens=*" %%L in (`findstr /c:"\"minecraft\":" "%PJ_TMP%" 2^>nul`) do (
        if not defined MC_VERSION (
            set "VAL=%%L"
            set "VAL=!VAL:"minecraft"=!"
            set "VAL=!VAL::=!"
            set "VAL=!VAL:"=!"
            set "VAL=!VAL:,=!"
            set "VAL=!VAL: =!"
            if defined VAL set "MC_VERSION=!VAL!"
        )
    )
    del "%PJ_TMP%" >nul 2>&1
)

if not defined MC_VERSION (
    for %%F in ("%PLATFORM_JAR%") do set "JAR_BASE=%%~nF"
    for /f "tokens=1,2,3 delims=-" %%A in ("!JAR_BASE!") do set "JAR_TAIL=%%C"
    if defined JAR_TAIL set "MC_VERSION=!JAR_TAIL:mc=!"
)

if not defined MC_VERSION (
    echo [Mili] ERROR: Cannot determine the Minecraft version.
    echo        Checked platform.json and the JAR file name in "%DIST_DIR%\core\".
    exit /b 1
)

REM --------------------------------------------------------------------------
REM  Locate Java 25
REM --------------------------------------------------------------------------
set "JAVA_BIN="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA_BIN (
    for %%F in (java.exe) do if not "%%~$PATH:F"=="" set "JAVA_BIN=%%~$PATH:F"
)
if not defined JAVA_BIN (
    echo [Mili] ERROR: Java not found. Please install JDK 25 and set JAVA_HOME.
    exit /b 1
)

REM --------------------------------------------------------------------------
REM  Read saved settings, or ask once and remember them.
REM  This block is not parenthesised on purpose: inside a block a percent
REM  sign would be evaluated before set /p assigns the variable, so the
REM  config file would be written with empty values.
REM --------------------------------------------------------------------------
set "SAVED_USER="
set "SAVED_TOKEN="
if exist "%CFG%" (
    for /f "usebackq tokens=1,* delims==" %%A in ("%CFG%") do (
        if "%%A"=="username" set "SAVED_USER=%%B"
        if "%%A"=="accessToken" set "SAVED_TOKEN=%%B"
    )
)

set "GAME_NAME=%SAVED_USER%"
if not defined GAME_NAME goto ask_setup
goto launch

:ask_setup
echo.
echo [Mili] First-time setup. You will be asked this only once.
echo.
set /p "GAME_NAME=Enter your game name (press Enter for Player): "
if not defined GAME_NAME set "GAME_NAME=Player"
set "SAVED_TOKEN="
set /p "SAVED_TOKEN=Access token for online play (press Enter to skip offline): "
> "%CFG%" echo username=%GAME_NAME%
>>"%CFG%" echo accessToken=%SAVED_TOKEN%
echo [Mili] Settings saved to "%CFG%".
echo.

:launch
REM --------------------------------------------------------------------------
REM  Derive the offline UUID from the game name, then assemble the auth args.
REM  A version 3 (MD5) UUID over the name, matching the scheme vanilla
REM  Minecraft uses for offline players. The name is handed to PowerShell
REM  through an environment variable because a quoted argument inside
REM  for /f backquotes loses its quotes.
REM --------------------------------------------------------------------------
set "MILI_NAME=%GAME_NAME%"
set "UUID_PS=$md5=[System.Security.Cryptography.MD5]::Create(); $b=[System.Text.Encoding]::UTF8.GetBytes('OfflinePlayer:'+$env:MILI_NAME); $h=$md5.ComputeHash($b); $h[6]=($h[6] -band 0x0f) -bor 0x30; $h[8]=($h[8] -band 0x3f) -bor 0x80; $sb=New-Object System.Text.StringBuilder; foreach($x in $h){ [void]$sb.Append($x.ToString('x2')) }; $t=$sb.ToString(); Write-Output ($t.Substring(0,8)+'-'+$t.Substring(8,4)+'-'+$t.Substring(12,4)+'-'+$t.Substring(16,4)+'-'+$t.Substring(20,12))"
set "GAME_UUID="
for /f "usebackq delims=" %%U in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "%UUID_PS%" 2^>nul`) do (
    if not defined GAME_UUID set "GAME_UUID=%%U"
)
if not defined GAME_UUID set "GAME_UUID=00000000-0000-0000-0000-000000000000"

if not exist "%GAME_DIR%" mkdir "%GAME_DIR%" >nul 2>&1
if not exist "%MODS_DIR%" mkdir "%MODS_DIR%" >nul 2>&1

set "ASSET_INDEX="
for %%F in ("%ASSETS_DIR%\indexes\*.json") do set "ASSET_INDEX=%%~nF"
if not defined ASSET_INDEX set "ASSET_INDEX=legacy"

set "AUTH_ARGS=--username "%GAME_NAME%" --uuid "%GAME_UUID%""
if defined SAVED_TOKEN set "AUTH_ARGS=!AUTH_ARGS! --accessToken "!SAVED_TOKEN!""

echo [Mili] Platform   : %PLATFORM_JAR%
echo [Mili] Game dir   : %GAME_DIR%
echo [Mili] Mods dir   : %MODS_DIR%
echo [Mili] MC version : %MC_VERSION%
echo [Mili] User       : %GAME_NAME%
if defined SAVED_TOKEN (echo [Mili] Auth       : online) else (echo [Mili] Auth       : offline)
echo.

"%JAVA_BIN%" -Xmx4G -Dfile.encoding=UTF-8 -Djava.library.path="%NATIVES_DIR%" -jar "%PLATFORM_JAR%" "%GAME_DIR%" --mili-mods "%MODS_DIR%" --version "%MC_VERSION%" --gameDir "%GAME_DIR%" --assetsDir "%ASSETS_DIR%" --assetIndex "%ASSET_INDEX%" %AUTH_ARGS% %*

endlocal
