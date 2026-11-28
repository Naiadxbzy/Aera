@echo off
setlocal
title Aera Web

set "AERA_PORTABLE_NODE=%~dp0runtime\node.exe"
set "AERA_PORTABLE_LAUNCHER=%~dp0app\launch.mjs"
if not exist "%AERA_PORTABLE_NODE%" goto missing
if not exist "%AERA_PORTABLE_LAUNCHER%" goto missing

"%AERA_PORTABLE_NODE%" "%AERA_PORTABLE_LAUNCHER%"
if errorlevel 1 (
    echo.
    pause
    exit /b 1
)
exit /b 0

:missing
echo Aera Web files are missing. Keep %~nx0, app, and runtime together.
pause
exit /b 1
