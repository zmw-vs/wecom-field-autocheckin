@echo off
setlocal
cd /d "%~dp0"
set "JAVA_HOME=D:\Android\jdk\jdk-17.0.20.1+1"
set "ANDROID_HOME=D:\Android\Sdk"
set "ANDROID_SDK_ROOT=D:\Android\Sdk"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK 17 not found: %JAVA_HOME%
  echo Please edit JAVA_HOME in build-release.bat
  pause
  exit /b 1
)
if not exist "keystore.properties" (
  echo [ERROR] keystore.properties missing, release APK cannot be signed
  pause
  exit /b 1
)

call gradlew.bat assembleRelease --console=plain
if errorlevel 1 (
  echo.
  echo [ERROR] BUILD FAILED - see log above
  pause
  exit /b 1
)

if not exist "app\build\outputs\apk\release\app-release.apk" (
  echo [ERROR] app-release.apk not found
  pause
  exit /b 1
)
copy /Y "app\build\outputs\apk\release\app-release.apk" "..\autocheckin-release.apk" >nul
echo.
echo BUILD OK
echo APK: %~dp0..\autocheckin-release.apk
pause
