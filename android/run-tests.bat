@echo off
setlocal
chcp 65001 > nul
cd /d "%~dp0"

echo ================================================
echo  Reader - core tests
echo ================================================
echo.

rem JAVA_HOME이 없으면 Android Studio가 함께 설치한 JDK를 찾아 쓴다.
rem 이게 없으면 Android Studio만 설치한 사람은 "JAVA_HOME is not set"에서 막힌다.
if not defined JAVA_HOME if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"

if defined JAVA_HOME (
  echo Java: %JAVA_HOME%
) else (
  where java >nul 2>nul
  if errorlevel 1 (
    echo [FAIL] Java를 찾지 못했습니다.
    echo.
    echo   Android Studio 또는 JDK 21을 설치한 뒤 다시 실행하세요.
    echo     Android Studio : https://developer.android.com/studio
    echo     JDK 21         : https://adoptium.net
    echo.
    pause
    exit /b 1
  )
  echo Java: PATH에서 찾음
)

echo.
echo 테스트를 실행합니다. 처음 실행은 몇 분 걸립니다(다운로드).
echo.

call gradlew.bat :document:check :core-layout:check

echo.
if errorlevel 1 (
  echo ================================================
  echo  [FAIL] 실패했습니다.
  echo.
  echo  위에 나온 메시지를 그대로 복사해서 알려주세요.
  echo ================================================
) else (
  echo ================================================
  echo  [OK] 성공! 모든 테스트가 통과했습니다.
  echo.
  echo  자세한 보고서:
  echo    document\build\reports\tests\test\index.html
  echo    core-layout\build\reports\tests\test\index.html
  echo ================================================
)
echo.
pause
