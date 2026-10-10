@echo off
setlocal enabledelayedexpansion
title KApp - Create Portable Zip Package
cd /d "%~dp0"

echo ===================================================
echo        KApp - Generate Portable ZIP Package
echo ===================================================
echo.

if not exist "dist\karaoke_app.dist" (
    echo [ERROR] dist\karaoke_app.dist not found!
    echo Please run build.bat or build_nocmd.bat first.
    echo.
    pause
    exit /b 1
)

if not exist "installer" mkdir "installer"

set "PY_CMD=python"
if exist "venv\Scripts\python.exe" set "PY_CMD=venv\Scripts\python.exe"

for /f "delims=" %%v in ('%PY_CMD% -c "from settings_manager import get_app_version; print(get_app_version())"') do set "VER=%%v"
if "!VER!"=="" set "VER=latest"

set "ZIP_OUT=installer\Karaoke_App_Portable_!VER!.zip"
echo Target output: %ZIP_OUT%
echo.
echo Compressing dist\karaoke_app.dist into portable zip...
echo This may take a moment...

%PY_CMD% -c "import zipfile, os; out = r'%ZIP_OUT%'; src = r'dist\karaoke_app.dist'; (os.remove(out) if os.path.exists(out) else None); z = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6); [z.write(os.path.join(root, file), os.path.relpath(os.path.join(root, file), os.path.dirname(src))) for root, _, files in os.walk(src) for file in files]; z.close(); print('[SUCCESS] Created:', out)"

if %ERRORLEVEL% equ 0 (
    echo.
    echo ===================================================
    echo [SUCCESS] Portable ZIP package created successfully!
    echo Location: %ZIP_OUT%
    echo ===================================================
) else (
    echo.
    echo [ERROR] Failed to create portable zip package.
)

echo.
pause
