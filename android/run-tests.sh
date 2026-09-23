#!/usr/bin/env bash
# macOS / Linux 용. Windows 는 run-tests.bat 를 더블클릭하면 된다.
set -uo pipefail
cd "$(dirname "$0")"

echo "================================================"
echo " Reader - core tests"
echo "================================================"
echo

# 이 프로젝트는 JDK 17~21 이 필요하다. Gradle 8.14 는 Java 25 를 알지 못해
# 버전 문자열만 찍고 죽는데(예: "What went wrong: 25.0.4.1") 원인을 짐작하기 어렵다.
java_exe="${JAVA_HOME:+$JAVA_HOME/bin/java}"
java_exe="${java_exe:-$(command -v java || true)}"

if [ -z "$java_exe" ] || [ ! -x "$java_exe" ]; then
  echo "[FAIL] Java 를 찾지 못했습니다. JDK 21 을 설치하세요: https://adoptium.net"
  exit 1
fi

# 'version' 이 든 줄만 본다. JAVA_TOOL_OPTIONS 가 설정돼 있으면 JVM 이
# "Picked up JAVA_TOOL_OPTIONS: ..." 를 먼저 찍으므로 첫 줄을 잡으면 틀린다.
version="$("$java_exe" -version 2>&1 | grep -i ' version ' | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
major="$(echo "$version" | cut -d. -f1)"
[ "$major" = "1" ] && major="$(echo "$version" | cut -d. -f2)"

if [ "$major" -lt 17 ] || [ "$major" -gt 21 ]; then
  echo "[FAIL] Java $version 은 쓸 수 없습니다. 이 프로젝트는 JDK 17~21 이 필요합니다."
  echo "       JDK 21 설치: https://adoptium.net"
  echo "       설치 후: export JAVA_HOME=<JDK 21 경로>"
  exit 1
fi

echo " Java : $version"

./gradlew :document:check :core-layout:check
status=$?

echo
if [ $status -eq 0 ]; then
  echo "[OK] 성공! 모든 테스트가 통과했습니다."
  echo "  보고서: document/build/reports/tests/test/index.html"
else
  echo "[FAIL] 실패했습니다. 위 메시지를 그대로 알려주세요."
fi
exit $status
