@echo off
setlocal
cd /d "%~dp0"
set "GRADLE_VERSION=8.14.3"
set "BOOTSTRAP_DIR=%CD%\.gradle-bootstrap"
set "GRADLE_HOME=%BOOTSTRAP_DIR%\gradle-%GRADLE_VERSION%"
set "GRADLE_EXE=%GRADLE_HOME%\bin\gradle.bat"

if not exist "%GRADLE_EXE%" (
    echo Downloading Gradle %GRADLE_VERSION% from the official Gradle distribution service...
    if not exist "%BOOTSTRAP_DIR%" mkdir "%BOOTSTRAP_DIR%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip=Join-Path '%BOOTSTRAP_DIR%' 'gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; Expand-Archive -LiteralPath $zip -DestinationPath '%BOOTSTRAP_DIR%' -Force; Remove-Item $zip"
    if errorlevel 1 (
        echo Failed to download or unpack Gradle. Check your internet connection.
        exit /b 1
    )
)

call "%GRADLE_EXE%" --no-daemon build
if errorlevel 1 exit /b 1

if not exist "build\libs\voxy-exclusion-zones-0.1.0.jar" (
    echo Gradle returned success but the expected remapped JAR was not found.
    exit /b 1
)

echo.
echo Build succeeded. Addon JAR:
echo %CD%\build\libs\voxy-exclusion-zones-0.1.0.jar
echo.
pause
