@echo off
echo ========================================================
echo Installing KApp Karaoke to connected Android device...
echo ========================================================

set ADB="C:\android_tool\platform-tools\adb.exe"
if not exist %ADB% set ADB=adb

%ADB% devices
echo.
set APK_FILE=app\build\outputs\apk\debug\app-debug.apk
for /f "delims=" %%F in ('dir /b /o:-d "app\build\outputs\apk\debug\app-debug*.apk" 2^>nul') do (
    set APK_FILE=app\build\outputs\apk\debug\%%F
    goto :found_apk
)
:found_apk
echo Installing: %APK_FILE%
%ADB% install -r "%APK_FILE%"

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================================
    echo SUCCESS! KApp Karaoke has been installed on your phone.
    echo Launching app...
    echo ========================================================
    %ADB% shell am start -n com.kapp.karaoke/.MainActivity
) else (
    echo.
    echo [!] Install failed. Please ensure:
    echo     1. Your phone is connected via USB.
    echo     2. USB Debugging is turned ON in Developer Options.
    echo     3. You accepted the "Allow USB Debugging" prompt on your phone screen.
)

pause
