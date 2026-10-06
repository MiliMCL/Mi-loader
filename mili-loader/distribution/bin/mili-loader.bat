@echo off
REM ==========================================================================
REM  Mili Platform launcher (Windows)
REM
REM  First run downloads Minecraft from the official Mojang servers into the
REM  game directory (about 600 MB) and verifies it. Later runs start directly.
REM
REM  WARNING - two rules for editing this file:
REM    1. ASCII only. cmd.exe reads .bat using the OEM code page (GBK/936 on a
REM       zh-CN system), so CJK text is decoded into garbage and stray bytes
REM       can break command parsing.
REM    2. CRLF line endings. A Unix-style LF-only file is not split into
REM       commands correctly by cmd.exe, which corrupts FOR loops and
REM       percent-variable expansion.
REM
REM  Note: do not write a literal percent sign in these comments. cmd expands
REM  percent variables even inside REM lines.
REM ==========================================================================

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "DIST_DIR=%%~fI"
set "GAME_DIR=%DIST_DIR%\game"
set "MODS_DIR=%DIST_DIR%\mods"

REM Locate the platform JAR
set "PLATFORM_JAR="
for %%F in ("%DIST_DIR%\core\mili-*.jar") do (
    if not defined PLATFORM_JAR set "PLATFORM_JAR=%%F"
)
if not defined PLATFORM_JAR (
    echo [Mili] ERROR: Platform JAR not found in "%DIST_DIR%\core\"
    exit /b 1
)

REM Locate Java 25
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

if not exist "%GAME_DIR%" mkdir "%GAME_DIR%"
if not exist "%MODS_DIR%" mkdir "%MODS_DIR%"

"%JAVA_BIN%" -Xmx2G -Dfile.encoding=UTF-8 -jar "%PLATFORM_JAR%" "%GAME_DIR%" --mili-mods "%MODS_DIR%" %*
endlocal
