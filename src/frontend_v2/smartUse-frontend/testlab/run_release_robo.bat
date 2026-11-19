@echo off
rem Release-build crash check: a Robo test of the shipped release APK (R8 on) on API 35 and 36.
rem Robo starts the app and taps around; any crash shows in the results with its logcat.
rem Uses two device runs of the daily Test Lab quota. --skip-build reuses the last release APK.
setlocal
set "REPO=%~dp0.."
set "APK=%REPO%\app\build\outputs\apk\release\app-release.apk"

if /I not "%~1"=="--skip-build" (
    call "%REPO%\gradlew.bat" -p "%REPO%" assembleRelease || exit /b 1
)
if not exist "%APK%" (
    echo Missing %APK%; build first.
    exit /b 1
)

gcloud firebase test android run ^
    --type robo ^
    --app "%APK%" ^
    --timeout 5m ^
    --device model=MediumPhone.arm,version=35 ^
    --device model=MediumPhone.arm,version=36
