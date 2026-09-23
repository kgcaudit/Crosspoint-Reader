# 인계 문서

마지막 갱신: 2026-09-23 · 브랜치 `claude/android-epub-viewer-plan-y7m3k3`

이 문서는 **세션을 이어받는 사람(또는 다음 Claude 세션)** 이 읽는 것이다. 무엇이 끝났고
무엇이 남았고 왜 그렇게 정했는지를 한자리에 둔다. 결정을 다시 논의하지 않게 하는 것이
목적이다.

---

## 1. 지금 상태

**순수 Kotlin 코어가 완성됐다.** EPUB/TXT 파일 바이트 → 챕터 → 블록 → 페이지 →
디스크 캐시 → 글자 오프셋 위치 → 책갈피·이어읽기까지 **기기 없이 한 줄로 돌아간다.**
글자 크기를 바꿔 재조판해도 읽던 글자로 돌아오는 것까지 테스트가 지킨다.

```bash
cd android && ./gradlew check
# :document 143 + :core-layout 223 = 366개 통과
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

### 남은 것 — 전부 안드로이드 모듈이다

| 모듈 | 내용 | 비고 |
|---|---|---|
| `:text-platform` | `AndroidTextMeasurer`(`Paint` 기반) | **여기부터.** 이거 하나면 조판 엔진이 기기에서 돈다 |
| `:data` | SAF 폴더 스캔 · Room(`books` `progress` `bookmarks` `recent`) | 보관소 인터페이스는 이미 있다 |
| `:reader-pdf` | `PdfRenderer` 기반 고정 페이지 리더 | 결정 P1 |
| `:ui-design` | GUI 컴포넌트(`CpHeader` `CpList` `CpStatusBar` `CpPopup` …) | CrossPoint GUI 참고 |
| `:ui` `:app` | 라이브러리·리더·목차·설정 화면 | |

---

## 2. 환경 요구 — **이게 막혀서 여기서 멈췄다**

안드로이드 SDK 와 AGP 는 **`dl.google.com` 에만** 있다. 기본 네트워크 정책이 이 호스트를
403 으로 막으므로 SDK 를 받을 수 없고, 안드로이드 모듈은 컴파일 검증이 불가능하다.
확인한 대체 경로는 전부 막힌다:

| 경로 | 결과 |
|---|---|
| `dl.google.com` | 403 (정책 거부) |
| `maven.google.com` | 301 → `dl.google.com` → 같은 403 |
| Maven Central | 열려 있으나 AGP 가 없다(404). AGP 는 Google Maven 전용 |
| Gradle Plugin Portal | 303 → Maven Central → 404 |

**해결**: 클라우드 환경의 **Network access** 를 넓히거나 허용 도메인에 `dl.google.com`
(가능하면 `maven.google.com` 도) 을 추가한다. Maven Central 과 `services.gradle.org` 는
이미 열려 있으므로 필요한 호스트는 그것뿐이다.

SDK 를 받은 뒤 순서:

```bash
# 1. cmdline-tools 로 SDK 설치 (platform 36, build-tools, platform-tools)
# 2. android/local.properties 에 sdk.dir 기록  ← settings.gradle.kts 가 이걸 본다
# 3. ./gradlew projects   # :text-platform 등이 구성 목록에 나타나는지 확인
```

`settings.gradle.kts` 는 SDK 가 있으면 **폴더가 실제로 있는** 안드로이드 모듈만
포함하고, 없는 것은 목록으로 알려 준다. 모듈을 하나씩 만들어 붙일 수 있다.

---

## 3. 다음 할 일 (권장 순서)

### 3.1 `:text-platform` — 첫 관문

```kotlin
class AndroidTextMeasurer(
    private val paint: TextPaint,      // 사용자 글꼴·크기가 반영된 것
    override val baseSizePx: Float,
) : TextMeasurer
```

- `advance` 는 `paint.getRunAdvance` 또는 `measureText(text, start, end)` 로.
  `sizeScale` 마다 `textSize` 를 바꿔 재야 하므로, **배율별 `Paint` 를 캐시**한다.
  매 호출마다 `textSize` 를 쓰면 `Paint` 내부 캐시가 무효화돼 조판이 느려진다.
- `lineHeight` / `ascent` 는 `paint.fontMetrics` 에서.
- 만들자마자 계측 테스트에서:
  ```kotlin
  val problems = MeasurerConformance.check(measurer)
  assertTrue(problems.isEmpty(), problems.joinToString("\n"))
  ```
  한글 글리프 검사가 여기서 B2(폰트 번들링) 결정을 검증한다.
- 그다음 `GoldenPaginationTest` 를 실기기에서 한 번 돌려 본다. `FakeMeasurer` 기준
  골든과 다른 건 당연하다 — 보려는 것은 **페이지가 무너지지 않는가** 뿐이다.

### 3.2 `:data`

- 보관소 인터페이스(`BookmarkRepository` `ProgressRepository`)를 Room 으로 구현한다.
  **계약은 `ReadingSessionTest` 의 `FakeBookmarks`/`FakeProgress` 가 이미 못 박아 뒀다** —
  같은 성질(위치 오름차순 정렬, 책마다 진도 하나, id 부여)을 지키면 된다.
- SAF: 폴더 등록 · 영속 권한 · 재귀 스캔(`.epub/.txt/.pdf`). `BookId` 는 `content://`
  URI 를 담는다(`PageStore` 가 해시해서 디렉터리 이름으로 쓴다 — 테스트가 있다).
- `SeekableSource` / `ByteSource` 를 SAF `Uri` 에 붙인다. EPUB 은 랜덤 액세스가 필요하다.

### 3.3 `:ui` 리플로우 리더

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

## 4. 미결 결정 4건 — 사용자 확인 필요

| # | 항목 | 권장 | 왜 지금 필요한가 |
|---|---|---|---|
| **P1** | PDF 렌더러 | `PdfRenderer`(플랫폼 내장) | APK 크기 0, v1 범위에 충분. 목차·검색이 필요해지면 Pdfium 으로(R8) |
| **B1** | 앱·패키지 이름 | 현재 `io.github.kgcaudit.reader`, 표시명 "Reader" (가칭) | 첫 APK 를 내기 전에 정해야 한다. 나중에 바꾸면 설치 데이터가 끊긴다 |
| **B2** | 폰트 번들링 | KoPub 바탕 + Pretendard 번들 | `MeasurerConformance` 의 한글 글리프 검사와 직결. 시스템 폰트만 쓰면 기기마다 조판이 달라진다 |
| **B3** | 테스트 코퍼스 | EPUB 20 · TXT 10 · PDF 10 | `corpus/README.md` 참고. 파일은 커밋하지 않고 골든만 커밋한다 |

---

## 5. 이미 내린 결정 — 다시 논의하지 말 것

| 결정 | 근거 |
|---|---|
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
이 환경은 dl.google.com 이 열려 있으니 안드로이드 SDK 를 받아
:text-platform 의 AndroidTextMeasurer 부터 붙여라.
미결 4건(P1 PDF 렌더러 · B1 앱 이름 · B2 폰트 · B3 코퍼스) 중 필요한 것은 먼저 물어봐.
```
