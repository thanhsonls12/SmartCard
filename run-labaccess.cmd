@echo off
title LabAccess - Virtual Reader + Staff + Kiosk
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\labaccess.ps1" -Action reader-all
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
