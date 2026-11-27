@echo off
title LabAccess - Virtual Card Reader
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\labaccess.ps1" -Action reader
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
