@echo off
REM Thin launcher. All logic lives in make-release-key.ps1 (kept ASCII-only, see run-tests.bat).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0make-release-key.ps1"
echo.
pause
