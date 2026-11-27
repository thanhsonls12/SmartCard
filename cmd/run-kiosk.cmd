@echo off
title LabAccess - Kiosk
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\scripts\labaccess.ps1" -Action reader-kiosk
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
