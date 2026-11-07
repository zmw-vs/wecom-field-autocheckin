@echo off
setlocal
set JAVA_HOME=D:/Android/jdk/jdk-17.0.20.1+1
set ANDROID_HOME=D:/Android/Sdk
set ANDROID_SDK_ROOT=D:/Android/Sdk
cd /d "%~dp0"
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo.
    echo BUILD FAILED - see log above
    pause
    exit /b 1
)
echo.
echo BUILD OK: app\build\outputs\apk\debug\app-debug.apk
pause
