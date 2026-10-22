# 안드로이드 ↔ iOS 대조표

마지막 갱신: 2026-10-22 · 기준 안드로이드 판 **0.55.0**

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

| 선언 | 파일 | 안드로이드(jvmMain) | iOS | 상태 |
|---|---|---|---|---|
| `InputStream` · `Reader` · `Closeable` · `IOException` · `EOFException` | `document/.../io/Streams.kt` | `java.io` 그 자체 | `iosMain/.../Streams.ios.kt` — JDK 의 기본 구현을 그대로 옮김 | ✅ |
| `Inflater` · `DataFormatException` | `io/Platform.kt` | `java.util.zip` | `Platform.ios.kt` — `platform.zlib`, Z_PARTIAL_FLUSH, raw 는 windowBits −15. 입력은 네이티브 메모리에 복사 | ✅ |
| `NamedCharsets` | `io/Platform.kt` | `java.nio.charset` | `NamedCharsets.ios.kt` — UTF-8/16 은 공통 `Text`, 나머지(CP949 …)는 **iconv**. CoreFoundation 은 깨진 바이트가 하나라도 있으면 통째로 실패해서 대체 문자를 낼 수 없다 | ✅ |
| `Crypto` (MD5 · SHA-1/256/384/512 · AES-CBC) | `io/Platform.kt` | `MessageDigest` · `Cipher` | CommonCrypto(`CC_MD5` · `CC_SHA*` · `CCCrypt`) | ✅ |
| `Unicode` (NFC · NFKC) | `io/Platform.kt` | `java.text.Normalizer` | `precomposedStringWithCanonicalMapping` · `…CompatibilityMapping` | ✅ |
| `ControlFlowException` | `io/Platform.kt` | 스택 기록 없는 `RuntimeException` | `RuntimeException`(Kotlin/Native 는 스택 기록을 끌 수 없다) | ✅ |
| `currentTimeMillis()` | `io/Platform.kt` | `System.currentTimeMillis` | `NSDate().timeIntervalSince1970 * 1000` | ✅ |
| `CacheDir` (인터페이스) | `core-layout/.../cache/CacheDir.kt` | `FileCacheDir` | `NSFileCacheDir`(바꿔 넣기는 `writeToFile(atomically = true)`) · `PageStore(rootPath)` | ✅ |
| `TextMeasurer` (인터페이스) | `core-layout/.../TextMeasurer.kt` | `:text-platform` 의 `AndroidTextMeasurer` | `shared-ios` 의 `CoreTextMeasurer`(Kotlin 으로 쓴 CoreText — Swift 로 쓰면 호출마다 장 문자열이 NSString 으로 복사될 수 있다). `MeasurerConformance` 통과(16 · 42pt) | ✅ |

iOS 쪽 actual 은 `document/src/iosTest` · `core-layout/src/iosTest` 가 시뮬레이터에서 시험한다(맥에서 `check` 가 돌린다).
기대값은 JVM · 파이썬 · NIST 시험 벡터로 박아 두었다 — iOS 가 iOS 자신과 대조하면 같이 틀려도 모른다.

### iOS 프레임워크 OloCore (`android/shared-ios`, 맥에서만 구성)

공통 두 모듈을 그대로 내보내고(export) iOS 에만 있는 것(CoreText 측정기 · 쪽 그리기 · 파일 바이트)을 더한다. 정적 프레임워크.
`cd android && ./gradlew :shared-ios:assembleXCFramework` → `android/shared-ios/build/XCFrameworks/{debug,release}/OloCore.xcframework`.
`settings.gradle.kts` 는 맥에서만 이 모듈을 넣는다 — 안드로이드 세션(리눅스 · 윈도우)의 빌드에는 영향이 없다.

| iOS 전용(`shared-ios/src/iosMain`) | 하는 일 · 안드로이드와 맞춘 것 |
|---|---|
| `CoreTextMeasurer` | 시스템 글꼴(한국어 우선, 한글은 Apple SD Gothic Neo 로 이어짐). 줄 높이 1em · 위 높이 = "가漢Hbdhklt" 의 실제 윗선 · 기울임 합성 · 굵게는 굵은 시스템 글꼴(특성을 달면 Semibold 가 나와 안드로이드보다 옅다) |
| `PagePainter` | `Page` → `UIImage`. 위첨자 −0.35em · 아래첨자 +0.2em · 밑줄 +0.12em · 취소선 −0.3em · 가로줄 반투명(안드로이드 `PagePainter`) |
| `ReadingSpec` | 안드로이드 기본 읽기 설정(18 · 줄 간격 1.65 · 여백 36/34/56 + 안전 영역). Swift 는 Kotlin 기본 매개변수를 못 써서 `LayoutSpec` · `Paginator` 를 여기서 만든다 |
| `FileBytes` | 파일 경로 · `NSData` → `ByteSource` |
| `IosBookReader` 찾기 | `searchChapter`(공통 `BookLayout.searchChapter` + 목차 갈래 · 책 %) · `goToHit` · `clearMark`. 찾은 말 상자는 `PagePainter.rangeBoxes`(안드로이드 `rangeBoxes` 와 같은 셈, 조판에 쓴 측정기로) |
| `BookFaces` | 출판사 글꼴(안드로이드 `BookTypefaces` 짝): 책 글꼴을 캐시 폴더로 꺼내고, 보통 · 굵게를 안드로이드와 같은 규칙으로(굵은 파일 하나뿐인 가족은 다시 굵히지 않음), 지문을 `bookFontsKey` 로. 측정기는 서식의 `face` 번호로 고른다 |
| 형광펜(`IosBookReader` · `ReadingStore.annotations`) | 고르기는 `charAt` · `wordAt`(안드로이드 `PageHits` 와 같은 셈), 칠은 `highlight`(같은 구간이면 색 · 메모만 바꾼다) · `recolor` · `setNote` · `removeNote`. 쪽 그림에 칠 · 메모 쪽지, 고른 구간 · 손잡이 · 메뉴는 Swift 가 겹쳐 그린다(고르는 동안 쪽을 다시 그리지 않게) |
| `NotesExport` | 독서노트 내보내기 글(글 · 마크다운 · 감싸기 · 파일 이름) — 안드로이드 `exportNotes` · `exportMarkdown` 와 같은 글. 두 앱이 같은 파일을 내야 한다 |
| `TouchZones` | 터치 영역(구상안 37): 이름 · 설명(오→왼이면 뒤집음) · 누른 자리 → 읽는 순서로 몇 쪽(`step`). 안드로이드는 Compose 모듈(`ui-design`)에 있어 같은 규칙을 여기 옮겼다 |
| `IosComics` · `IosComicBook` | 만화 살피기(cbz · cbt · 그림만 든 zip — 파일 머리로 zip/tar), 그림 폴더, 작품 묶기(공통 `ComicShelf`), 쪽 바이트 · 크기(공통 `ImageHeader`) · 두 쪽 짝(공통 `ComicSpreads`). `ReadingStore` 의 `C`(권의 읽은 쪽 · 다 읽은 때는 한 번만) · `K`(쪽 책갈피) · `O`(작품 설정 RTL · COVER · SHELF) 줄 |
| `IosPdfBook` | PDF 의 기록 · 구조(공통 `PdfStructureReader`): 목차(쪽 수를 넘는 항목 뺌) · 쪽 이름표 · 자리(`p:page:0:y`, 짧아진 파일은 마지막 쪽) · 진도(보이는 마지막 쪽까지) · 책갈피(펼침의 두 쪽) · 이 장 남은 쪽. 쪽 그리기는 화면(PDFKit) |
| `RecordsBackup` · `RecordsCodec` | 읽기 기록 백업 파일 읽기 · 쓰기 · 미리 보기 · 합치기 · 기다리는 기록(안드로이드 `RecordsBackup` 과 같은 규칙). `ReadingStore.recordOf` · `merge` · `recordOfComic` · `mergeComic` · `workRecords` · `mergeWorks`(만화 · 작품 설정, `ComicRecordsBackupTest`). 책 id 는 한글을 모아 쓴 꼴(NFC) — 옛 판이 풀어 쓴 꼴로 적은 줄도 읽을 때 모은다 |
| `ReadingStore` 읽은 시간 · `IosBookReader.info` | `T` 줄(책 · 날 · 방식, 음수 버림 · 하루 상한, 모르는 방식은 남기고 셈에서만 뺀다) · `addTime` · `times`. `info(path, displayName)`: EPUB 의 제목 · 저자(없으면 파일 이름, 깨진 책도 파일 이름) |
| `IosBookReader` 두 쪽 · 하단 정보 | `layOut(twoPages, fullWidth, leftInset)` · `PagePainter.spread`. `ReaderFrame` 에 `hasRight` · `section` · `charsLeftChapter` · `charsLeftBook`(공통 `chapterLength` · `charsAfterChapter`, 장마다 한 번 셈) |
| `IosBookReader` 듣기 | `speechSentences`(공통 `splitSentences` + 문단 시작) · `spoken`(공통 `speakable` + `joinWords`) · `speak`(문장 칠 + 그 문장이 든 쪽으로, 이미 보이면 그대로) · `stopSpeaking`. 칠은 형광펜 **아래** 강조색 18%(검은 지면 30%). `ReaderFrame.pageStart` · `pageEnd` 로 "보이는 쪽에서 시작하는 첫 문장" 을 찾는다 |
| `TurnFx` | 넘김 소리의 자리(디자인 인계 §6-7): 쓸림 조건 · 방향 · 같은 세기 스테레오 · 모노 WAV → 쓸린 WAV. 안드로이드 `PageTurnFx` 와 같은 셈 |
| `UserFonts` | 넣은 글꼴: 글꼴 ID `user:<파일 이름>` ↔ 경로, 넣기 전 검사(머리 4바이트 · CoreText 로 읽어 보기 · 한글 유무). `CoreTextMeasurer.forSpec` 이 `fontId` 에서만 글꼴을 고른다 — 캐시 키와 잰 글꼴이 갈라지지 않게 |

### iOS 앱 (`ios/OloEBook.xcodeproj`, SwiftUI · 최소 iOS 17)

번들 식별자 `io.github.kgcaudit.oloebook`(안드로이드와 같다). 묶음 대상 `OloCore` 가 빌드마다 지금 칩 · 구성의 프레임워크 하나를
Gradle 로 링크하고, 앱은 검색 경로로 읽는다 — XCFramework 를 앱에 직접 붙이면 Xcode 가 Gradle 보다 먼저 옛 것을 복사해 가서
Kotlin 을 고친 뒤 두 번 빌드해야 반영됐다. 처음 한 번은 `android` 폴더에서 `./gradlew :shared-ios:linkDebugFrameworkIosSimulatorArm64`.
서재(구상안 3A · 9 · 10 · 11) · 리더(2A · 4A · 5A · 6A)까지. 디자인 시스템은 `ios/OloEBook/Design/` — `CpTheme`(색 · 글자 · 치수, 다크는 토큰에서) · `CpIcons`(안드로이드 경로 문자열을 그대로 읽는다, SF Symbols 를 쓰지 않는다) · `CpComponents`. 화면 확인용 시험 책은 `ios/TestBooks/`(`make_books.py` 가 EPUB 을 짓는다). 디버그 빌드에만 실행 인자 `-olo-open "<파일 이름>"` · `-olo-turn N` · `-olo-menu` 가 있다(시뮬레이터는 명령으로 누를 수 없다). 서재 보기는 설정 값 그대로 `-library.layout grid|list|shelf` 로 띄운다(실행 인자가 설정을 덮는다). 찍은 화면은 `docs/ios-screens/`.

공통 코드가 iOS 에서도 컴파일되는지는 리눅스에서도 검사된다: `./gradlew :document:check :core-layout:check`
(`compileCommonMainKotlinMetadata` 포함). `commonMain` 에 `java.*` 를 쓰면 여기서 깨진다.

공통 바이트 ↔ 글자 변환(`io/Text.kt` · `io/Buffers.kt`)은 JVM 결과와 똑같이 읽도록 `CommonIoTest` 가 대조한다.

### 맥 빌드 환경 (iOS 세션, 2026-10-19 확인)

macOS 26.6 (Apple Silicon) · Xcode 27.1 · iOS 시뮬레이터 27.0/27.1 · JDK Temurin 21 · Kotlin/Native 2.1.21.
`./gradlew :document:jvmTest :core-layout:jvmTest :document:compileCommonMainKotlinMetadata :core-layout:compileCommonMainKotlinMetadata`
통과(시험 315 + 332개, 실패 0).

---

## 2. 기능 대조표

상태: **안** = 안드로이드 · **i** = iOS. ✅ 됨 · ◐ 일부 · ⬜ 아직 · ✕ 만들지 않음(까닭 적음).

### 서재

| 기능 | 안 | i | iOS 메모 |
|---|---|---|---|
| 폴더 등록 · 하위 폴더 훑기 | ✅ | ◐ | 앱 폴더(파일 앱 › 나의 iPhone › OLO eBook) + 폴더 추가(보안 범위 북마크). 빈 서재는 3A(책을 들이는 세 길), 책 폴더 판은 안드로이드와 같은 가운데 판 · 말(15A: 폴더 추가 · 빼기 · 설명 문장). PDF 는 리더(PDFKit) 전이라 서재에 올리지 않는다 |
| 연결 프로그램으로 열기(다른 앱의 파일) | ✅ | ◐ | EPUB · TXT 를 `받은 파일` 폴더로 들여와 바로 연다(같은 이름 · 크기면 다시 복사 안 함). 실기기 확인 전 |
| 책 · 만화 탭, 만화 세 갈래(만화 · 웹툰 · 그림책) | ✅ | ◐ | 구상안 35A: '책 N · 만화 N(작품 수)' 탭 · 옆으로 밀기, 만화 탭은 작품 단위 세 갈래(읽는 책 · 읽은 책 · 읽을 책, 공통 `WorkStatuses`) · 격자 · 목록 · 책장, 읽는 중 칸은 이어 볼 권을 바로 연다(표지 꼬리표 'n권' · 'n권 · n쪽'), 작품 화면(이어 읽기 · 권 줄 · 합본 안의 권은 54 들임 · 같은 권 n곳). 만화로 치는 것: cbz · cbt · 그림만 든 zip · 그림 3장 이상 폴더(공통 `ComicContents`), 작품 묶기 공통 `ComicShelf`. 표지는 cover/folder 그림 또는 첫 쪽, 펼침면이면 앞표지 반(공통 `CoverCrop.front`). 웹툰 보기 · 그림책 ⬜ |
| 격자 · 목록 · 원목 책장 보기 | ✅ | ✅ | 2026-10-21 구상안 9 · 10 · 11 로 0.54.8 에 맞춤: 머리 없앰 · 도구 한 줄(순서 ▾ · 찾기 · 격자\|목록\|책장 · 더 보기) · 원목 책장(값은 `WoodShelf.kt` 그대로, 결 난수만 다름) · 대신 표지 띠 · 순서 판(OLO). 더 보기의 독서 기록 줄은 그 기능을 만들 때 |
| 읽는 책 · 읽은 책 · 읽을 책, 책 찾기 | ✅ | ✅ | 갈래(0.43 이름 · 안드로이드 순서) · 순서 ✅, 세 갈래 모두 같은 보기 · 줄바꿈(0.38) ✅. 서재 이름은 책 안의 제목 · 저자(구상안 29A — 전에는 파일 이름이었다, 제목이 없으면 확장자 뗀 파일 이름, 판마다 `Caches/book-info.json` 에 기억). 책 찾기(29A) ✅: 제목 · 저자 · 파일 이름, 소문자 · 공백 무시 · **한글을 모아 쓴 꼴(NFC)로 맞춘 뒤** 견준다(맥에서 받은 파일 이름은 NFD 일 수 있다 — 안드로이드에는 없는 단계), 갈래 차례 · 가나다순, 맞은 곳 칠, 찾던 말은 닫아도 남는다 |
| 작품 묶기 · 작품 정리 · 압축 속 압축을 권으로 | ✅ | ◐ | 작품 묶기 ✅(자동, 공통). 작품 정리(합치기 · 빼기 · 이름 고치기 · 사본 고르기) · 길게 눌러 갈래 옮기기 · 압축 속 압축 ⬜ |
| 표지 고르기(반쪽 · 왼/오/통째로) | ✅ | ⬜ | |
| 옮긴 파일의 기록 잇기 | ✅ | ⬜ | |
| 기록 백업 · 가져오기 | ✅ | ✅ | 구상안 31A: 앱 정보가 판에서 화면으로, '읽기 기록' 두 줄, 저장 창만. 형식은 안드로이드 `RecordsCodec` 과 같은 JSON(Kotlin `RecordsCodec` — 한 칸 들여쓰기 · null 칸은 키째 뺌 · 빈 `time` 은 뺌 · 날은 "YYYY-MM-DD"), 합치기는 `mergeInto` 와 같은 규칙(읽은 자리 더 뒤쪽 · 연 때 나중 · 다 읽은 때는 이 기기 우선 · 책갈피는 자리 · 형광펜은 구간으로 겹침 없앰 · 시간은 큰 쪽). 맞추기는 파일 이름 + 크기, **이름은 한글을 모아 쓴 꼴로 견준다**. 못 찾은 책은 `pending-records.json` 에 두었다가 서재를 훑을 때 붙인다. **만화(2026-10-22)**: 권마다 읽은 쪽 · 다 읽은 때 · 쪽 책갈피 · 읽은 시간을 안드로이드 `ComicRecord` 와 같은 칸으로 담고 받는다. 맞추기는 이름 + 크기(그림 폴더는 이름만, 압축을 풀어 같은 이름 폴더로 옮긴 것도 하나면 붙음), 합치기는 `mergeComicInto` 규칙(쪽은 더 뒤일 때만 · 다 읽은 때는 이 기기 우선 · 책갈피는 쪽으로 · 시간은 큰 쪽). 작품 설정(이름 · 방향 · 보는 방식 · 갈래 · 표지 · 펼침면 나누기)은 작품이 없어도 바로 이 기기 설정으로, 이미 정한 칸은 덮지 않는다(`mergeWorks`). iOS 가 아직 쓰지 않는 칸(웹툰 자리 `offset` · 펼침면 반 `half` · 보는 방식 · 펼침면 나누기)도 `ReadingStore` 에 간직해 다음 백업에 다시 담는다 — iOS 에서 그 권을 다시 읽으면 쪽 전체로 읽은 것이라 자리 · 반은 지운다. 안드로이드에서 고친 작품 이름은 iOS 서재에도 보인다. 미리 보기 · 끝 판은 "찾은 책 n권 · 만화 n권" · "작품 설정 N개를 가져왔습니다"(안드로이드 글 그대로). 다른 점: 모르는 형광펜 색은 노랑으로 들어온다(iOS 칠은 넷) |
| 이어 읽기 위젯 | ✅ | ⬜ | WidgetKit |
| 기기 간 이어 읽기 | ✅ | ⬜ | |

### 책 (EPUB · TXT)

| 기능 | 안 | i | iOS 메모 |
|---|---|---|---|
| 쪽 넘김 · 목차 · 진도 · 남은 시간 | ✅ | ✅ | 넘김(누르기 30·40·30% · 밀기) · 진도 막대 · 읽던 자리 · 목차 판(6A, iPad 는 옆 판 400) ✅. 읽기 메뉴 0.45 낮은 메뉴(구상안 12A: 위 52 · 진행 40 · 도구 46) ✅. 아래 줄은 화면 끝에서 14 · 좌우 본문 여백 선 · 읽는 동안 홈 표시줄 숨김(13A — 안드로이드의 좌우 16 은 iPhone 둥근 모서리에 잘린다), 본문 아래 여백에 홈 표시줄 영역을 더하지 않는다. 하단 정보(구상안 28A) ✅: 세 자리 × 없음 · 책 제목 · 장 제목 · 시계 · 배터리 · 쪽 · 진행률 · 이 장 남은 쪽 · 이 장/책 남은 시간, 저장값은 안드로이드 이름(`footerLeft` 등). **고르는 화면의 줄마다 회색 본보기 글**("이 장 4분 남음") — 안드로이드에는 없다(이름만으로 '쪽' 과 '이 장 남은 쪽' 이 헷갈려서). 남은 시간은 안드로이드 `ReadingSpeed` · `TurnSampler` 와 같은 규칙(사람이 바로 다음 쪽으로 넘긴 것만, 2초 ~ 10분, 지수 평균 0.2, 세 번 전에는 빈칸, 모든 책에 하나 — `reader.speedChars`). 배터리는 모르면(시뮬레이터) 빈칸 |
| 글꼴(휴대폰 · 출판사 · 넣은 글꼴) · 크기 · 줄 간격 · 여백 · 정렬 · 들여쓰기 | ✅ | ✅ | 크기 · 줄 간격 · 여백 · 정렬 · 들여쓰기 · 문단 간격 ✅. 글꼴 판(구상안 7A · 8) ✅: 휴대폰 글꼴 · 사용자 글꼴 넣기(TTF · OTF · TTC, 웹 글꼴 · 깨진 파일 · 64MB 초과는 까닭과 함께 거절) · 빼기, 한글 없는 글꼴의 한글은 휴대폰 글꼴. 굵게는 같은 가족의 굵은 꼴이 없으면 합성. 출판사 글꼴 ✅(기본 켬, 글꼴이 든 책에서만 줄이 보인다, 켜면 책이 정하지 않은 곳은 휴대폰 글꼴). 가변 글꼴의 굵기 축은 아직 쓰지 않는다(파일의 기본 굵기). 목록 이름은 글꼴의 가족 이름(앱이 한국어 지역화가 없어 영문 이름이 나온다: AppleMyungjo) |
| 배경(흰 · 아이보리 · 세피아 · 회색 · 검정) · 그림 흰 바탕을 지면색으로 | ✅ | ✅ | 메뉴도 지면 밝기를 따른다(검은 지면 = 어두운 메뉴) |
| 두 쪽 보기(가로 · 넓은 화면) | ✅ | ✅ | 구상안 27: 가로 켬(기본) · 세로는 짧은 변 600 이상(iPad)에서만, iPhone 에서는 흐림 + "넓은 화면(iPad)에서만 쓸 수 있습니다". 공통 `spreadStart` · `nextSpread` · `previousSpread` · `saveProgress(lastShown)` · `removeBookmarkAt(lastShown)` 그대로. 한 쪽 폭 = (화면 − 양옆 안전 영역) ÷ 2, 안전 영역은 바깥에만. 엔진이 펼침을 한 장(가운데 책등 13%)으로 그리고, 누른 자리 · 고른 상자는 화면 좌표로 오가며 어느 쪽인지는 엔진이 가린다. 말림은 iOS 펼침 말림(책등 가운데 · 양면). 화면 묶음 차례를 안드로이드대로(형광펜 → 두 쪽 → 화면 켜짐 → 하단 정보) |
| 찾기 · 각주 판 · 링크 | ✅ | ✅ | 찾기(구상안 16A · 17) ✅. 각주 · 링크(18) ✅: 각주 표시는 강조 글자색(지면마다 대비 4.5 — `PagePainter.readableOn`), 누르면 아래 판(각주 자리로 가기 · 닫기, 길면 60% 까지), 책 안 링크는 건너가고 "읽던 곳으로" 칩(목차 · 진도 · 책갈피 · 찾기로 옮기면 지운다), 바깥 주소는 묻고 열기, 없는 곳은 알림. 링크가 넘김 자리보다 먼저(손가락 48 안이면 그 링크) |
| 독서노트(책갈피 · 형광펜 · 메모) · 내보내기 | ✅ | ◐ | 책갈피(14A) ✅. 형광펜 · 메모(구상안 19A · 20) ✅: 길게 눌러 낱말 고르기 · 손잡이 둘 · 어두운 알약(4색 · 메모 · 복사 · 공유 · 사전 — iOS 사전 창), 칠한 곳 누르면 같은 알약(지금 색 고리 · 지우기), 칠 45%/38% · 메모 쪽지, 메모 판, 독서노트 거르기 칩 · 칠 줄 · 메모 상자 · ⋮(메모 · 색 바꾸기 · 공유 · 지우기), 모든 보기 설정 "형광펜 · 메모 보임/숨김". 저장은 `ReadingStore` 의 `A` 줄. 쪽을 넘어 이어 고르기(21) ✅ — "이어서 ›" · 끝은 새 쪽 첫 문장 끝 · "앞 쪽에서 이어 고르는 중 · N자" · 독서노트 "5–6쪽에 걸침". 내보내기(22) ✅ — 담을 것 · 글/마크다운 · 미리 보기 · 파일로 저장(iOS 파일 저장 창) · 공유, 글은 `NotesExport`(안드로이드와 같은 기대값의 시험) |
| 넘김 효과(말림 · 덮기) · 넘김 소리 · 진동 | ✅ | ✅ | 구상안 23A: 없음 · 서서히 · 밀기 · 덮기 · 말림(기본). **말림은 iOS 기본 말림**(`UIPageViewController`, Apple Books 와 같은 모양 — 안드로이드의 원통 말림과 모양이 조금 다르다), 앞 · 뒤 쪽을 미리 그려(`peek`) 손가락을 따라 말린다. 소리 끔 · 사락 · 휙 · 톡(안드로이드와 같은 WAV), 진동 켬 · 끔. 사람이 넘긴 쪽만, 무음 스위치 · 다른 소리 중 · 동작 줄이기면 내지 않음. 이어폰 · 가로 · 폭 600 이상이면 넘어가는 쪽에서 쓸림(`TurnFx`) |
| 듣기(TTS) · 화면 끄고 듣기 · 어절 쉼 | ✅ | ✅ | 구상안 24 · 25A. 진행은 안드로이드 `Listening` 을 Swift 로 옮김(`Listening.swift` — 세대 번호로 늦은 알림 무시 · 다음 한 문장 줄 세움 · 장 끝 타이머 · 사람이 넘긴 쪽의 첫 문장으로), 음성은 `AVSpeechSynthesizer`. 조종판 · 듣기 판(속도 0.5–2.0 · 목소리 · 타이머 · 어절 쉼)은 안드로이드와 같다. **목소리는 음질별 묶음**(iOS 에는 음성 엔진 갈래가 없다: 프리미엄 · 향상됨 · 기본 음질). **받지 않은 목소리는 목록에 없다** — iOS 가 깔린 목소리만 알려 주므로 "내려받기 필요" 줄을 둘 수 없고, 맨 아래 설정 경로로 안내한다. 화면 끄고 듣기 = 백그라운드 오디오(`UIBackgroundModes audio`, 듣는 동안만 `.playback`, 끄면 넘김 소리의 `.ambient` 로). 잠금 화면 · 제어 센터 카드(`MPNowPlayingInfoCenter`): 제목 · 지금 문장 · 상태, 앞 문장 · 멈춤 · 다음 문장 — **"듣기 끝내기" 단추는 iOS 카드에 둘 수 없다**. 전화가 오면 멈췄다 이어 읽고 이어폰을 빼면 멈춘다. 빠르기 1.0× = iOS 기본(0.5), 2.0× = iOS 최고 |
| 자동 넘김 | ✅ | ✅ | 구상안 26: 끔 · 15초 · 30초 · 60초, 알약(남은 초 · 멈춤 · ✕ 이번에만 끔). 메뉴 · 판 · 듣기 · 고르기 · 메모 · 각주 판 · 앱이 뒤로 가면 쉰다, 쪽이 바뀌면 처음부터, 책 끝에서 멈춤, 넘김 효과는 내되 소리 · 진동은 없음, 도는 동안 화면을 켜 둔다 |
| 터치 영역(기본 · 좌우 바꾸기 · 한 손) | ✅ | ✅ | 구상안 37: 보기 설정 › 넘기기 맨 위 '터치 영역' 줄 → 고르는 화면(그림: 앞 쪽 푸른 회색 · 메뉴 · 다음 쪽 클레이, 고르면 돌아옴). 판단은 Kotlin `TouchZones.step`(안드로이드 `actionAt` + `forward` 와 같은 규칙, `TouchZonesTest`): 가운데 40% 메뉴, 오→왼 만화면 기본 · 좌우 바꾸기의 자리를 뒤집고 한 손은 늘 다음, 오른쪽 위 모서리 56 은 언제나 책갈피. 오→왼 만화에서 열면 '이 작품은 오→왼으로 읽습니다 — 좌우가 바뀝니다' 와 뒤집힌 글 · 그림(0.54.9). 책 · PDF · 만화가 한 설정. 손쉬운 사용의 '다음 쪽' · '앞 쪽' 동작도 읽는 순서로(오→왼 만화에서 거꾸로 가던 것을 함께 고침) |
| 음량 키로 넘기기 | ✅ | ✕ | iOS 는 음량 키를 앱이 가로챌 수 없다(우회는 심사 거절). 구상안 37 · 2A: 보기 설정에 줄을 두지 않는다 |

### PDF

| 기능 | 안 | i | iOS 메모 |
|---|---|---|---|
| 쪽 보기 · 확대 · 폭 맞춤 · 목차 · 암호 PDF | ✅ | ◐ | 구상안 32A · 33: 서재에 PDF(표지 = 첫 쪽, 제목 · 저자는 공통 `PdfStructureReader` 정리 규칙), 쪽은 PDFKit 으로 그려 지면색을 곱한다(검은 지면은 0.78 로 어둡게만). 쪽 맞춤 쪽 전체(기본) · 폭 · 이어서 — 앱 전체 하나(`pdfFit`). 쪽 전체: 넘김 효과 · 말림 · 소리 그대로, 가로 두 쪽(표지 따로/함께 — 책마다, 처음 값은 모든 보기 설정, 혼자인 쪽은 공통 `SpreadSlot`). 폭: 한 화면씩(10% 겹침) 내리다 쪽 끝에서 넘김, 앞 쪽은 끝부터, 밀면 바로 넘김, 알약 '4쪽 · 2번째 화면 (2/4)' · 막대, 쪽 안 자리 저장. 이어서: 사이 14 · 짙은 틈, 누르면 90%, 밀면 한 쪽, 알약 'n쪽 · N쪽 중', 지금 쪽 = 가운데 · 저장 = 맨 위. 확대: 두 번 누르기 2.5배 · 두 손가락 최대 5배(쪽 전체 · 폭, 두 쪽 · 이어서는 없음), 넘기면 풀림. 목차(쪽 이름표 · 줄 끝 쪽), 책갈피 'n쪽'(펼침이면 두 쪽 모두 뺌), 진도 막대 'n쪽', 아래 줄 'iv · 4 / 230' · 이 장 남은 쪽(목차 항목까지). 암호는 묻지 않고 '암호가 걸린 PDF입니다. …'(32A), 깨진 파일 · 쪽 없는 파일 안내. **다른 점**: 폭 · 이어서에서는 말림 대신 그 밖의 넘김 효과(말림은 화면 한 장이 쪽 전체여야 한다), PDF 의 남은 시간은 쪽 단위 빠르기(2026-10-22, `reader.speedPages` — 글자 빠르기와 따로, 안드로이드 `speed.pages` 와 같은 갈래, 분량은 보인 쪽 수 · 이 장은 목차 항목까지 남은 쪽 · 책은 오른쪽 쪽 뒤부터, 이어서 보기는 쪽을 넘기지 않아 재지 않는다), 화면 회전 줄 없음 |
| 찾기 · 형광펜 · 듣기 | ✅ | ⬜ | 구상안 34 확정(화면은 책과 같다, 쪽 단위 · 한 쪽 안에서만 · 스캔본 안내). 34A: 글자 층 코드가 공통으로 옮겨지기를 기다린다(변경 기록의 요청) |
| 그림 위주 PDF 를 만화로 보기 | ✅ | ⬜ | |

### 만화 · 웹툰

| 기능 | 안 | i | iOS 메모 |
|---|---|---|---|
| CBZ · 폴더 · CBR · CB7 · CBT | ✅ | ◐ | CBZ · CBT(공통 `ZipReader` · `TarReader`, 종류는 파일 머리로) · 그림 폴더 ✅. **CBR · CB7 은 서재에 올리지 않는다**(35A — 풀 도구가 없어 보이는데 열리지 않는 책이 된다). 해제기를 iOS 용으로 빌드하거나 다른 해제기 ⬜ |
| 쪽 넘김 · 오→왼 · 두 쪽 · 붙여 넘기기 · 꽉 채우기 | ✅ | ◐ | 구상안 36: 화면 전체 맞춤(휴대폰은 위에 붙임, 태블릿은 아래), 어두운 바탕 0x141311 · 메뉴도 어둡게, 넘김 효과 · 소리 · 자동 넘김은 책과 같음. 오→왼(작품마다: 고른 값 → ComicInfo → 왼→오): 누름 · 밀기 · 말림 방향 · 두 쪽 순서 · 진행 막대가 뒤집힘, 그림은 그대로. 가로 두 쪽(공통 `ComicSpreads` — 표지 혼자 · 펼침면 혼자, 혼자인 쪽은 `SpreadSlot.onLeft`), 표지 따로/함께는 작품마다(처음 값은 모든 보기 설정). 진도 · 다 읽음은 판의 마지막 쪽, 다 읽고 끝에서 멈춘 권은 처음부터. 확대(두 번 2.5배 · 두 손가락 5배), 깨진 쪽은 '이 쪽을 그리지 못했습니다', 쪽 목록 · 책갈피(쪽 단위), 권 끝 판(다음 권 · 이어서 읽기 · 서재로 · '마지막 권입니다'), 아래 줄 없음. **다른 점**: 밀기 효과의 '앞뒤 판 붙여 미끄러뜨리기' 대신 책과 같은 전환, 터치 영역 고르기는 책과 한 설정(터치 영역 줄), 보는 방식 줄은 웹툰과 함께(쪽 다듬기 줄은 구상안 38). 만화 기록 · 작품 설정은 백업으로 오간다(기록 백업 줄) |
| 펼침면 나누기 · 반쪽 이어 보기 · 여백 자르기 · 색 보정 | ✅ | ✅ | 구상안 38 · 2A: 보기 판에 넘기는 방향 → 여백 자르기(기본 끔) → 펼침면 나누기(기본 켬) → 이 작품(따름 · 나눔 · 통째로, 한 단 들임 · 옅은 강조 바탕, `O SPLIT`) → 색 보정(끔 · 선명하게 · 흑백). 판 · 반 차례 · 여백 상자 · 색 행렬은 Kotlin `IosComicLayout`(공통 `SpreadSplit` · `ComicSpreads` · `MarginTrim` 을 묶음, 행렬은 안드로이드 `comicColorMatrix` 와 같은 값, `ComicTrimTest`), 자르기는 `CGImage.cropping`, 색은 `CIColorMatrix`(sRGB 로 셈 — 선형으로 하면 중간 밝기가 달라진다). 반을 보는 중이면 위 막대 '· 왼쪽 반', 진도에 half 를 적어 다시 열면 그 반에서(백업의 half 칸). 두 쪽 판은 한 여백 상자를 함께, 반 조각은 접힌 자리를 지키는 상자. 쪽 목록은 펼침면이 한 줄을 다 쓰고 나누는 동안 두 반을 따로 고른다. 확대는 보이는 조각 그대로 |
| 웹툰 세로 스크롤 · 다음 화 이어 붙이기 · 그림 폭 · 자동 스크롤 | ✅ | ⬜ | |

### 휴대폰과 맞물리는 것

| 기능 | 안 | i | iOS 메모 |
|---|---|---|---|
| 밝기(책을 열면 휴대폰 밝기, 바꾼 밝기는 그 책에서만) | ✅ | ✅ | `UIScreen.brightness` 는 **휴대폰 전체**를 바꾼다 — 책을 닫거나 앱을 나갈 때 반드시 되돌린다 |
| 읽기 잠금(다른 앱으로 못 나감 · 전화 오면 풀림) | ✅ | ✕ | 앱이 화면 고정을 걸 수 없다. 사용자가 켜는 "사용법 유도"(Guided Access) 안내로 대신할지 구상안에서 정한다 |
| 화면 켜짐 유지 | ✅ | ✅ | 휴대폰 · 10분(마지막 넘김 뒤) · 항상. `isIdleTimerDisabled` |
| 화면 회전 고정 | ✅ | ⬜ | |
| 폴더블 · 태블릿 반응형(좁음 · 중간 · 넓음) | ✅ | ⬜ | `RESPONSIVE_PLAN.md` 의 표를 iPhone · iPad 에 그대로 |
| 독서 기록 · 통계 | ✅ | ◐ | 책 ✅(구상안 30): 서재 › 더 보기 › 독서 기록. 타일 · 달력 · 올해 다 읽은 것 · 가장 오래 읽은 책 · 따로 센 시간(없는 쪽은 뺀다 — 안드로이드는 "1분 미만" 으로 보인다), 넓은 화면(840 이상) 두 판. 재기는 `PageClock` 규칙(쪽마다 5분까지, 건너뛰기 전 쪽은 버림, 앱이 뒤로 가면 멈춤, 듣기는 1분마다 끊어 LISTEN, 자동 넘김은 AUTO). 저장은 `ReadingStore` 의 `T bookId day mode millis`(하루 24시간 상한) — 백업을 만들 때 안드로이드의 `time` 칸과 같은 값이 된다. 만화 · 웹툰 시간 ⬜(만화를 만들 때) |

---

## 3. 변경 기록 (안드로이드 → iOS)

새 줄은 **위에** 더한다. 형식: `판 · 날짜 · 무엇(사용자가 느끼는 것) · 공통/화면 · iOS: 대기|반영(커밋)|해당 없음(까닭)`

iOS 세션이 공통 코드의 문제를 찾으면 판 칸에 `iOS→안` 이라고 적은 줄을 더한다. 안드로이드 세션이 고친 뒤 판을 적는다.

| 판 | 날짜 | 무엇 | 범위 | iOS |
|---|---|---|---|---|
| 0.55.0 | 2026-10-22 | 태블릿 보기 판을 보기 단추 위 카드(420dp)로 · 메뉴와 본문 사이 선 + 그림자 · 모든 판이 열리고 닫힐 때 움직임(값 표는 `design-handoff/README.md` §6-8 · 9 · 10, 코드 `CpMotion.kt`) | 화면 | 대기 — iPad 보기 설정은 단추에 붙는 팝오버가 시스템 방식이라 그대로 쓰면 된다. 시스템 시트 · 팝오버는 길이 · 곡선을 앱이 못 정한다 — 직접 그리는 판(읽기 메뉴 막대)만 같은 값으로 |
| iOS→안 | 2026-10-22 | **요청**(구상안 34A, 사용자 결정): PDF 글자 층 코드를 공통(commonMain)으로 옮겨 주세요 — 지금 안드로이드 전용 `reader-pdf` 에 있어 iOS 가 쓸 수 없다. 대상: `PageText`(rowStarts · inVisualOrder · withoutOverprint · repaired · lines · charAt · wordAt · rects · quote · speech · isReadable · textOnly), `TextLine`, `PdfSpeech`, `BrokenHangul`, `KsHangul`(java.util.Base64 → kotlin.io.encoding.Base64), `AdobeKr`, `layerHits`, 가능하면 `PageViewport.reveal`. 시험(`PageTextTest` · `BrokenHangulTest`)도 `commonTest` 로 — 이름에 쉼표가 없어야 iOS 에서 컴파일된다(아래 요청 참고). **까닭**: PDF 형광펜 자리(`Reflow(page, charOffset)`)는 이 코드가 다듬은 층 글자의 번호라, 두 앱이 같은 코드여야 PDF 형광펜이 백업으로 오간다. iOS 는 층을 PDFKit 의 `PDFPage.string` · `characterBounds(at:)` 로 채워 `PageText(text, boxes)` 를 만들 생각이다(번호 체계가 같아 `assembleLayer` 는 필요 없을 듯). 옮긴 뒤 iOS 가 PDF 찾기 · 형광펜 · 듣기(34)를 잇는다 — 그때까지 PDF 2단계는 멈춘다 | 공통 | 대기(안드로이드) |
| iOS→안 | 2026-10-21 | **요청**: `commonTest` 로 옮긴 시험(4c63d06)이 iOS 에서 컴파일되지 않는다 — `:document` · `:core-layout` 의 `compileTestKotlinIosSimulatorArm64` 오류 67개. (1) Kotlin/Native 는 시험 이름(백틱)에 쉼표를 받지 않는다: `Name contains illegal characters: ","` — 29개 파일(CssParserTest 5 · CoverCropTest 6 · PictureBookTest 5 · ComicNameTest 4 · ComicReadingTest 4 · TocParserTest 4 · SpreadSplitTest 3 · ChapterParserTest 3 …). 쉼표를 ` and ` · 대시로 바꾸면 된다(iOS 시험들이 그렇게 한다). (2) `ContainerParserTest` 가 JVM 의 `reader()` 를 쓴다(7곳) — `jvmTest` 로 되돌리거나 공통 입력으로. 맥에서 `./gradlew :document:iosSimulatorArm64Test :core-layout:iosSimulatorArm64Test` 로 확인할 수 있다. 공통 코드라 iOS 세션은 고치지 않고 요청으로 남긴다 | 공통 | 대기(안드로이드) → **처리**(2026-10-22, 안드로이드): 시험 이름의 쉼표 52곳을 " - " 로, `String.reader()` 를 쓰던 5개 파일(ContainerParser · XmlScanner · PictureBook · Opf · Toc)을 공통 `StringInput` 으로. 리눅스 `jvmTest` 통과. 맥에서 `iosSimulatorArm64Test` 확인 — iOS: 대기 |
| 0.54.9 | 2026-10-20 | 터치 영역의 설명 · 그림을 읽기 방향대로 — 오→왼 만화에서 열면 "이 작품은 오→왼으로 읽습니다 — 좌우가 바뀝니다" 와 뒤집힌 설명, 한 손 설명에 미는 방향(`TouchZones.description(rightToLeft)`) | 화면 | 반영(2026-10-22, 구상안 37) — 같은 글 · 그림 규칙, Kotlin `TouchZones.description` |
| 0.54.8 | 2026-10-20 | 오→왼 만화에서 한 손(양쪽 다음)으로 눌러도 앞 쪽으로 가던 것을 고침 — 한 손은 방향과 상관없이 늘 다음, 기본 · 좌우 바꾸기만 오→왼에서 자리를 뒤집는다(`TouchZones.forward`) | 화면 | 반영(2026-10-22, 구상안 37) — Kotlin `TouchZones.step` 이 같은 규칙, 시뮬레이터에서 오→왼 만화 한 손 양쪽 누름이 다음 쪽 확인 |
| iOS→안 | 2026-10-20 | **요청**: `docs/design-handoff/screens/` 의 그림이 9-28(0.25 무렵) 것이라 서재 · 읽기 메뉴가 지금과 다르다. iOS 는 그 그림을 보고 서재를 만들어 0.38~0.44 개편이 빠졌다. 지금 판으로 다시 찍어 주세요(서재 격자 · 목록 · 책장, 더 보기 판, 읽기 메뉴 · 보기 판). 맥에는 안드로이드 SDK 가 없어 iOS 세션이 찍을 수 없다 | 화면 | 대기(안드로이드) → **처리**(2026-10-21, 0.54.9 화면): 12장을 다시 찍고 7장을 더함(책장 · 더 보기 판 · 만화 탭 세 보기 · 작품 화면 · 터치 영역). 목록은 `design-handoff/README.md` §8 — iOS: 대기 |
| iOS→안 | 2026-10-20 | **요청**: `jvmTest` 중 JVM 전용 기능을 쓰지 않는 시험 39개(`document` 21 · `core-layout` 18 — 대부분 이미 `kotlin.test` 만 쓴다)를 `commonTest` 로 옮겨 iOS 에서도 돌게 해 주세요. 맥에서 `check` 가 `iosSimulatorArm64Test` 로 함께 돌린다 — 두 앱이 갈라지는 것을 공통 시험이 잡는다. 공통 코드는 안드로이드 세션만 고친다는 규칙이라 요청으로 남긴다 | 공통 | 대기(안드로이드) → **처리**(2026-10-21, 안드로이드): 39개 모두 `commonTest` 로. 리눅스에서 `jvmTest` 315 · 332 통과. 맥에서 `:document:check` · `:core-layout:check` 가 `iosSimulatorArm64Test` 로 돌리는지 확인 — iOS: 대기 |
| 0.54.7 | 2026-10-18 | 공통 코어를 Kotlin Multiplatform 으로(동작 같음). iOS 시작점 | 공통 | 반영 — `iosMain` 채움(§1), 맥에서 `:document:check` · `:core-layout:check` 통과 |
