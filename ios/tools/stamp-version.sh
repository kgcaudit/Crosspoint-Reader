#!/bin/sh
# 앱 판을 안드로이드와 맞춘다(2026-10-25 사용자 결정): 빌드할 때마다 android/app/build.gradle.kts 의 versionName · versionCode 를
# 지금 대상의 Info.plist 에 적는다. 판의 원본은 그 한 곳이다 — Xcode 설정(MARKETING_VERSION)에 손으로 옮겨 적으면 어긋나, 기기의
# 앱 정보가 늘 "판 0.1.0 (1)" 이었다. 앱 · 위젯 대상이 각자 서명 전에 부른다(서명한 뒤 고치면 위젯 서명이 깨진다).
set -e
gradle="$SRCROOT/../android/app/build.gradle.kts"
name=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$gradle" | head -1)
code=$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$gradle" | head -1)
if [ -z "$name" ] || [ -z "$code" ]; then echo "error: could not read versionName/versionCode from $gradle"; exit 1; fi
plist="$TARGET_BUILD_DIR/$INFOPLIST_PATH"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $name" "$plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $code" "$plist"
echo "stamped $name ($code) into $INFOPLIST_PATH"
