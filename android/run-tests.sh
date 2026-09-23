#!/usr/bin/env bash
# macOS / Linux 용. Windows 는 run-tests.bat 를 더블클릭하면 된다.
set -uo pipefail
cd "$(dirname "$0")"

echo "================================================"
echo " Reader - core tests"
echo "================================================"
echo

if ! command -v java > /dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  echo "[FAIL] Java 를 찾지 못했습니다. JDK 21 을 설치하세요: https://adoptium.net"
  exit 1
fi

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
