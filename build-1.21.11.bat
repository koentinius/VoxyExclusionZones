@echo off
setlocal
cd /d "%~dp0"
set "GRADLE_VERSION=9.2.1"
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

call "%GRADLE_EXE%" --no-daemon build ^
  -Ploom_version=1.14.8 ^
  -Pminecraft_version=1.21.11 ^
  -Pyarn_mappings=1.21.11+build.3 ^
  -Pfabric_version=0.141.6+1.21.11 ^
  -Pmod_version=0.2.0 ^
  -Parchives_base_name=voxy-exclusion-zones-mc1.21.11 ^
  -Pvoxy_version=k5PZxsfL ^
  -Pvoxy_mod_version=0.2.13-alpha ^
  -Psodium_version=UddlN6L4
if errorlevel 1 exit /b 1

echo.
echo Build task completed for Minecraft 1.21.11. Check build\libs for the remapped JAR.
echo Runtime shader and Mixin compatibility with Voxy 0.2.13-alpha still requires in-game verification.
echo.
pause
