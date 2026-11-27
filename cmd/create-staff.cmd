@echo off
title LabAccess - Create Staff
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\scripts\provision-staff.ps1"
set "RESULT=%ERRORLEVEL%"
pause
exit /b %RESULT%
