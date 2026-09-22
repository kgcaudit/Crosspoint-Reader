# 안드로이드 EPUB 뷰어 — 세부 시행계획 (빌드 사양서)

**확정 방향**: 네이티브 Canvas 조판 + 디스크 페이지 캐시. WebView 미사용. `ANDROID_ARCHITECTURE_DECISION.md` §8 한계 수용.
**이 문서의 성격**: 착수부터 1차 배포까지 **작업 단위로 실행 가능한** 사양서. 설계 논의는 끝났고, 여기서는 "무엇을 어떤 순서로 만들고 무엇으로 끝났다고 판정하는가"만 다룬다.
**작성일**: 2026-09-22

---

## 1. 확정 사항 (변경하려면 이 문서를 고쳐야 함)

| 항목 | 확정값 |
|---|---|
| 본문 렌더링 | **Canvas + Paint 직접 그리기.** WebView·Compose Text 미사용 |
| 페이지네이션 | **챕터 단위 사전 조판 → 디스크 캐시.** 페이지 넘김 경로에 계산 없음 |
| 줄바꿈 | `LineBreaker` 전략 인터페이스. **v1 = `PlatformLineBreaker`(StaticLayout)**, v2 = `KoreanLineBreaker`(S6) |
| UI | Compose + Material 3. 리더 본문만 Canvas |
| DI | **수동 (생성자 주입 + `AppContainer`).** Hilt 미사용 |
| 저장 | Room(라이브러리·진도·북마크) + DataStore(설정) + 자체 바이너리(페이지 캐시) |
| EPUB 파싱 | **자체 구현.** Readium·epub4j 미사용 (`BookSource` 뒤에 두어 교체 가능) |
| 파일 접근 | SAF 폴더 등록(`ACTION_OPEN_DOCUMENT_TREE`) |
| minSdk / target / compile | **26 / 35 / 36** |
| 라이선스 | **MIT** (원본 CrossPoint MIT 계승, 출처 표기) |
| v1 포맷 | EPUB 2/3 리플로우 전용 |

### 성능 예산 (CI 게이트)

| 지표 | 예산 |
|---|---:|
| 페이지 넘김 p95 | **≤ 16 ms** |
| 책 열기 (캐시 적중) | ≤ 300 ms |
| 첫 페이지 표시 (300KB 챕터, 최초) | ≤ 1,500 ms |
| 콜드 스타트 → 라이브러리 | ≤ 500 ms |
| 리더 RSS | ≤ 120 MB |
| APK (폰트 제외) | ≤ 12 MB |

---

## 2. 저장소 구조

```
Crosspoint-Reader/                 ← 현재 저장소를 그대로 사용
├─ docs/                           ← 기존 계획 문서 4종
├─ android/                        ← 신규. Gradle 루트
│  ├─ settings.gradle.kts
│  ├─ gradle/libs.versions.toml    ← version catalog (의존성 단일 출처)
│  ├─ app/                         :app          Application, AppContainer, Navigation
│  ├─ ui/                          :ui           화면 20개
│  ├─ ui-design/                   :ui-design    CpTheme 토큰 + 컴포넌트 10종
│  ├─ reader-render/               :reader-render Canvas 그리기 · 제스처 · 애니메이션
│  ├─ text-platform/               :text-platform TextMeasurer/LineBreaker 안드로이드 구현
│  ├─ core-layout/                 :core-layout  ★ 순수 Kotlin (JVM)
│  ├─ epub/                        :epub         ★ 순수 Kotlin (JVM) + XmlPullParser
│  ├─ data/                        :data         Room · DataStore · SAF · 페이지 캐시 I/O
│  └─ benchmark/                   :benchmark    Macrobenchmark
└─ corpus/                         ← 테스트용 EPUB (저장소에 넣지 않고 경로만 기록)
```

### 2.1 모듈 의존 규칙 (강제)

```
:app → :ui → :ui-design
        ↓
   :reader-render → :text-platform → :core-layout ← :epub
        ↓                                 ↑
      :data ─────────────────────────────┘
```

| 모듈 | 허용 | **금지** |
|---|---|---|
| `:core-layout` | kotlin-stdlib **만** | `android.*` 일체. `kotlinx-coroutines` 도 금지(순수 함수) |
| `:epub` | kotlin-stdlib, `org.xmlpull` | `android.*` (단, `XmlPullParser` 인터페이스는 허용 — 구현은 주입) |
| `:text-platform` | `android.graphics.*` | Compose, Room |
| `:reader-render` | Compose, `:text-platform` | Room, 네트워크 |
| `:ui-design` | Compose, Material3 | 도메인 모델, Room |

> **`:core-layout`과 `:epub`에 Android 의존이 들어가는 순간 이 프로젝트의 핵심 이점이 사라진다.** Gradle에서 `android.*` 임포트를 검출해 빌드 실패시키는 검사를 S1에 넣는다.

---

## 3. 데이터 파이프라인

```
SAF Uri
  └→ ZipReader            랜덤 액세스 ZIP (중앙 디렉터리 파싱, 엔트리 지연 팽창)
      └→ ContainerParser  META-INF/container.xml → rootfile 경로
          └→ OpfParser    content.opf → BookMeta · Manifest · Spine
              └→ TocParser  toc.ncx (EPUB2) | nav.xhtml (EPUB3) → Toc
                  ↓ [Room에 1회 저장]
  [챕터 열람 시]
  ZipReader.open(spine[i].href)
      └→ ChapterParser    XmlPullParser SAX → InlineRun 스트림 + 정규화 텍스트
          └→ StyleResolver  태그 기본값 + CSS 서브셋 + 사용자 설정 → BlockStyle
              └→ Block 스트림   Text(runs) | Image | Rule
                  └→ Paginator   LineBreaker + TextMeasurer + RenderSpec
                      └→ Page 스트림
                          └→ PageStore  s<i>.txt / s<i>.run / s<i>.idx 기록
                              ↓ [이후 열람은 여기서 시작]
                          PageStore.load(page) → PageRenderer → Canvas
```

**핵심**: 두 번째 열람부터는 파이프라인 상단이 전부 생략되고 `PageStore.load` → `draw`만 남는다. 이것이 페이지 넘김 16ms의 근거다.

---

## 4. 핵심 인터페이스 (확정)

### 4.1 `:core-layout` — 순수 Kotlin

```kotlin
/** 글자 폭 측정. 유일한 플랫폼 의존 지점이며, 테스트에서는 FakeMeasurer로 대체한다. */
interface TextMeasurer {
    fun advance(text: CharSequence, start: Int, end: Int, style: TextStyle): Float
    fun lineHeight(style: TextStyle): Float
    fun ascent(style: TextStyle): Float
    fun spaceWidth(style: TextStyle): Float
}

/** 줄바꿈 정책. 교체 가능한 전략. */
interface LineBreaker {
    fun breakLines(
        paragraph: Paragraph,        // 인라인 런 + 블록 스타일
        widthPx: Float,
        firstLineIndentPx: Float,
        measurer: TextMeasurer,
    ): List<LaidLine>                // 각 줄의 런 배치(x 좌표 포함)
}

/** 줄 → 페이지 패킹. 항상 순수 함수. */
class Paginator(
    private val lineBreaker: LineBreaker,
    private val measurer: TextMeasurer,
) {
    fun paginate(blocks: Sequence<Block>, spec: RenderSpec): Sequence<Page>
}

/** 조판에 영향을 주는 모든 값. 이 해시가 캐시 키다. */
data class RenderSpec(
    val fontFamilyId: String, val fontSizePx: Float, val lineHeightMul: Float,
    val marginPx: Insets, val alignment: Alignment,
    val paragraphIndent: Boolean, val extraParagraphSpacing: Boolean,
    val embeddedStyle: Boolean, val hyphenation: Boolean,
    val characterWrap: Boolean, val imagesEnabled: Boolean,
    val viewportW: Int, val viewportH: Int,
) { val hash: String get() = /* 안정적 해시 */ }
```

### 4.2 `:epub`

```kotlin
interface BookSource {                       // EPUB / TXT / MD … 확장점
    suspend fun metadata(): BookMeta
    suspend fun spine(): List<SpineItem>
    suspend fun toc(): List<TocEntry>
    suspend fun openChapter(index: Int): java.io.Reader
    suspend fun openResource(href: String): java.io.InputStream?
}
```

### 4.3 `:data` — 점진적 조판

```kotlin
sealed interface PaginateEvent {
    data class PageReady(val index: Int) : PaginateEvent
    data class Progress(val built: Int, val estimatedTotal: Int) : PaginateEvent
    data object Complete : PaginateEvent
}

interface ChapterPager {
    fun build(spine: Int, spec: RenderSpec): Flow<PaginateEvent>
    suspend fun page(spine: Int, page: Int): Page
    suspend fun pageForOffset(spine: Int, charOffset: Int): Int
    suspend fun offsetForPage(spine: Int, page: Int): Int
    suspend fun suspendBuild()               // 앱 백그라운드 진입 시 부분 결과 보존
}
```

---

## 5. 페이지 캐시 포맷 (v1 초안 — S2에서 확정)

```
<filesDir>/pages/<bookId>/<specHash>/
   s<N>.txt    챕터 정규화 텍스트 (UTF-8, 연속 공백 축약 후)
   s<N>.run    고정 길이 Run 레코드 배열
   s<N>.idx    헤더 + 고정 길이 PageEntry 배열
```

| 파일 | 레코드 | 필드 |
|---|---|---|
| `.idx` 헤더 (32B) | — | magic `CPP1` · version u16 · pageCount u16 · complete u8 · runCount u32 · bytesConsumed u32 · totalBytes u32 |
| `.idx` PageEntry (16B) | 페이지당 1개 | runStart u32 · runCount u16 · imgStart u16 · imgCount u8 · flags u8 · charOffset u32 |
| `.run` Run (16B) | 런당 1개 | charStart u32 · charEnd u32 · x i16(고정소수) · y i16 · styleId u8 · flags u8 |

**설계 의도 3가지**
1. **텍스트를 한 번만 저장하고 런은 인덱스만 갖는다.** `.txt` 하나가 조판·**텍스트 선택**·**검색**·**TTS**·진도 오프셋의 공통 원천이 된다.
2. **고정 길이 레코드** → 페이지 N 읽기 = `.idx`에서 16B 읽고 `.run`에서 슬라이스 읽기. 파싱 0.
3. `complete=0`이면 **부분 캐시**(중단된 빌드). 그 지점까지는 즉시 읽고 뒤는 백그라운드로 이어 만든다.

**무효화**: `specHash` 디렉터리 단위. 설정이 바뀌면 새 디렉터리가 생기고, 오래된 것은 LRU로 정리(기본 상한 200MB).

---

## 6. CSS 서브셋 (v1 확정 범위)

### 지원
| 분류 | 속성 |
|---|---|
| 텍스트 | `text-align`, `text-indent`, `text-decoration`(underline/line-through), `direction` |
| 폰트 | `font-style`(italic), `font-weight`(bold), `font-size`(em/rem/%/pt/px) |
| 박스 | `margin`(4방향), `padding`(4방향), `display:none`, `page-break-before/after` |
| 인라인 | `vertical-align`(super/sub) |
| 이미지 | `width`, `height` |

### 셀렉터
타입(`p`) · 클래스(`.x`) · ID(`#x`) · 후손(`div p`) · 그룹(`,`) — **이것만**. 특이도는 표준 규칙.

### 무시 (경고 없이 통과)
`float`, `position`, `color`/`background`(테마가 결정), `border`, `line-height`(사용자 설정이 결정), `@media`, 의사 클래스/요소, 속성 셀렉터, `table` 고급 속성

> 무시 목록을 **명시적으로** 둔다. "지원 안 하는 게 뭔지 모르는 상태"가 가장 비싸다.

---

## 7. 설정 스키마 (v1 전체 항목)

설정은 **데이터로 선언**하고 UI·DataStore·`RenderSpec` 해시가 자동 파생된다.

```kotlin
sealed interface Setting {
    data class Bool (val key:String, val cat:Cat, val title:Int, val default:Boolean): Setting
    data class Enum (val key:String, val cat:Cat, val title:Int, val values:List<Int>, val default:Int): Setting
    data class Range(val key:String, val cat:Cat, val title:Int, val min:Int, val max:Int, val step:Int, val default:Int): Setting
}
```

| 카테고리 | 항목 |
|---|---|
| **Reader** (→ `RenderSpec`) | 글꼴, 글자 크기(12–28sp), 줄 간격(1.0/1.2/1.4), 여백(5–40dp), 문단 정렬(양쪽/왼쪽/가운데/책 스타일), 문단 들여쓰기, 문단 간격, 내장 스타일 사용, 하이픈, 이미지 표시, **글자 단위 줄바꿈**(S6) |
| **Display** | 테마(시스템/라이트/다크/세피아), 테마 변형(Lyra/Classic), 상태바 구성(제목·페이지·퍼센트·배터리·진행바 개별 토글), 화면 항상 켬, 전체화면 |
| **Controls** | 탭 영역(좌우/중앙), 스와이프 넘김, 볼륨키 넘김, 페이지 전환(슬라이드/페이드/없음) |
| **System** | 라이브러리 폴더, 캐시 정리, 언어, 백업/복원 |

**설정 1개 추가 = 위 리스트에 1줄.** 화면·저장·캐시 무효화가 자동 반영된다.

---

## 8. GUI 컴포넌트 명세 (CrossPoint 이식)

| # | 컴포넌트 | 구성 | 주요 토큰 |
|---|---|---|---|
| 1 | `CpHeader` | 제목 + 선택적 부제 | `headerHeight`, `topPadding` |
| 2 | `CpList` | 아이콘·제목·부제·우측값·비활성 행 + 스크롤바 | `listRowHeight` 30, `listWithSubtitleRowHeight` 50, `scrollBarWidth` 4 |
| 3 | `CpButtonMenu` | 홈의 아이콘+라벨 세로 메뉴 | `menuRowHeight` 45, `menuSpacing` 8 |
| 4 | `CpCoverTile` | 최근 책 커버 타일 | `homeCoverHeight` 400, `homeTopPadding` 40 |
| 5 | `CpTabBar` | 목차/북마크 전환 | `tabBarHeight` 50, `tabSpacing` 10 |
| 6 | `CpStatusBar` | 제목·페이지·퍼센트·배터리·진행바 | `statusBarHorizontalMargin` 5, `progressBarHeight` 16 |
| 7 | `CpProgressBar` | 굵기 3단계 | `progressBarHeight` |
| 8 | `CpPopup` | 중앙 프레임 + 진행 | `popupMarginX/Y` 15, `popupFrameThickness` 2 |
| 9 | `CpOptionPopup` | 제목 + 구분선 + 선택 목록 | `optionPopupInnerPadding` 16 |
| 10 | `CpTextField` | 검색·입력 | `textFieldHorizontalPadding` 6 |

**각색 규칙 (전부 적용)**
1. 원본 수치는 480×800 e-ink 기준 → **비율 재해석**, 단 **터치 타깃 48dp 하한 강제**
2. 5단계 흑백 디더 → Light / Dark / Sepia 팔레트
3. 1bit 아이콘 16종 → 벡터 재작성
4. `drawButtonHints` / `drawSideButtonHints` **제외** (물리 버튼 없음)
5. **화면 구성(무엇이 어디 있는가)은 원본 그대로 유지** ← 이것이 계승 대상

---

## 9. 스프린트별 세부 작업

### S1 — 기반 (2주)

| # | 작업 | 산출물 |
|---|---|---|
| 1.1 | Gradle 멀티모듈 + version catalog + **모듈 의존 규칙 검사 태스크** | `./gradlew check` 에서 `:core-layout`의 `android.*` 임포트 시 실패 |
| 1.2 | `ZipReader` — SAF `Uri` 랜덤 액세스, 중앙 디렉터리 파싱, 엔트리 지연 팽창 | 단위 테스트 |
| 1.3 | `ContainerParser` + `OpfParser` — 메타데이터·manifest·spine | 〃 |
| 1.4 | `TocParser` — ncx / nav 양쪽 | 〃 |
| 1.5 | `EpubSource : BookSource` 통합 | 〃 |
| 1.6 | Room 스키마 (`books` `spine` `toc` `progress` `bookmarks` `stats`) + DAO + 마이그레이션 테스트 | |
| 1.7 | DataStore(Proto) 설정 + §7 스키마 | |
| 1.8 | SAF 폴더 등록 · 재귀 스캔 · 영속 권한 | |
| 1.9 | `CpTheme` 토큰 골격 (`CpMetrics`, 팔레트 3종) | |
| 1.10 | **테스트 코퍼스 20권 선정** + 파서 골든 테스트 | 코퍼스 목록 문서 |

**완료 판정**: 기기 없이 `./gradlew :epub:test` 로 코퍼스 20권의 제목·저자·spine 수·TOC 항목 수가 **골든과 100% 일치**.

### S2 — 조판 코어 (3주)

| # | 작업 | 산출물 |
|---|---|---|
| 2.1 | `ChapterParser` — XmlPullParser SAX → `InlineRun` 스트림 + 정규화 텍스트(`.txt`) | |
| 2.2 | CSS 파서 (§6 서브셋) + 셀렉터 매칭 + 특이도 | 단위 테스트 |
| 2.3 | `StyleResolver` — 태그 기본값 + CSS + 사용자 설정 → `BlockStyle`(마진 병합 포함) | 〃 |
| 2.4 | `Block` sealed 모델 (`Text`/`Image`/`Rule`) | |
| 2.5 | `TextMeasurer` 인터페이스 + **`FakeMeasurer`**(모든 글자 10f, 공백 5f → 완전 결정적) | |
| 2.6 | `PlatformLineBreaker` (`StaticLayout` 위임, `:text-platform`) | |
| 2.7 | `Paginator` — 줄→페이지 패킹, 이미지 배치, 페이지 브레이크, 마진 상쇄 | |
| 2.8 | `PageStore` — §5 포맷 읽기/쓰기 + 부분 캐시 | |
| 2.9 | **골든 테스트 하네스** — "챕터 → 페이지별 시작 char offset 목록" 스냅샷 | |

**완료 판정**: `FakeMeasurer`로 코퍼스 20권 전체를 헤드리스 조판하고, 페이지 경계 스냅샷이 골든과 일치. 실행 시간 **전체 < 30초**.

### S3 — 렌더링·성능 (3주)

| # | 작업 |
|---|---|
| 3.1 | `PageRenderer` — `.run` 레코드 → `Canvas.drawText`. 밑줄·취소선·첨자 |
| 3.2 | `ReaderSurface` — Compose `Canvas` + 탭 영역/스와이프/볼륨키 제스처 |
| 3.3 | 페이지 전환 — 오프스크린 비트맵 기반 슬라이드/페이드/없음 |
| 3.4 | `ChapterPager` — 점진적 빌드 `Flow`, 백그라운드 진행, 중단·재개(부분 캐시) |
| 3.5 | 진도 — **char offset 기준** 저장/복원. 회전·설정 변경 후 같은 글자 복귀 |
| 3.6 | 이미지 블록 — `inSampleSize` 뷰포트 맞춤 디코드 + 디스크 캐시 |
| 3.7 | `:benchmark` Macrobenchmark + 예산 검사 |

**완료 판정**: 실기기에서 **페이지 넘김 p95 ≤ 16ms**, 책 열기(캐시) ≤ 300ms, 리더 RSS ≤ 120MB. 화면 회전 후 진도 오차 0자.

> 3.7을 **마지막이 아니라 S3 시작에** 붙인다. 성능 계측을 나중에 붙이면 원인 추적이 불가능하다.

### S4 — 화면 완성 (2주)

| # | 작업 |
|---|---|
| 4.1 | GUI 컴포넌트 10종 (§8) |
| 4.2 | 홈 / 라이브러리 / 파일 브라우저 |
| 4.3 | 리더 화면 + 상태바 + 메뉴 시트 |
| 4.4 | 목차 (탭바) |
| 4.5 | 설정 화면 — §7 스키마에서 자동 생성 |
| 4.6 | 테마 Light / Dark / Sepia |
| 4.7 | 실기기 완독 테스트 (한국어 EPUB 20권) |

**완료 판정**: **1차 배포 가능.** 코퍼스 20권을 처음부터 끝까지 읽어 크래시·레이아웃 붕괴·진도 유실 0.

### S5~S8 (요약)

| S | 기간 | 내용 |
|---|---|---|
| S5 | 3주 | 북마크 · 텍스트 선택→복사/공유 · 각주 · 퍼센트 이동 · 읽기 시간 · 자동 넘김 · 책 끝 화면 |
| S6 | 2주 | **`KoreanLineBreaker`** — 글자 단위 줄바꿈, 어절 간격 1.0–1.5x, U+3000 들여쓰기 |
| S7 | 2주 | 테마 변형 · 태블릿 2단 · TalkBack · i18n(ko/en) · 예산 CI 편입 |
| S8+ | — | (선택) TTS · 하이라이트 · KOSync · OPDS · TXT/MD |

**1차 배포 = S4 종료 시점, 누적 10주.**

---

## 10. 테스트 전략

| 층 | 방식 | 실행 시점 |
|---|---|---|
| 파서 | 코퍼스 20권 골든 (메타·spine·TOC) | 매 PR, JVM |
| CSS·스타일 | 단위 테스트 (셀렉터·특이도·마진 병합) | 매 PR, JVM |
| **조판** | **`FakeMeasurer` 골든** — 페이지 경계 char offset 스냅샷 | 매 PR, JVM, <30초 |
| 캐시 포맷 | 왕복 테스트 + 부분 캐시 재개 | 매 PR, JVM |
| GUI 컴포넌트 | 스크린샷 테스트 (Roborazzi — **테스트 전용, APK 미포함**) | 매 PR |
| 성능 | Macrobenchmark | 실기기 수동 + 주간 |
| 통합 | 실기기 완독 | 스프린트 종료 |

**`FakeMeasurer`가 이 전략의 핵심.** 모든 글자 폭을 10f로 고정하면 조판이 완전히 결정적이 되어, 줄바꿈 로직 변경의 영향을 **골든 diff로 눈으로 확인**할 수 있다. 기기도 폰트도 필요 없다.

---

## 11. CI

| 트리거 | 실행 | 게이트 |
|---|---|---|
| PR | ktlint · detekt · **모듈 의존 규칙 검사** · JVM 단위 테스트 · 골든 · 스크린샷 | 전부 통과 필수 |
| main 머지 | 위 + 디버그 APK 빌드 + **APK 크기 보고** | 크기 회귀 시 경고 |
| 주간 | Macrobenchmark (고정 실기기) | 예산 초과 시 이슈 자동 생성 |

> 에뮬레이터 성능 수치는 **상대 비교용**으로만 쓴다. §1의 절대 예산은 고정 실기기에서만 판정한다.

---

## 12. 착수 전 결정 필요 (3건, 전부 가벼움)

| # | 항목 | 비고 |
|---|---|---|
| **B1** | **앱 이름 / 패키지명** | 예: `kr.kgcaudit.reader`. Play 등록 후 변경 불가 |
| **B2** | **폰트 번들 여부** | ① 번들 안 함(시스템 폰트, APK 최소) ② KoPub 바탕 + Pretendard 번들(+6–10MB, 원본 조판 재현) ③ 최초 실행 시 다운로드. **권장 ②** — 다만 배포 전 각 서체의 임베딩·재배포 조항 확인 필요 |
| **B3** | **테스트 코퍼스 20권 확보 경로** | 보유 EPUB / 공공 도메인(한국어 위키문헌·구텐베르크) 혼합. S1.10의 전제 |

그 외는 §1에서 전부 확정됐다.

---

## 13. 착수 체크리스트

- [ ] B1·B2·B3 결정
- [ ] `android/` Gradle 골격 생성 (S1.1)
- [ ] 모듈 의존 규칙 검사 태스크 작성 — **가장 먼저**. 나중에 넣으면 이미 오염돼 있다
- [ ] 코퍼스 20권 배치 + 골든 생성
- [ ] CI 워크플로 (PR 게이트)
- [ ] S1 착수

