@echo off
setlocal
chcp 65001 >nul
title Rezhim UPE - Server
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
if errorlevel 1 (
    echo.
    echo Startup failed. See the message above.
    pause
)
endlocal
