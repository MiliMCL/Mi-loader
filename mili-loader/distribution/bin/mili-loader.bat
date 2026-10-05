@echo off
REM Mili Platform launcher (Windows)
REM
REM 首次运行会发现 game 目录里没有 Minecraft，自动从 Mojang 官方源下载并
REM 校验（约 600MB）。之后每次启动直接进入游戏。

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "DIST_DIR=%%~fI"
set "GAME_DIR=%DIST_DIR%\game"
set "MODS_DIR=%DIST_DIR%\mods"

REM 定位平台 JAR
set "PLATFORM_JAR="
for %%F in ("%DIST_DIR%\core\mili-*.jar") do (
    if not defined PLATFORM_JAR set "PLATFORM_JAR=%%F"
)
if not defined PLATFORM_JAR (
    echo [Mili] 错误: 在 "%DIST_DIR%\core\" 下找不到平台 JAR
    exit /b 1
)

REM 定位 Java 25
set "JAVA_BIN="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA_BIN (
    for %%F in (java.exe) do if not "%%~$PATH:F"=="" set "JAVA_BIN=%%~$PATH:F"
)
if not defined JAVA_BIN (
    echo [Mili] 错误: 找不到 Java。请安装 JDK 25 并设置 JAVA_HOME。
    exit /b 1
)

if not exist "%GAME_DIR%" mkdir "%GAME_DIR%"
if not exist "%MODS_DIR%" mkdir "%MODS_DIR%"

"%JAVA_BIN%" -Xmx2G -Dfile.encoding=UTF-8 -jar "%PLATFORM_JAR%" "%GAME_DIR%" %*
endlocal
