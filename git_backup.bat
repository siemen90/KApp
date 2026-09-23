@echo off
setlocal enabledelayedexpansion
title KApp Git Backup

:: Ensure Git is in PATH
where git >nul 2>nul
if %ERRORLEVEL% neq 0 (
    set "PATH=C:\Users\Chan\AppData\Local\GitHubDesktop\app-3.4.3\resources\app\git\cmd;C:\Users\Chan\AppData\Local\GitHubDesktop\app-3.4.3\resources\app\git\mingw64\bin;%PATH%"
)

:: Use rtk if available, otherwise native git
set "GIT_CMD=git"
where rtk >nul 2>nul
if %ERRORLEVEL% equ 0 (
    set "GIT_CMD=rtk git"
)

echo ===================================================
echo              KApp - Git Backup Tool
echo ===================================================
echo.

cd /d "%~dp0"

echo [1/3] Checking repository status...
call %GIT_CMD% status --short

:: Check if there are any changes (staged, unstaged, or untracked)
for /f %%i in ('git status --porcelain 2^>nul') do (
    set HAS_CHANGES=1
    goto :has_changes
)
set HAS_CHANGES=0

:has_changes
if "%HAS_CHANGES%"=="0" (
    echo.
    echo No local changes detected.
    goto :push_check
)

:: Get commit message
set "COMMIT_MSG=%*"
if "%COMMIT_MSG%"=="" (
    for /f "tokens=1-4 delims=/ " %%a in ('date /t') do set DATE_STR=%%a-%%b-%%c
    for /f "tokens=1-2 delims=: " %%a in ('time /t') do set TIME_STR=%%a:%%b
    set "DEFAULT_MSG=Backup !DATE_STR! !TIME_STR!"
    echo.
    set /p "COMMIT_MSG=Enter commit message [Default: !DEFAULT_MSG!]: "
    if "!COMMIT_MSG!"=="" set "COMMIT_MSG=!DEFAULT_MSG!"
)

echo.
echo [2/3] Staging and committing changes...
call %GIT_CMD% add .
call %GIT_CMD% commit -m "!COMMIT_MSG!"

:push_check
echo.
echo [3/3] Pushing to GitHub (origin main)...
call %GIT_CMD% push origin main

if %ERRORLEVEL% equ 0 (
    echo.
    echo ===================================================
    echo [SUCCESS] Backup completed and synced to GitHub!
    echo Remote: https://github.com/siemen90/KApp
    echo ===================================================
) else (
    echo.
    echo [WARNING] Push encountered an issue. Check network or credentials.
)

echo.
pause
