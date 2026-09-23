@echo off
setlocal
chcp 65001 > nul
cd /d "%~dp0"

echo ================================================
echo  Reader - core tests
echo ================================================
echo.

rem 사내망이 HTTPS를 가로채는 경우(TLS 검사 프록시) Java가 인증서를 거부해
rem "PKIX path building failed" 로 다운로드가 실패한다. 회사가 배포한 루트 인증서는
rem 이미 Windows 인증서 저장소에 들어 있으므로(그래서 브라우저는 된다), Java도
rem 그걸 쓰게 하면 해결된다. 프록시가 없는 환경에서도 Windows 저장소에는 표준
rem 공인 인증서가 들어 있어 그대로 동작한다.
rem
rem JAVA_TOOL_OPTIONS 를 쓰는 이유: Gradle 배포본을 내려받는 래퍼 JVM 과 그 뒤에
rem 라이브러리를 내려받는 데몬 JVM 이 서로 다른 프로세스인데, 이 환경변수는 둘 다에
rem 적용된다.
set "JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT %JAVA_TOOL_OPTIONS%"

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
  echo.
  echo  "PKIX path building failed" 가 보이고 이 창에서도 계속 실패한다면
  echo  사내 프록시 설정이 필요합니다. docs\LOCAL_SETUP.md 의 6장을 보세요.
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
