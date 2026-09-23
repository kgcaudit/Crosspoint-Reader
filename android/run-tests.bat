@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul
cd /d "%~dp0"

echo ================================================
echo  Reader - core tests
echo ================================================
echo.

rem 사내망이 HTTPS를 가로채는 경우(TLS 검사 프록시) Java가 인증서를 거부해
rem "PKIX path building failed" 로 다운로드가 실패한다. 회사가 배포한 루트 인증서는
rem 이미 Windows 인증서 저장소에 들어 있으므로(그래서 브라우저는 된다), Java도
rem 그걸 쓰게 하면 해결된다. 프록시가 없어도 Windows 저장소에는 표준 공인 인증서가
rem 들어 있어 그대로 동작한다.
rem
rem JAVA_TOOL_OPTIONS 인 이유: Gradle 배포본을 내려받는 래퍼 JVM 과 그 뒤에
rem 라이브러리를 내려받는 데몬 JVM 이 서로 다른 프로세스인데, 이 변수는 둘 다에 붙는다.
set "JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT %JAVA_TOOL_OPTIONS%"

rem ── 쓸 수 있는 JDK 찾기 ─────────────────────────────────────────
rem
rem 이 프로젝트는 JDK 17~21 이 필요하다. Gradle 8.14 는 Java 25 를 알지 못해
rem "What went wrong: 25.0.4.1" 처럼 버전 문자열만 찍고 죽는데, 원인을 짐작하기
rem 어려운 메시지다. 그래서 여기서 먼저 확인하고 안내한다.
rem
rem Android Studio 가 함께 설치하는 JDK(jbr)를 먼저 본다 — Android 개발에서 쓰는
rem 바로 그 버전이고, 시스템에 최신 Java 가 따로 깔려 있어도 영향을 받지 않는다.

set "PICKED="

if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" (
  call :checkJava "%LOCALAPPDATA%\Programs\Android Studio\jbr" "Android Studio"
)
if not defined PICKED if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
  call :checkJava "%ProgramFiles%\Android\Android Studio\jbr" "Android Studio"
)
if not defined PICKED if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  call :checkJava "%JAVA_HOME%" "JAVA_HOME"
)
if not defined PICKED (
  for /f "delims=" %%p in ('where java 2^>nul') do (
    if not defined PICKED call :checkJavaExe "%%p" "PATH"
  )
)

if not defined PICKED (
  echo.
  echo  [FAIL] 이 프로젝트에 쓸 수 있는 JDK(17~21)를 찾지 못했습니다.
  echo.
  echo   둘 중 하나를 설치하면 됩니다:
  echo     Android Studio : https://developer.android.com/studio
  echo     JDK 21         : https://adoptium.net  (Temurin 21 LTS, Windows x64 .msi)
  echo.
  echo   이미 설치했다면 이 창을 닫고 새로 연 뒤 다시 실행해 주세요.
  echo.
  pause
  exit /b 1
)

set "JAVA_HOME=%PICKED%"
echo  Java : %JAVA_HOME%  (버전 %PICKED_MAJOR%, %PICKED_FROM%)
echo.
echo  테스트를 실행합니다. 처음 실행은 몇 분 걸립니다(다운로드).
echo.

call gradlew.bat :document:check :core-layout:check

echo.
if errorlevel 1 (
  echo ================================================
  echo  [FAIL] 실패했습니다.
  echo.
  echo  위에 나온 메시지를 그대로 복사해서 알려주세요.
  echo.
  echo  "PKIX path building failed" 가 보인다면 사내 프록시 문제입니다.
  echo  docs\LOCAL_SETUP.md 의 6장을 보세요.
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
exit /b 0

rem ── 보조 루틴 ───────────────────────────────────────────────────

:checkJava
rem %1 = JDK 폴더, %2 = 출처 이름
call :checkJavaExe "%~1\bin\java.exe" %2
exit /b 0

:checkJavaExe
rem %1 = java.exe 경로, %2 = 출처 이름
set "_v="
for /f "tokens=3" %%v in ('"%~1" -version 2^>^&1 ^| findstr /i "version"') do (
  if not defined _v set "_v=%%~v"
)
if not defined _v exit /b 0

rem "1.8.0_x" 는 major 가 두 번째 조각, "21.0.5" 는 첫 조각.
for /f "tokens=1,2 delims=." %%a in ("!_v!") do (
  if "%%a"=="1" (set "_major=%%b") else (set "_major=%%a")
)

if !_major! LSS 17 (
  echo  건너뜀: %~2 의 Java !_v! ^(17 미만^)
  exit /b 0
)
if !_major! GTR 21 (
  echo  건너뜀: %~2 의 Java !_v! ^(Gradle 8.14 가 지원하지 않음^)
  exit /b 0
)

for %%h in ("%~1\..\..") do set "PICKED=%%~fh"
set "PICKED_MAJOR=!_major!"
set "PICKED_FROM=%~2"
exit /b 0
