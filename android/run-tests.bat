@echo off
REM Thin launcher. All logic lives in run-tests.ps1.
REM
REM cmd.exe reads a batch file byte by byte while executing it, using the
REM console codepage. A UTF-8 batch file with Korean comments plus "chcp"
REM desynchronises that reader, and cmd starts executing comment text as
REM commands. Paths containing spaces are also painful to quote correctly
REM inside for /f blocks. PowerShell has neither problem, so this file stays
REM ASCII-only and does nothing but launch the script.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-tests.ps1"
echo.
pause
