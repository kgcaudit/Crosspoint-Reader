# iOS 세션 시작 지시문

맥북(Claude Code)에서 iOS 세션을 처음 열 때 아래 **"보낼 글"** 을 그대로 붙여 넣는다. 그 뒤로는 세션을 이어받을 때마다
"`docs/PLATFORM_PARITY.md` 의 변경 기록부터 처리해 줘" 한 줄이면 된다.

## 맥에 미리 깔 것

1. Xcode(앱 스토어) — 한 번 열어 추가 구성 요소 설치까지 마친다.
2. JDK 21 — `brew install --cask temurin@21` (Kotlin/Native 빌드에 Gradle 이 쓴다).
3. Claude Code — 터미널에서 `claude`.
4. 저장소: `git clone https://github.com/kgcaudit/Crosspoint-Reader.git` 후 그 폴더에서 `claude` 를 연다.

## 보낼 글

```
OLO eBook 의 iOS 앱을 만든다. 저장소 루트의 CLAUDE.md 와 docs/PLATFORM_PARITY.md, docs/IOS_KICKOFF.md 를 먼저 읽어.

- 안드로이드가 중심이다. 안드로이드 세션이 브랜치 claude/android-epub-viewer-text-measurer-oqy6rq 에서 기능을 만든다.
  iOS 는 브랜치 claude/ios-viewer 에서 한다(없으면 안드로이드 브랜치에서 만든다). 일을 시작할 때마다 안드로이드
  브랜치를 합친다(merge, rebase 아님).
- android/document · android/core-layout 은 Kotlin Multiplatform 공통 코드다. iOS 는 이것을 프레임워크로 받아 쓴다.
  공통 코드의 동작을 iOS 쪽에서 고치지 않는다 — 고칠 것이 있으면 안드로이드 세션에 넘길 일로 적어 둔다.
- 화면은 SwiftUI. 디자인은 docs/design-handoff/ 의 색 · 글자 · 치수 · 규칙을 그대로 따른다.
- CLAUDE.md 의 작업 순서(구상안 이미지 → 결정 → 확정 → 코딩)와 우리말 규칙을 지킨다. iOS 구상안은 SwiftUI
  미리보기(시뮬레이터 스크린샷)로 그린다.

첫 일(순서대로, 단계마다 커밋):
1. 빌드 환경 확인: android 폴더에서 ./gradlew :document:check :core-layout:check 가 맥에서도 통과하는지.
2. iosMain 채우기: docs/PLATFORM_PARITY.md §1 의 "iOS 가 채울 것" 표. android/document/src/iosMain 과
   android/core-layout/src/iosMain 에 actual 을 쓰고, 공통 시험(jvmTest 에 있는 것 중 플랫폼과 무관한 것)을 iOS 에서도
   돌 수 있게 commonTest 로 옮길 수 있는지 검토해 제안한다(옮기는 것은 안드로이드 세션과 겹치므로 먼저 묻는다).
3. 프레임워크: 공통 두 모듈을 묶어 내보내는 Gradle 모듈(예: android/shared-ios, 이름 OloCore)을 만들어
   ./gradlew :shared-ios:assembleXCFramework 로 XCFramework 를 낸다.
4. ios/ 폴더에 Xcode 프로젝트(SwiftUI, 최소 iOS 17)를 만들고 OloCore 를 붙여, 번들에 넣은 시험용 TXT 한 권을
   CoreText 로 재는 TextMeasurer 로 조판해 첫 쪽을 그린다. TextMeasurer 는 MeasurerConformance 를 통과해야 한다.
5. 여기까지 되면 서재 · 리더 화면 구상안을 만들어 보이고 결정을 받는다.

끝날 때마다 docs/PLATFORM_PARITY.md 의 표와 변경 기록을 고친다.
```

## 왜 이렇게 나눴나

- **브랜치를 나눈다**: 안드로이드 세션은 자기 브랜치에만 올린다. 한 브랜치를 두 세션이 함께 쓰면 서로의 미완성 코드를
  받아 빌드가 깨진다.
- **iOS 는 merge 로 따라간다**: 안드로이드 브랜치를 rebase 하지 않는다 — 안드로이드 세션이 같은 브랜치에 계속 올리므로,
  rebase 한 iOS 브랜치는 다음 merge 때 같은 커밋을 두 번 갖게 된다.
- **공통 코드는 안드로이드 세션이 고친다**: 두 세션이 같은 파일을 따로 고치면 합칠 때 부딪친다. iOS 에서 공통 코드의 버그를
  찾으면 변경 기록에 "안드로이드에 요청" 으로 적고, 안드로이드 세션이 고쳐 시험과 함께 넣는다.
