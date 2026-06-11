@echo off
title Yuanshi Model Push Tool
setlocal enabledelayedexpansion

:: Push model folder from PC to Android device
:: Usage: push_model.bat <local_model_folder>
:: Example: push_model.bat D:\models\Lily

set PKG=com.yuanshi.avatar
set REMOTE_BASE=/storage/emulated/0/Android/data/%PKG%/files/duix/model

echo Yuanshi - Model Push Tool
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

:: Check args
if "%1"=="" (
    echo Usage: push_model.bat ^<local_model_folder^>
    echo Example: push_model.bat D:\models\Lily
    pause
    exit /b 1
)

set LOCAL_PATH=%1
if not exist "%LOCAL_PATH%" (
    echo ERROR: Path not found: %LOCAL_PATH%
    pause
    exit /b 1
)

:: Extract folder name as model name
for %%i in ("%LOCAL_PATH%") do set MODEL_NAME=%%~nxi

echo Model: %MODEL_NAME%
echo Local: %LOCAL_PATH%
echo Remote: %REMOTE_BASE%/%MODEL_NAME%
echo.

:: Push
echo Creating remote dirs...
adb shell mkdir -p %REMOTE_BASE%/%MODEL_NAME%
adb shell mkdir -p %REMOTE_BASE%/tmp

echo Pushing model files...
adb push "%LOCAL_PATH%\." %REMOTE_BASE%/%MODEL_NAME%/
if errorlevel 1 (
    echo ERROR: Push failed
    pause
    exit /b 1
)

echo Creating tag file...
adb shell touch %REMOTE_BASE%/tmp/%MODEL_NAME%

echo.
echo Done! Model "%MODEL_NAME%" pushed to device.
echo Open the app and it will appear in the model list.
echo.

pause
