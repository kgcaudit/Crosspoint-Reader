# 안드로이드 ↔ iOS 대조표

마지막 갱신: 2026-10-18 · 기준 안드로이드 판 **0.54.7**

**안드로이드가 중심이다**(2026-10-18 사용자 결정). 기능은 안드로이드에서 먼저 정하고 만든다. iOS 는 이 표를 보고 따라간다.
두 세션은 기억을 나누지 않으므로, 이 문서와 저장소가 유일한 연결 고리다.

- **안드로이드 세션**: 기능을 넣거나 버그를 고칠 때마다 맨 아래 **변경 기록**에 한 줄을 더한다(커밋과 같은 커밋으로).
  공통 코어(`:document` · `:core-layout`)만 바꾼 것도 적는다 — iOS 는 코드를 저절로 받지만 화면에서 확인할 것이 생긴다.
- **iOS 세션**: 시작할 때 안드로이드 브랜치를 합치고(`git merge origin/claude/android-epub-viewer-text-measurer-oqy6rq`),
  변경 기록에서 "iOS: 대기" 인 줄을 처리한 뒤 "iOS: 반영(커밋)" · "iOS: 해당 없음(까닭)" 으로 바꾼다.

---

## 1. 함께 쓰는 코드 (Kotlin Multiplatform)

`android/document` · `android/core-layout` 은 `commonMain` 에 있다. 안드로이드는 JVM 으로, iOS 는 Kotlin/Native 로 같은 코드를
컴파일한다. 여기서 고친 것은 두 앱에 함께 들어간다.

| 영역 | 공통 코드 | 안드로이드만 · iOS 만 |
|---|---|---|
| 파일 읽기 | ZIP(ZIP64) · OPF · NCX/nav 목차 · XML · 인코딩 감지(UTF-8/16 · CP949) · TXT · TAR · 그림 머리 | — |
| PDF | 구조(목차 · 쪽 수 · 제목) · 암호 풀기(RC4 · AES) · 스트림 풀기 | 쪽 그리기: 안드로이드 `PdfRenderer` · iOS **PDFKit** |
| 만화 | 이름 읽기 · 작품 묶기 · ComicInfo · 펼침면 나누기 판정 · 여백 자르기 판정 · 웹툰 판별 · 진도 모델 | 그림 해독 · CBR/CB7 해제(안드로이드는 네이티브 7z · UnRAR) |
| 조판 | CSS 서브셋 · 장 해석 · 줄 나누기 · 쪽 나누기 · 쪽 캐시 포맷 · 링크 · 각주 · 찾기 · 문장 나누기 · 책갈피 · 진도 | 글자 폭 재기(`TextMeasurer`), 그리기 |

### iOS 가 채울 것 (`iosMain`)

공통 코드에 선언만 있고(`expect`), 안드로이드는 `jvmMain` 이 채운다. iOS 는 맥에서 같은 이름으로 채운다.

| 선언 | 파일 | 안드로이드(jvmMain) | iOS 에서 쓸 것 |
|---|---|---|---|
| `InputStream` · `Reader` · `Closeable` · `IOException` · `EOFException` | `document/.../io/Streams.kt` | `java.io` 그 자체 | 같은 모양의 추상 클래스 · 인터페이스 |
| `Inflater` · `DataFormatException` | `io/Platform.kt` | `java.util.zip` | `platform.zlib`(raw deflate 는 windowBits −15) |
| `NamedCharsets` | `io/Platform.kt` | `java.nio.charset` | `NSString` + `CFStringConvertIANACharSetNameToEncoding`(CP949 = `kCFStringEncodingDOSKorean`) |
| `Crypto` (MD5 · SHA-1/256/384/512 · AES-CBC) | `io/Platform.kt` | `MessageDigest` · `Cipher` | CommonCrypto(`CC_MD5` · `CC_SHA*` · `CCCrypt`) |
| `Unicode` (NFC · NFKC) | `io/Platform.kt` | `java.text.Normalizer` | `precomposedStringWithCanonicalMapping` · `…CompatibilityMapping` |
| `ControlFlowException` | `io/Platform.kt` | 스택 기록 없는 `RuntimeException` | `RuntimeException` |
| `currentTimeMillis()` | `io/Platform.kt` | `System.currentTimeMillis` | `NSDate().timeIntervalSince1970 * 1000` |
| `CacheDir` (인터페이스) | `core-layout/.../cache/CacheDir.kt` | `FileCacheDir` | `NSFileManager`(바꿔 넣기는 `replaceItemAt`) |
| `TextMeasurer` (인터페이스) | `core-layout/.../TextMeasurer.kt` | `:text-platform` 의 `AndroidTextMeasurer` | CoreText. **`MeasurerConformance` 를 통과해야 한다** |

공통 코드가 iOS 에서도 컴파일되는지는 리눅스에서도 검사된다: `./gradlew :document:check :core-layout:check`
(`compileCommonMainKotlinMetadata` 포함). `commonMain` 에 `java.*` 를 쓰면 여기서 깨진다.

공통 바이트 ↔ 글자 변환(`io/Text.kt` · `io/Buffers.kt`)은 JVM 결과와 똑같이 읽도록 `CommonIoTest` 가 대조한다.

---

## 2. 기능 대조표

상태: **안** = 안드로이드 · **i** = iOS. ✅ 됨 · ◐ 일부 · ⬜ 아직 · ✕ 만들지 않음(까닭 적음).

### 서재

| 기능 | 안 | i | iOS 메모 |
|---|---|---|---|
| 폴더 등록 · 하위 폴더 훑기 | ✅ | ⬜ | SAF 대신 `UIDocumentPickerViewController`(폴더) + 보안 범위 북마크 |
| 연결 프로그램으로 열기(다른 앱의 파일) | ✅ | ⬜ | `CFBundleDocumentTypes` · "다음으로 열기" · 파일 앱 |
| 책 · 만화 탭, 만화 세 갈래(만화 · 웹툰 · 그림책) | ✅ | ⬜ | |
| 격자 · 목록 · 원목 책장 보기 | ✅ | ⬜ | |
| 읽는 중 · 읽은 책 · 읽을 책, 책 찾기 | ✅ | ⬜ | |
| 작품 묶기 · 작품 정리 · 압축 속 압축을 권으로 | ✅ | ⬜ | |
| 표지 고르기(반쪽 · 왼/오/통째로) | ✅ | ⬜ | |
| 옮긴 파일의 기록 잇기 | ✅ | ⬜ | |
| 기록 백업 · 가져오기 | ✅ | ⬜ | 백업 파일 형식은 두 앱이 같아야 한다(서로 옮길 수 있게) |
| 이어 읽기 위젯 | ✅ | ⬜ | WidgetKit |
| 기기 간 이어 읽기 | ✅ | ⬜ | |

### 책 (EPUB · TXT)

| 기능 | 안 | i | iOS 메모 |
|---|---|---|---|
| 쪽 넘김 · 목차 · 진도 · 남은 시간 | ✅ | ⬜ | 조판은 공통. 그리기만 새로 |
| 글꼴(휴대폰 · 출판사 · 넣은 글꼴) · 크기 · 줄 간격 · 여백 · 정렬 · 들여쓰기 | ✅ | ⬜ | 넣은 글꼴: `CTFontManagerRegisterFontURLs` |
| 배경(흰 · 아이보리 · 세피아 · 회색 · 검정) · 그림 흰 바탕을 지면색으로 | ✅ | ⬜ | |
| 두 쪽 보기(가로 · 넓은 화면) | ✅ | ⬜ | iPad |
| 찾기 · 각주 판 · 링크 | ✅ | ⬜ | |
| 독서노트(책갈피 · 형광펜 · 메모) · 내보내기 | ✅ | ⬜ | |
| 넘김 효과(말림 · 덮기) · 넘김 소리 · 진동 | ✅ | ⬜ | 소리 규칙 `design-handoff` §6-7 |
| 듣기(TTS) · 화면 끄고 듣기 · 어절 쉼 | ✅ | ⬜ | `AVSpeechSynthesizer` · 백그라운드 오디오 · `MPNowPlayingInfoCenter` |
| 자동 넘김 | ✅ | ⬜ | |
| 음량 키로 넘기기 | ✅ | ✕ | iOS 는 음량 키를 앱이 가로챌 수 없다(우회는 심사 거절) |

### PDF

| 기능 | 안 | i | iOS 메모 |
|---|---|---|---|
| 쪽 보기 · 확대 · 폭 맞춤 · 목차 · 암호 PDF | ✅ | ⬜ | PDFKit 으로 그린다. 구조 읽기는 공통 |
| 찾기 · 형광펜 · 듣기 | ✅ | ⬜ | PDFKit 의 글자 층 |
| 그림 위주 PDF 를 만화로 보기 | ✅ | ⬜ | |

### 만화 · 웹툰

| 기능 | 안 | i | iOS 메모 |
|---|---|---|---|
| CBZ · 폴더 · CBR · CB7 · CBT | ✅ | ⬜ | CBR/CB7 은 안드로이드의 네이티브 해제기를 iOS 용으로 다시 빌드하거나 다른 해제기 |
| 쪽 넘김 · 오→왼 · 두 쪽 · 붙여 넘기기 · 꽉 채우기 | ✅ | ⬜ | |
| 펼침면 나누기 · 반쪽 이어 보기 · 여백 자르기 · 색 보정 | ✅ | ⬜ | 판정은 공통, 그림 자르기는 `CGImage` |
| 웹툰 세로 스크롤 · 다음 화 이어 붙이기 · 그림 폭 · 자동 스크롤 | ✅ | ⬜ | |

### 휴대폰과 맞물리는 것

| 기능 | 안 | i | iOS 메모 |
|---|---|---|---|
| 밝기(책을 열면 휴대폰 밝기, 바꾼 밝기는 그 책에서만) | ✅ | ⬜ | `UIScreen.brightness` 는 **휴대폰 전체**를 바꾼다 — 책을 닫거나 앱을 나갈 때 반드시 되돌린다 |
| 읽기 잠금(다른 앱으로 못 나감 · 전화 오면 풀림) | ✅ | ✕ | 앱이 화면 고정을 걸 수 없다. 사용자가 켜는 "사용법 유도"(Guided Access) 안내로 대신할지 구상안에서 정한다 |
| 화면 켜짐 유지 | ✅ | ⬜ | `isIdleTimerDisabled` |
| 화면 회전 고정 | ✅ | ⬜ | |
| 폴더블 · 태블릿 반응형(좁음 · 중간 · 넓음) | ✅ | ⬜ | `RESPONSIVE_PLAN.md` 의 표를 iPhone · iPad 에 그대로 |
| 독서 기록 · 통계 | ✅ | ⬜ | |

---

## 3. 변경 기록 (안드로이드 → iOS)

새 줄은 **위에** 더한다. 형식: `판 · 날짜 · 무엇(사용자가 느끼는 것) · 공통/화면 · iOS: 대기|반영(커밋)|해당 없음(까닭)`

iOS 세션이 공통 코드의 문제를 찾으면 판 칸에 `iOS→안` 이라고 적은 줄을 더한다. 안드로이드 세션이 고친 뒤 판을 적는다.

| 판 | 날짜 | 무엇 | 범위 | iOS |
|---|---|---|---|---|
| 0.54.9 | 2026-10-20 | 터치 영역의 설명 · 그림을 읽기 방향대로 — 오→왼 만화에서 열면 "이 작품은 오→왼으로 읽습니다 — 좌우가 바뀝니다" 와 뒤집힌 설명, 한 손 설명에 미는 방향(`TouchZones.description(rightToLeft)`) | 화면 | 대기 — iOS 터치 영역 화면도 같은 글 · 그림 규칙 |
| 0.54.8 | 2026-10-20 | 오→왼 만화에서 한 손(양쪽 다음)으로 눌러도 앞 쪽으로 가던 것을 고침 — 한 손은 방향과 상관없이 늘 다음, 기본 · 좌우 바꾸기만 오→왼에서 자리를 뒤집는다(`TouchZones.forward`) | 화면 | 대기 — iOS 만화 리더를 만들 때 같은 규칙 |
| 0.54.7 | 2026-10-18 | 공통 코어를 Kotlin Multiplatform 으로(동작 같음). iOS 시작점 | 공통 | 대기 — `iosMain` 채우기부터(§1) |
