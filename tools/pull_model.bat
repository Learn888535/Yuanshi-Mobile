@echo off
title Yuanshi Model Pull Tool
setlocal enabledelayedexpansion

:: Pull model files from Android device to project assets folder
:: Usage: pull_model.bat [source_package]
:: Default source: ai.guiji.duix.test

set ASSETS_DIR=app\src\main\assets\duix\model
set SRC_PKG=%1
if "%SRC_PKG%"=="" set SRC_PKG=ai.guiji.duix.test
set REMOTE_PATH=/storage/emulated/0/Android/data/%SRC_PKG%/files/duix/model

echo Yuanshi - Model Pull Tool
echo Source: %SRC_PKG%
echo Dest:   %ASSETS_DIR%
echo.

:: Check ADB
where adb >nul 2>&1
if errorlevel 1 (
    echo ERROR: adb not found in PATH
    pause
    exit /b 1
)

:: Check device
adb get-state >nul 2>&1
if errorlevel 1 (
    echo ERROR: No device detected
    pause
    exit /b 1
)

echo Device connected.
echo.

:: Create destination dirs
if not exist "%ASSETS_DIR%" mkdir "%ASSETS_DIR%"
if not exist "%ASSETS_DIR%\tmp" mkdir "%ASSETS_DIR%\tmp"

:: List remote models
echo Models available on device:
echo.
adb shell ls "%REMOTE_PATH%"
echo.

:: Pull base config
echo Pulling gj_dh_res...
adb pull "%REMOTE_PATH%/gj_dh_res" "%ASSETS_DIR%/gj_dh_res"

:: Pull tag files
echo Pulling tmp/...
adb pull "%REMOTE_PATH%/tmp" "%ASSETS_DIR%/tmp"

:: Pull each model dir
for /f "tokens=*" %%d in ('adb shell ls "%REMOTE_PATH%"') do (
    if not "%%d"=="tmp" if not "%%d"=="gj_dh_res" (
        echo Pulling model: %%d...
        adb pull "%REMOTE_PATH%/%%d" "%ASSETS_DIR%/%%d"
    )
)

echo.
echo Done! Models pulled to: %ASSETS_DIR%
echo Rebuild APK to bundle them:
echo   gradlew assembleDebug
echo.
pause
