@echo off
title LabAccess - tat
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\labaccess.ps1" -Action stop
echo.
pause
