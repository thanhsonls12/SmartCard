@echo off
title LabAccess - Staff Console
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\labaccess.ps1" -Action reader-staff
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
