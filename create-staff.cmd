@echo off
title LabAccess - Create Staff
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\provision-staff.ps1"
set "RESULT=%ERRORLEVEL%"
pause
exit /b %RESULT%
