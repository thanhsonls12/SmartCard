@echo off
title LabAccess - cai applet
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\labaccess.ps1" -Action install
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
