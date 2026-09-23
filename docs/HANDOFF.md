# 인계 문서

마지막 갱신: 2026-09-23 · 브랜치 `claude/android-epub-viewer-text-measurer-oqy6rq`
(`claude/android-epub-viewer-plan-y7m3k3` 에서 이어짐)

이 문서는 **세션을 이어받는 사람(또는 다음 Claude 세션)** 이 읽는 것이다. 무엇이 끝났고
무엇이 남았고 왜 그렇게 정했는지를 한자리에 둔다. 결정을 다시 논의하지 않게 하는 것이
목적이다.

---

## 1. 지금 상태

**순수 Kotlin 코어가 완성됐고, 화면 아래 층(`:text-platform` `:data`)이 모두 붙었다.**
EPUB/TXT 파일 바이트 → 챕터 → 블록 → 페이지 → 디스크 캐시 → 글자 오프셋 위치 →
책갈피·이어읽기까지 **기기 없이 한 줄로 돌아가고**, 그 조판이 **번들 글꼴과 실제
`Paint`** 로 돌며(Robolectric 네이티브 그래픽스), 책갈피·진도는 **Room** 에 저장되고,
책은 **SAF 폴더**에서 찾아 연다. 남은 것은 화면뿐이다.

```bash
cd android && ./gradlew check
# SDK 있음: :document 143 + :core-layout 223 + :text-platform 12 + :data 32 = 410개 + lint
# SDK 없음: :document + :core-layout 366개 (기존과 같다)
```

### 끝난 것

| 모듈 | 내용 |
|---|---|
| `:document` | ZIP(ZIP64) · OPF · NCX/nav 목차 · 자체 XML 스캐너 · 인코딩 감지(UTF-8/16, EUC-KR) · `Locator` · 보관소 인터페이스 |
| `:core-layout` | 블록 모델 · `LineBreaker`(한국어 글자 단위 줄바꿈) · `Paginator` · `PageCodec` |
| `:core-layout` | CSS 서브셋(`css/`) · `TagDefaults` · `StyleResolver` |
| `:core-layout` | `ChapterParser`(XHTML→블록·앵커) · `TextChapter`(TXT) · `ChapterHead` · `ChapterLoader` |
| `:core-layout` | `PageStore`(디스크 캐시 · 부분 캐시 · 정리) |
| `:core-layout` | `BookLayout`(페이지 이동 · 위치 복원 · 진도) · `ReadingSession`(책갈피 · 이어읽기) |
| `:core-layout` | `MeasurerConformance` — 안드로이드 `TextMeasurer` 구현이 통과해야 할 검사 |
| `:text-platform` | `AndroidTextMeasurer`(`Paint`) · `ReaderFont`(번들 글꼴 2종) — conformance 통과 |
| `:data` | Room(`books` `progress` `bookmarks` `recent`) 보관소 · SAF 폴더 등록·재귀 스캔 · `Uri` → `SeekableSource`/`ByteSource` · `ReaderData`(묶음) |

### 남은 것 — 전부 안드로이드 모듈이다

| 모듈 | 내용 | 비고 |
|---|---|---|
| `:ui-design` | GUI 컴포넌트(`CpHeader` `CpList` `CpStatusBar` `CpPopup` …) | **여기부터.** CrossPoint GUI 참고 |
| `:reader-reflow` `:app` | 리플로우 리더(Canvas) · 라이브러리 화면 · `AppContainer` | 첫 APK. **B1 을 먼저 정한다** |
| `:reader-pdf` | `PdfRenderer` 기반 고정 페이지 리더 | 결정 P1 |
| `:ui` | 목차·설정 화면 | |

---

## 2. 환경 요구

안드로이드 SDK 와 AGP 는 **`dl.google.com` 에만** 있다. 이 호스트가 열린 환경에서만
안드로이드 모듈이 구성된다(기본 네트워크 정책은 403 으로 막는다 — 그때는 순수 Kotlin
모듈만 구성되고 그게 정상이다). 새 세션에서 SDK 를 까는 순서:

```bash
# 1. cmdline-tools 를 받아 /opt/android-sdk/cmdline-tools/latest 에 푼다
curl -O https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
# 2. 라이선스 동의 후 설치 (AGP 8.7.3 은 compileSdk 35 까지)
yes | sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
# 3. android/local.properties 에 sdk.dir=/opt/android-sdk  ← settings.gradle.kts 가 이걸 본다
```

**Maven Central 이 429 를 준다.** 클라우드 세션에서 새로 받는 의존성이 많으면 속도
제한에 걸린다(Gradle 과 Robolectric 둘 다). 저장소에 넣지 않고 컨테이너에만 둔다:

```kotlin
// ~/.gradle/init.d/central-mirror.gradle.kts — Google 이 운영하는 Maven Central 미러
val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
beforeSettings {
    pluginManagement.repositories { maven(mirror) }
    dependencyResolutionManagement.repositories { maven(mirror) }
}
```

```properties
# ~/.gradle/gradle.properties — Robolectric 은 android-all 을 Gradle 밖에서 따로 받는다
reader.robolectricRepo=https://maven-central.storage-download.googleapis.com/maven2/
```

**Gradle 플러그인은 루트 `buildscript` 가 클래스패스에 한 번만 올린다.** 새 모듈은
`plugins { id("com.android.library") }` 처럼 **버전 없이** 적용한다(`alias(...)` 금지).
모듈마다 버전을 적으면 Kotlin 플러그인이 모듈마다 다른 클래스로더에 올라가 Gradle 이
"빌드가 깨질 수 있다" 고 경고한다. Compose 모듈은 `id("org.jetbrains.kotlin.plugin.compose")`
— 클래스패스에 이미 있다.

에뮬레이터는 쓸 수 없다(`/dev/kvm` 없음). 그래서 `Paint` 검증은 Robolectric
`@GraphicsMode(NATIVE)` 로 한다. 호스트용 minikin·FreeType 이라 기기와 같은 엔진이지만,
**대체 글꼴 목록과 힌팅은 기기와 다를 수 있다** — 실기기 확인은 §3.3 에서 한 번 한다.

---

## 3. 다음 할 일 (권장 순서)

### 3.1 `:text-platform` — 끝남. 여기서 정한 것

```kotlin
val spec = LayoutSpec(..., baseSizePx = px, fontId = ReaderFont.Batang.layoutFontId)
val measurer = AndroidTextMeasurer.forSpec(context, spec)   // 글꼴·크기를 spec 에서 꺼낸다
val paint = measurer.paintFor(run.style)                    // 그릴 때도 같은 Paint
```

- **글꼴은 `LayoutSpec.fontId` 에 들어간다**(규칙 4). 글꼴을 바꾸면 폭이 달라지므로
  캐시 키에 없으면 바탕으로 잰 페이지를 고딕으로 그린다. `fontId` 에는 폰트 파일의
  판(`batang@kopubworld-1.0.3`)이 붙어 있어서, **폰트 파일을 교체하면 `ReaderFont` 의
  `revision` 을 올려야 한다.** 설정에는 판 없는 `key` 를 저장한다.
- **측정기는 `forSpec` 으로만 만든다.** 글꼴을 따로 넘기면 캐시 키와 실제로 잰 글꼴이
  갈라질 수 있다. 모르는 `fontId` 는 기본 글꼴로 연다(규칙 6).
- **줄 높이는 1em 으로 정규화했다** — 인계 초안의 "`fontMetrics` 에서" 와 다르다.
  KoPubWorld 는 hhea 지표로 줄 높이가 1.54em, Pretendard 는 1.19em 이라, 그대로 쓰면
  글꼴만 바꿔도 한 페이지의 줄 수가 30% 가까이 바뀐다. 베이스라인은 실제 글리프
  윗변(보통 글꼴에서 한 번 잰 값, KoPubWorld ≈ 0.81em)에 둔다.
- **힌팅된 폭을 쓴다**(`ANTI_ALIAS | SUBPIXEL`, `LINEAR_TEXT` 끔). 선형 텍스트는
  글리프 캐시를 꺼서 페이지 그리기가 느려진다. 같은 `Paint` 로 재고 그리므로 어긋나지 않는다.
- 기울임은 `textSkewX` 합성(한글 글꼴에 이탤릭이 없다). 굵게는 번들 Bold 파일.
- `AndroidTextMeasurer` 는 **한 스레드 전용**이다. 조판용과 그리기용을 따로 만든다.

실기기에서 할 일(아직 못 함): 같은 테스트를 계측 테스트로 한 번 돌려 호스트와 기기의
폭이 같은지 본다. 다르면 캐시를 만든 기기와 그리는 기기가 같으므로 문제는 아니지만,
기기마다 페이지 수가 달라지는 폭을 알아 둘 필요가 있다.

### 3.2 `:data` — 끝남. 여기서 정한 것

```kotlin
val data = ReaderData(context)                       // 앱에 하나. AppContainer 가 든다
data.folders.register(treeUri)                       // ACTION_OPEN_DOCUMENT_TREE 결과
data.rescan(treeUri)                                 // 또는 rescanAll() — 앱 시작·당겨서 새로고침
data.library.books() / data.library.recent()         // Flow<List<LibraryBook>>
val source = data.sources.seekableSource(Uri.parse(book.id.value))   // EPUB
val text   = data.sources.byteSource(Uri.parse(book.id.value))       // TXT
ReadingSession(layout, data.bookmarks, data.progress)
```

- **스캔은 아무것도 지우지 않는다.** 스캔이 **끝까지 성공했을 때만** 안 보인 책을
  숨기고(`missing`), 다시 보이면 되살린다. 폴더 하나라도 못 읽으면(권한 회수, 제공자
  오류, null 커서) 불완전 스캔이라 아무것도 숨기지 않는다. 진도·책갈피는 외래 키 없이
  `bookId` 로만 참조한다 — CASCADE 를 걸면 스캔 한 번의 실수로 책갈피가 지워진다.
- **등록 폴더 목록의 원천은 OS 영속 권한**(`persistedUriPermissions`)이다. 따로 저장하면
  사용자가 권한을 거둔 폴더가 목록에 남는다. 책마다가 아니라 폴더에 권한을 받는 이유는
  권한 개수 제한(128/512)이다.
- **책갈피 정렬은 숫자 열 세 개**(`orderMajor/Minor/Patch`)로 한다. 위치 문자열로
  정렬하면 `r:10:…` 이 `r:2:…` 보다 앞에 온다. PDF 는 (페이지, 세로, 가로).
- **스캔은 `DocumentsContract` 질의로** 한다(폴더당 한 번). `DocumentFile.listFiles()` 는
  파일마다 질의가 따로 나가서 500권 폴더에서 2000번이 된다. `Bundle` 판 `query` 를 부른다 —
  `DocumentsProvider` 는 O 부터 그 판만 받는다.
- **제공자의 런타임 예외는 "그 폴더를 못 읽음" 으로** 바꾼다. 다른 앱의 버그가 바인더를
  건너 올라오므로, 그대로 두면 클라우드 앱 하나 때문에 스캔 전체가 죽는다.
- **파이프를 주는 제공자는 캐시 사본으로 연다**(`statSize < 0`). 사본은 닫을 때 지운다.
- `fallbackToDestructiveMigration` 을 **쓰지 않는다.** 스키마는 `data/schemas/` 에 커밋돼
  있다 — 다음에 테이블을 바꿀 때 `Migration` 과 `MigrationTestHelper` 테스트를 같이 쓴다.
- Room 을 2.6.1 → **2.7.2** 로 올렸다(KSP2 호환). KSP `2.1.21-2.0.2`.

실기기에서 할 일(Robolectric 이 재현 못 함):
- **파이프 판정**: Robolectric 은 `createPipe()` 를 임시 파일로 흉내 내서 `statSize` 가
  -1 이 되지 않는다. 사본 경로는 테스트했지만, "파이프면 사본으로 간다" 는 판정은
  Google Drive 같은 클라우드 제공자로 한 번 확인한다.
- 실제 파일 관리자·SD 카드 제공자로 폴더 등록 → 스캔 → 열기.

알려진 한계(v2): 파일을 **다른 폴더로 옮기면 URI 가 바뀌어** 진도·책갈피가 따라가지
않는다. 필요해지면 (이름, 크기)로 옛 `bookId` 를 찾아 옮기는 단계를 `applyScan` 에 더한다.

### 3.3 `:ui-design` · `:reader-reflow` · `:app` — 첫 APK

R1 스프린트는 PDF 부터였지만, PDF 는 P1 이 미결이고 리플로우 경로는 아래 층이 전부
준비돼 있다. **TXT/EPUB 리더로 첫 APK 를 내는 것을 권한다.** 첫 APK 전에 B1(패키지
이름)을 정해야 한다 — 설치 후 바꾸면 사용자 데이터가 끊긴다.

`:app` 을 만들 때: 폰트가 압축돼 들어가면 로드할 때 메모리로 풀린다(KoPubWorld 한
벌 8MB × 2). `androidResources { noCompress += listOf("otf", "ttf") }` 로 mmap 되게 할지
정한다 — 설치 크기 +10MB 대 메모리 16MB 의 교환이다. 라이브러리 모듈의 설정은 앱으로
전파되지 않으므로 `:app` 에서 해야 한다. 또 KoPub 약관은 **받는 사람에게 약관을 알릴
의무**가 있으므로 `assets/licenses/` 를 보여 주는 화면(오픈소스 라이선스)이 필요하다.

리플로우 리더:

`BookLayout` 과 `ReadingSession` 만 쓰면 된다. 화면에 남는 일은 이것뿐이다:

```kotlin
val position = session.restore()                       // 지난번 자리
val page = layout.page(position.spineIndex, position.pageIndex)
// page.runs / images / rules 를 좌표 그대로 Canvas 에 찍는다 (산술 없음)
layout.next(position) / layout.previous(position)       // 챕터 경계도 여기서 넘긴다
session.addBookmark(position) / session.saveProgress(position)
```

`Page` 의 좌표는 여백이 반영된 **절대값**이다. 그리는 쪽에 산술을 남기지 않는 것이
16ms 예산의 전제다.

### 3.4 `:reader-pdf`, GUI, 나머지

`docs/ANDROID_BUILD_SPEC.md` §6 의 R1·R4 대로.

---

## 4. 미결 결정 3건 — 사용자 확인 필요

| # | 항목 | 권장 | 왜 지금 필요한가 |
|---|---|---|---|
| **P1** | PDF 렌더러 | `PdfRenderer`(플랫폼 내장) | APK 크기 0, v1 범위에 충분. 목차·검색이 필요해지면 Pdfium 으로(R8) |
| **B1** | 앱·패키지 이름 | 현재 `io.github.kgcaudit.reader`, 표시명 "Reader" (가칭) | 첫 APK 를 내기 전에 정해야 한다. 나중에 바꾸면 설치 데이터가 끊긴다 |
| **B3** | 테스트 코퍼스 | EPUB 20 · TXT 10 · PDF 10 | `corpus/README.md` 참고. 파일은 커밋하지 않고 골든만 커밋한다 |

---

## 5. 이미 내린 결정 — 다시 논의하지 말 것

| 결정 | 근거 |
|---|---|
| **B2: KoPubWorld 바탕 + Pretendard 번들** (2026-09-23 사용자 결정) | 시스템 글꼴은 기기마다 조판이 달라진다. KoPub 구판이 아니라 **KoPubWorld** 인 이유: 구판에는 `—`(U+2014)가 없고 한자가 4,620자뿐이다(World 는 6,007자). 두 글꼴 모두 한글 11,172자 전부. 라이선스: Pretendard 는 OFL, **KoPubWorld 는 OFL 이 아니라 KOPUS 약관**(무료 재배포 가능 · 유료 판매 금지 · 약관 동봉 의무 · 수정본에 "KoPub" 이름 금지) — 그래서 서브셋하지 않고 원본을 넣었다. 저장소 +21.5MB, APK +11MB(압축) |
| C++ 를 옮기지 않는다. GUI 구성만 참고 | 사용자 명시: "crosspoint 의 GUI 구성이 마음에 들었을 뿐이라, 코드 구조는 어떤 것이든 상관없어". 원본의 60~70% 는 ESP32 제약 때문의 코드다 |
| 네이티브 Canvas + 디스크 페이지 캐시 | WebView 는 메모리·시작 시간이 무겁고 조판을 통제할 수 없다. `docs/ANDROID_ARCHITECTURE_DECISION.md` |
| 안드로이드 텍스트 API 를 조판에 쓰지 않는다 | `StaticLayout` 으로는 CrossPoint 의 한국어 양쪽정렬을 표현할 수 없음을 확인했다(INTER_WORD API 26, INTER_CHARACTER API 35, `lineBreakWordStyle=phrase` API 33 — 최소 지원 API 26 에서 불가) |
| 코어는 순수 Kotlin | 기기 없이 366개 테스트로 파일→픽셀 전체를 검증한다. 이 값이 계속 드러나고 있다 |
| 페이지 캐시는 직접 만든 이진 포맷 | 고정 길이 런 레코드라 "페이지 N 읽기" 에 파싱이 없다. §7 |
| TXT 를 EPUB 보다 먼저 | ZIP·XML·CSS 없이 조판을 단독 검증할 수 있다 |
| 진도 무게는 압축 전 파일 크기 | 정확한 글자 수는 조판해 봐야 알고, 막대를 그리려고 책 전체를 조판할 수는 없다 |

---

## 6. 이 과정에서 실제로 걸린 것들

윈도우 PC(회사 장비)에서 로컬 검증을 시도하다 다섯 번 막혔다. 전부
`docs/LOCAL_SETUP.md` 에 적혀 있다. 요약:

1. **PKIX 오류** — TLS 검사 프록시. `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`
2. **`What went wrong: 25.0.4.1`** — JDK 25 는 Gradle 8.14.3 이 못 쓴다. **JDK 17~21** 로 고정
3. **배치 파일 깨짐** — cmd.exe 가 실행 중에 배치 바이트를 읽으므로 UTF-8 한국어 + `chcp` 가
   어긋난다. `.bat` 은 ASCII 로만 쓰고 PowerShell(**BOM 있는 UTF-8**)을 띄운다
4. **JDK 탐색 실패** — PATH 의 첫 java 가 새 설치를 가린다. 여러 위치를 다 본다
5. **`Could not move temporary workspace`** — Gradle 트랜스폼 캐시. stop → 캐시 삭제 → 재시도,
   그래도 안 되면 `GRADLE_USER_HOME` 을 LocalAppData 로

교훈: **로컬 검증은 이 코어에 대해서는 값이 없었다.** 저장소에서 새로 클론해 이미 검증하고
있기 때문이다. PC 가 필요해지는 시점은 안드로이드 스튜디오로 화면을 볼 때다.

---

## 7. 새 세션 시작 문구

```
docs/HANDOFF.md 와 CLAUDE.md 를 읽고 이어서 진행해.
브랜치는 claude/android-epub-viewer-plan-y7m3k3 이고, 순수 Kotlin 코어는 끝나 있다.
:text-platform(측정기·글꼴) · :data(Room · SAF)까지 끝나 있다. HANDOFF §2 대로 SDK 와
Maven 미러를 설정하고 §3.3(:ui-design · :reader-reflow · :app, 첫 APK)부터 이어가라.
미결 3건(P1 PDF 렌더러 · B1 앱 이름 · B3 코퍼스) 중 필요한 것은 먼저 물어봐.
```
