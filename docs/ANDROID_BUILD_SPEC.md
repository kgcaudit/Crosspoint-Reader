# 안드로이드 전자책 뷰어 — 세부 시행계획 (빌드 사양서)

**rev.2 (2026-09-22)** — 제품 정의 정정 반영. rev.1을 대체한다.

---

## 0. rev.1에서 무엇이 틀렸나

사용자 정의: **"EPUB · TXT · PDF를 간편하게 읽고, 책갈피를 넣는 등 CrossPoint 주요기능 중심."**

rev.1은 여기서 두 군데가 어긋났다.

| # | 어긋남 | 원인 | 정정 |
|---|---|---|---|
| **1** | **PDF를 범위에서 제외했다** | CrossPoint `SCOPE.md`의 "PDF out-of-scope"를 그대로 승계했다. 그러나 **그 근거는 e-ink 전용이다** — *"고정 레이아웃이라 페이지를 이미지로 띄워야 하고, e-ink에서 끊임없는 패닝·줌은 나쁜 경험"*. **휴대폰·태블릿에서는 핀치 줌이 자연스러워 이 근거가 성립하지 않는다.** 원본의 제약을 제품 요구로 오인했다 | **PDF를 1급 지원으로 편입** |
| **2** | **조판 엔진을 먼저, 읽기 기능을 나중에 배치했다** | "가볍게"를 성능 문제로만 읽고 "간편하게"를 놓쳤다. 책갈피가 S5(13주차), 쓸 수 있는 앱이 S4(10주차) | **순서를 뒤집는다. 2주차에 설치해서 쓸 수 있는 앱, 책갈피는 첫 스프린트** |

**유지되는 것**: 아키텍처 결정(네이티브 조판 · WebView 미사용 · 디스크 페이지 캐시 · 성능 예산 · 모듈 규칙 · 수동 DI). PDF가 들어와도 이 결정은 흔들리지 않는다 — **PDF도 WebView를 쓰지 않는다.**

---

## 1. 제품 정의

> **EPUB · TXT · PDF를 폴더에서 바로 열어 읽고, 책갈피와 이어읽기가 확실한 가벼운 뷰어.**
> 화면 구성은 CrossPoint를 따른다.

### 1.1 핵심 기능 (v1 필수)

| 분류 | 기능 |
|---|---|
| 읽기 | EPUB · TXT · PDF 열기 / 페이지 넘김 / 화면 회전 |
| **책갈피** | 추가 · 목록 · 이동 · 삭제 — **3개 포맷 전부** |
| 이어읽기 | 진도 자동 저장 / 최근 읽은 책 |
| 탐색 | 폴더 라이브러리 / 목차 이동 / 진도 퍼센트 이동 |
| 설정 | 글꼴 · 크기 · 줄 간격 · 여백 · 정렬 (EPUB·TXT) / 테마 |
| 표시 | 상태바 (제목 · 페이지 · 퍼센트 · 배터리 · 진행바) |

### 1.2 v2 이후

각주 이동 · 읽기 시간 · 자동 페이지 넘김 · 한국어 글자 단위 줄바꿈 · 텍스트 선택/사전 · 하이픈 · 스크린샷 · 태블릿 2단 · 접근성 · KOSync · OPDS

### 1.3 비범위

고정 레이아웃 EPUB(만화) · 미디어 오버레이 · 쓰기/주석 편집 · 클라우드 동기화(v1)

---

## 2. 두 개의 파이프라인, 하나의 셸

PDF 편입이 만드는 유일한 구조 변화다. **PDF는 텍스트 조판을 전혀 거치지 않는다.**

```
        ┌──────────── 공통 셸 (전체의 60%) ─────────────┐
        │ 라이브러리 · 책갈피 · 진도 · 최근 책 · 설정      │
        │ 리더 크롬 (상태바 · 메뉴 · 제스처 · 목차 · 테마)  │
        └──────────────────┬───────────────────────────┘
                           │
          ┌────────────────┴─────────────────┐
          │                                   │
   ReflowDocument                      FixedPageDocument
   EPUB · TXT                          PDF
   ─────────────────                   ─────────────────
   파싱 → 블록 → 조판                   PdfRenderer → Bitmap
   → 페이지 캐시 → Canvas               (뷰포트 해상도 · 줌은 타일)
   진도 = 글자 오프셋                    진도 = 페이지 번호 + 화면 위치
```

**핵심 설계**: 위치를 가리키는 타입을 sealed로 통일하면, 책갈피·진도·목차 이동이 **두 파이프라인에서 같은 코드로** 동작한다.

```kotlin
sealed interface Locator {
    data class Reflow(val spine: Int, val charOffset: Int) : Locator
    data class FixedPage(val page: Int, val xNorm: Float, val yNorm: Float) : Locator
}

data class Bookmark(val bookId: BookId, val locator: Locator,
                    val snippet: String?, val createdAt: Long)
```

이 하나로 책갈피 화면·진도 저장·"이어읽기"가 포맷과 무관해진다.

```kotlin
sealed interface Document {
    val meta: BookMeta
    suspend fun outline(): List<TocEntry>
}
interface ReflowDocument : Document {          // EPUB · TXT
    suspend fun spine(): List<SpineItem>
    suspend fun openChapter(i: Int): java.io.Reader
    suspend fun openResource(href: String): java.io.InputStream?
}
interface FixedPageDocument : Document {       // PDF
    val pageCount: Int
    suspend fun pageSize(i: Int): Size
    suspend fun render(i: Int, target: Rect, scale: Float): Bitmap
}
```

---

## 3. PDF 구현 방침

| 항목 | 선택 |
|---|---|
| 렌더러 | **`android.graphics.pdf.PdfRenderer`** (플랫폼 내장, **APK 0 바이트**) |
| 열기 | SAF `Uri` → `openFileDescriptor` → `PdfRenderer` |
| 렌더 해상도 | **뷰포트 크기에 맞춰서만 렌더.** 원본 해상도 비트맵 금지 |
| 줌 | **타일 렌더링** — 확대 시 보이는 영역만 고해상도로 다시 렌더. 전체 페이지를 고해상도로 올리지 않는다 |
| 책갈피 | 페이지 번호 + 화면 내 정규화 좌표 |
| 진도 | 〃 |

**`PdfRenderer`의 한계 (v1에서 수용)**: PDF 내장 목차(outline)·텍스트 추출·검색·암호 PDF를 지원하지 않는다. → **PDF 목차·검색은 v1에 없다.**

→ 필요해지면 `FixedPageDocument` 구현만 **Pdfium 계열로 교체**하면 된다(+3~6MB/ABI). 인터페이스가 이미 그 자리에 있으므로 위층은 무수정. **결정 P1** 참조.

---

## 4. 확정 사항

| 항목 | 확정값 |
|---|---|
| 지원 포맷 | **EPUB 2/3 · TXT · PDF** |
| 본문 렌더링 | Canvas + Paint 직접 그리기. **WebView 미사용** |
| PDF 렌더링 | `PdfRenderer` (플랫폼) |
| 페이지네이션(리플로우) | 챕터 단위 사전 조판 → 디스크 캐시 |
| 줄바꿈 | `LineBreaker` 전략. v1 = `PlatformLineBreaker`(StaticLayout), v2 = `KoreanLineBreaker` |
| UI | Compose + Material 3 |
| DI | 수동 (`AppContainer`) |
| 저장 | Room + DataStore + 자체 바이너리 페이지 캐시 |
| 파일 접근 | SAF 폴더 등록 |
| minSdk / target / compile | 26 / 35 / 36 |
| 라이선스 | MIT |

### 성능 예산 (CI 게이트)

| 지표 | 예산 |
|---|---:|
| 페이지 넘김 p95 (EPUB·TXT) | ≤ 16 ms |
| 페이지 넘김 p95 (PDF) | ≤ 100 ms (렌더 필요) |
| 책 열기 (캐시 적중) | ≤ 300 ms |
| 콜드 스타트 → 라이브러리 | ≤ 500 ms |
| 리더 RSS | ≤ 120 MB |
| APK (폰트 제외) | ≤ 12 MB |

---

## 5. 모듈 구조

```
android/
├─ app/              :app            Application · AppContainer · Navigation
├─ ui/               :ui             화면
├─ ui-design/        :ui-design      CpTheme 토큰 + 컴포넌트
├─ reader-reflow/    :reader-reflow  EPUB·TXT 리더 화면 · Canvas 그리기 · 제스처
├─ reader-pdf/       :reader-pdf     PDF 리더 화면 · PdfRenderer · 타일 줌
├─ text-platform/    :text-platform  TextMeasurer · PlatformLineBreaker
├─ core-layout/      :core-layout    ★ 순수 Kotlin — 블록 · 조판 · 페이지 캐시 포맷
├─ document/         :document       ★ 순수 Kotlin — Document/Locator 모델 · EPUB·TXT 파서
├─ data/             :data           Room · DataStore · SAF · 캐시 I/O
└─ benchmark/        :benchmark      Macrobenchmark
```

**의존 규칙 (빌드 시 강제)**

| 모듈 | 허용 | 금지 |
|---|---|---|
| `:core-layout` | kotlin-stdlib **만** | `android.*` 일체 |
| `:document` | kotlin-stdlib, `XmlPullParser` 인터페이스 | `android.*` 구현체 |
| `:reader-pdf` | `android.graphics.pdf.*` | `:core-layout` (PDF는 조판하지 않는다) |

> `:core-layout` / `:document`에 `android.*` 임포트가 들어가면 빌드를 실패시킨다. **R1의 첫 작업.**

---

## 6. 스프린트 — "2주차에 쓸 수 있는 앱"

각 스프린트가 **설치해서 쓸 수 있는 상태**로 끝난다.

### R1 — 공통 셸 + PDF (2주) → **설치 가능**

| # | 작업 |
|---|---|
| 1.1 | Gradle 멀티모듈 + version catalog + **의존 규칙 검사 태스크** |
| 1.2 | SAF 폴더 등록 · 영속 권한 · 재귀 스캔 (`.epub/.txt/.pdf`) |
| 1.3 | Room: `books` `progress` `bookmarks` `recent` + DAO |
| 1.4 | `Document` / `Locator` 모델 · `AppContainer` |
| 1.5 | GUI 기본 4종: `CpHeader` `CpList` `CpStatusBar` `CpPopup` |
| 1.6 | 라이브러리 화면 (폴더 탐색 · 파일 아이콘 · 최근 책) |
| 1.7 | **PDF 리더**: `PdfRenderer` · 뷰포트 렌더 · 페이지 이동 · 핀치 줌(타일) |
| 1.8 | **책갈피 추가/목록/이동/삭제** · 진도 저장 · 이어읽기 |
| 1.9 | 리더 크롬: 상태바 · 메뉴 시트 · 탭/스와이프 제스처 |

**완료 판정**: 실기기에 설치해 **PDF를 폴더에서 열고, 읽고, 책갈피를 찍고, 앱을 껐다 켜도 같은 자리로 돌아온다.**

### R2 — TXT 리더 + 조판 엔진 (2주) → **설치 가능**

| # | 작업 |
|---|---|
| 2.1 | `TxtDocument` — **인코딩 감지 (UTF-8 / UTF-16 / EUC-KR·CP949)**. 한국어 TXT는 EUC-KR이 흔하다 |
| 2.2 | `:core-layout` 블록 모델 (문단 · 빈 줄) |
| 2.3 | `TextMeasurer` 인터페이스 + **`FakeMeasurer`** (모든 글자 10f → 결정적 테스트) |
| 2.4 | `PlatformLineBreaker` (`StaticLayout` 위임) |
| 2.5 | `Paginator` — 줄 → 페이지 패킹 |
| 2.6 | `PageStore` — 페이지 캐시 포맷 (§7) |
| 2.7 | 리플로우 리더 화면 · Canvas 그리기 |
| 2.8 | 리더 설정: 글꼴 · 크기 · 줄 간격 · 여백 · 정렬 |
| 2.9 | 골든 테스트 하네스 |

**완료 판정**: TXT를 읽고 책갈피·이어읽기가 동작. **조판 파이프라인이 CSS·ZIP 없이 검증됨.**

> TXT를 EPUB보다 먼저 하는 이유: 조판 엔진을 **ZIP·XML·CSS 없이** 단독 검증할 수 있다. 여기서 버그를 다 잡고 EPUB에 들어간다.

### R3 — EPUB (3주) → **설치 가능**

| # | 작업 |
|---|---|
| 3.1 | `ZipReader` — SAF `Uri` 랜덤 액세스 |
| 3.2 | `ContainerParser` · `OpfParser` · `TocParser`(ncx/nav) |
| 3.3 | `ChapterParser` — XmlPullParser SAX → 인라인 런 + 정규화 텍스트 |
| 3.4 | CSS **최소** 서브셋 (§8) + `StyleResolver` |
| 3.5 | 점진적 조판 (`Flow`) · 부분 캐시 · 중단/재개 |
| 3.6 | 목차 화면 · 이미지 블록 (`inSampleSize`) |
| 3.7 | 진도 = **글자 오프셋** — 회전·설정 변경 후에도 같은 글자 복귀 |

**완료 판정**: 코퍼스 EPUB 20권을 크래시·레이아웃 붕괴 없이 완독.

### R4 — 완성도 + 배포 (2주)

| # | 작업 |
|---|---|
| 4.1 | GUI 나머지 컴포넌트 (`CpButtonMenu` `CpCoverTile` `CpTabBar` `CpProgressBar` `CpOptionPopup` `CpTextField`) |
| 4.2 | 홈 화면 (최근 책 커버 타일) · 커버 썸네일 |
| 4.3 | 진도 퍼센트 이동 · 화면 회전 · 페이지 전환 애니메이션 |
| 4.4 | 테마 Light / Dark / Sepia |
| 4.5 | 설정 화면 (선언적 스키마 자동 생성) |
| 4.6 | Macrobenchmark + 예산 검증 · APK 크기 확인 |
| 4.7 | 실기기 통합 테스트 (3개 포맷) |

**완료 판정**: **1차 배포.** §4 성능 예산 전 항목 통과.

### 이후

| R | 기간 | 내용 |
|---|---|---|
| R5 | 2주 | 각주 이동 · 읽기 시간 · 자동 페이지 넘김 · 텍스트 선택→복사 |
| R6 | 2주 | **`KoreanLineBreaker`** (글자 단위 줄바꿈 · 어절 간격 1.0–1.5x · U+3000 들여쓰기) |
| R7 | 2주 | 태블릿 2단 · TalkBack · i18n · 하이픈 · 스크린샷 |
| R8+ | — | Pdfium 전환(PDF 목차·검색) · 사전 · KOSync · OPDS |

**누적: R1 2주(쓸 수 있는 앱) → R4 9주(1차 배포) → R7 15주(기능 완성)**

rev.1 대비: 쓸 수 있는 앱이 **10주 → 2주**, 1차 배포 **10주 → 9주**(PDF·TXT 포함).

---

## 6.5 현재 진행 상황

스프린트 순서(R1 셸·PDF → R2 TXT → R3 EPUB)는 **안드로이드 기기에서 쓸 수 있는 것**을
기준으로 짠 것이다. 실제 착수는 순수 Kotlin 코어부터 했다 — 이 작업 환경에
안드로이드 SDK 를 받을 수 없어서(§6.6), SDK 없이 검증되는 것을 먼저 끝내는 편이
낭비가 적기 때문이다. 그래서 R2·R3 의 **엔진 부분이 먼저 끝나 있고**, 화면은 전부 남았다.

| 층 | 모듈 | 상태 |
|---|---|---|
| 포맷 파싱 (EPUB zip·OPF·목차·TXT 인코딩) | `:document` | **완료** · 143 테스트 |
| 블록 모델 · 줄바꿈 · 조판 · 캐시 포맷 | `:core-layout` | **완료** |
| CSS 서브셋 · 태그 기본값 · 스타일 해석 (§8) | `:core-layout` | **완료** |
| `ChapterParser` (XHTML → 블록·앵커) · `TextChapter` (TXT) | `:core-layout` | **완료** |
| `ChapterLoader` (외부 CSS 포함 챕터 적재) | `:core-layout` | **완료** |
| `PageStore` (디스크 캐시 · 부분 캐시 · 정리) | `:core-layout` | **완료** |
| `BookLayout` (페이지 이동 · 위치 복원 · 진도) | `:core-layout` | **완료** |
| `ReadingSession` (책갈피 · 이어읽기) | `:core-layout` | **완료** · 합계 211 테스트 |
| 책갈피·진도 보관소 **인터페이스** | `:document` | **완료** (구현은 `:data`) |
| `TextMeasurer` 안드로이드 구현 (`Paint`) · 번들 글꼴 (B2) | `:text-platform` | **완료** · 12 테스트 (Robolectric 네이티브 그래픽스) |
| SAF 폴더 스캔 · Room 구현 | `:data` | 미착수 |
| PDF 렌더 (`PdfRenderer`) | `:reader-pdf` | 미착수 (결정 P1) |
| GUI 컴포넌트 · 라이브러리·리더 화면 | `:ui-design` `:ui` `:app` | 미착수 |

**지금 상태로 증명된 것**: EPUB/TXT 파일 바이트 → 챕터 → 블록 → 페이지 → 디스크 캐시
→ 글자 오프셋 위치 → 책갈피·이어읽기까지가 기기 없이 한 줄로 돌아간다.
글자 크기를 바꿔 재조판해도 읽던 글자로 돌아오는 것까지 테스트가 지킨다.

**남은 것의 성격**: 화면과 플랫폼 붙이기다. `TextMeasurer` 하나를 `android.graphics.Paint`
로 구현하면 조판 엔진이 그대로 기기에서 돈다 — 플랫폼 경계를 그 인터페이스 하나로
좁혀 둔 것이 여기서 값을 한다.

---

## 6.6 작업 환경 제약 (클라우드 세션)

> **해결됨(조건부)**: 클라우드 환경의 **Network access** 를 넓히면(또는 허용 도메인에
> `dl.google.com` 을 추가하면) SDK 를 받을 수 있다. 2026-09-23 세션에서 그렇게 열어
> `:text-platform` 을 붙였다(설치 순서와 Maven Central 429 우회는 `docs/HANDOFF.md` §2).
> 기본 정책에서는 아래와 같이 막힌다.

기본 네트워크 정책의 클라우드 세션은 **`dl.google.com` 에 나갈 수 없다**(프록시가
CONNECT 를 403 으로 막는다). 그래서 세션 안에서는

- 안드로이드 SDK / 커맨드라인 도구를 받을 수 없고,
- AGP 와 안드로이드 의존성(`google()` 저장소)을 받을 수 없다.

`settings.gradle.kts` 가 SDK 가 없으면 `:document` `:core-layout` 만 구성하도록 돼 있어
순수 Kotlin 코어는 그대로 빌드·테스트된다. **안드로이드 모듈은 안드로이드 스튜디오가
설치된 PC 에서 작업해야 한다.** 코드를 세션에서 써 둘 수는 있지만 컴파일로 검증할 수
없으므로, 검증 없이 쌓아 두는 양을 최소로 유지한다.

---

## 7. 페이지 캐시 포맷 (리플로우 전용) — **확정**

R2 에서 구현·검증됨(`PageCodec`). 초안에서 두 가지가 바뀌었다(§ 아래 "초안과 달라진 점").

```
<filesDir>/pages/<bookId>/<specHash>/
   s<N>.txt   챕터 정규화 텍스트 (UTF-8)
   s<N>.idx   머리말(32B) + 고정 24B 페이지 엔트리
   s<N>.run   고정 24B 런 레코드
   s<N>.obj   가변 길이 그림·구분선 덩이
```

| 레코드 | 크기 | 필드 |
|---|---:|---|
| `.idx` 머리말 | 32B | magic `CPP1` · version u16 · pageCount u16 · complete u8 · runCount u32 · imageCount u32 · ruleCount u32 · textLength u32 |
| `.idx` PageEntry | 24B | runStart u32 · runCount u16 · imageStart u16 · imageCount u8 · ruleCount u8 · ruleStart u16 · charStart u32 · charEnd u32 |
| `.run` Run | 24B | charStart u32 · charEnd u32 · x f32 · baselineY f32 · sizeScale f32 · styleFlags u8 |
| `.obj` | 가변 | 표식 u8 + (그림: 길이 u16 + href + x,y,w,h f32) / (구분선: x,y,w,thickness f32) |

리틀엔디안 정수 + IEEE 754 실수. 플랫폼에 무관하게 같은 바이트가 나오므로 JVM 에서
만든 캐시를 기기가 읽는다(테스트가 그 성질에 기댄다).

### 설계 의도

1. **텍스트는 한 번만 저장하고 런은 인덱스만 갖는다.** `.txt` 하나가 조판 · 책갈피
   스니펫 · 진도 오프셋 · (향후) 검색 · 텍스트 선택 · 낭독의 공통 원천이 된다.
2. **런은 고정 길이** → "페이지 N 읽기" = `.idx` 에서 24B 읽고 `.run` 을 잘라 오기.
   파싱이 없다. 이게 페이지 넘김 16ms 예산의 전제다.
3. **그림·구분선은 가변 길이 덩이로 분리.** href 길이가 제각각이라 고정 길이에 담을 수
   없고, 챕터당 몇 개뿐이라 한 번에 읽어도 싸다. 이렇게 나눠야 런 배열이 고정 길이를
   유지한다.
4. `complete=0` = **부분 캐시**. 그 지점까지 즉시 읽고 뒤는 배경에서 이어 만든다.
5. **버전이 다르거나 손상되면 읽지 않고 null** — 옛 캐시로 엉뚱한 화면을 그리는 것보다
   한 번 더 조판하는 게 낫다.

### 초안과 달라진 점

| | 초안 | 확정 | 이유 |
|---|---|---|---|
| 런 레코드 | 16B (x·y 를 i16) | **24B (x·y·sizeScale 을 f32)** | 양쪽정렬이 소수점 위치를 만든다. 반올림하면 글자가 조금씩 밀린다 |
| 그림·구분선 | PageEntry 에 인덱스만 | **별도 `.obj` 덩이** | href 가 가변 길이라 고정 레코드에 담을 수 없다 |

### 런 병합 (측정)

글자 단위 줄바꿈은 토큰을 **글자마다** 만든다. 서식이 같고 글자가 이어지며 양쪽정렬이
끼워 넣은 틈이 없으면 한 런으로 묶는다. 720×1280 · 18px · 양쪽정렬 한국어 본문 실측:

| | 병합 없음 | 병합 |
|---|---:|---:|
| 페이지당 런 | 880 (= 글자 수) | **40 (= 줄 수)** |
| 페이지당 캐시 | ≈ 21 KB | **≈ 1 KB** |
| 페이지당 `drawText` | 880 회 | **40 회** |

글자 단위 줄바꿈이 줄을 폭까지 채우므로 양쪽정렬이 벌릴 여유가 거의 없고, 그래서
병합이 끊기지 않는다 — 한국어 조판 선택이 성능에서도 이득이 되는 지점이다.
테스트가 "런 ≤ 줄 × 3" 을 고정한다.

**무효화**: `specHash`(조판 영향 설정 전부의 해시) 디렉터리 단위. LRU 상한 200MB.
**PDF 는 이 캐시를 쓰지 않는다.**

## 8. CSS 서브셋 (v1) — **구현됨**

"간편하게 읽기"가 목표이므로 최소로 시작하고 실책 코퍼스가 요구할 때만 늘린다.
구현은 `core-layout/.../layout/css/` 와 `layout/html/` 에 있다.

**지원 속성**

| 속성 | 받는 값 | 조판에 미치는 것 |
|---|---|---|
| `text-align` | left/start · right/end · center · justify | `BlockStyle.align` (상속) |
| `text-indent` | em · rem · % · px · pt · 0 | `BlockStyle.firstLineIndentEm` (상속) |
| `font-style` | italic · oblique · normal | `TextStyle.italic` |
| `font-weight` | bold(er) · normal · lighter · 100–900 (≥600 = 굵게) | `TextStyle.bold` |
| `font-size` | em · rem · % · px · pt · 키워드(xx-small…xx-large) | `TextStyle.sizeScale` (부모에 **곱한다**) |
| `text-decoration(-line)` | underline · line-through · none | `TextStyle.underline` / `strikethrough` |
| `vertical-align` | super · sub · baseline | `TextStyle.vertical` |
| `margin` / `margin-*` | 1~4값 축약형 포함 | 상하 = 블록 여백, 좌우 = 들여쓰기(**누적**) |
| `padding` / `padding-*` | 위와 같음 | `margin` 으로 접는다 — 배경·테두리를 안 그리므로 결과가 같다 |
| `display` | `none` | 내용을 통째로 버린다 |
| `page-break-before` / `break-before` | always · page · left · right · recto · verso | `BlockStyle.pageBreakBefore` |

**셀렉터**: 타입 · 클래스 · ID · 후손 · 그룹. 표준 특이도(id 1,000,000 / 클래스 1,000 / 타입 1).
`>` `+` `~` 는 **후손으로 낮추고**, 의사 클래스·속성 셀렉터는 떼어 내고 나머지로 맞힌다 —
규칙을 통째로 버리면 책 한 권의 서식이 사라지기 때문이다.

**캐스케이드 출처**: 태그 기본값(`TagDefaults`) → 책의 CSS → `style` 속성.
세 단계를 특이도 계산에 섞지 않고 차례로 덮어쓴다. 섞으면 태그 기본값 `h1`(특이도 1)이
책의 `*`(특이도 0)을 이겨 출처가 뒤집힌다.

**상속**: `text-align` · `text-indent` 와 인라인 서식은 상속, 좌우 여백은 누적.
그 밖의 속성은 요소별로만 적용한다.

**무시(경고 없이)**: `float` · `position` · `color`/`background` · `border` · `line-height`(사용자 설정이 맡는다) · `@media` · `@font-face` · `direction`(v2) · 표 고급 속성.
깨진 CSS(닫히지 않은 주석·블록, 값 없는 선언, 모르는 단위)는 그 부분만 버리고 계속 읽는다.

**알려진 한계**
- `<style>` 은 만나는 순간부터 적용된다(`<head>` 안이면 문제없다). 외부 CSS 는 호출자가 합쳐서 넘긴다.
- 글을 직접 담지 않는 바깥 블록(`div`·`blockquote`·`ul`)의 **위아래** 여백은 버린다. 좌우는 누적해 넘긴다.
- 표는 칸마다 한 문단으로 편다. 칸 사이 정렬은 맞지 않는다.
- `<br>` 은 여백 없는 문단 나눔으로 처리한다.

---

## 9. 테스트 전략

| 층 | 방식 | 실행 |
|---|---|---|
| 파서 (EPUB·TXT) | 코퍼스 골든 (메타·spine·TOC·인코딩) | 매 PR · JVM |
| **조판** | **`FakeMeasurer` 골든** — 페이지 경계 char offset 스냅샷 | 매 PR · JVM · <30초 |
| 캐시 포맷 | 왕복 + 부분 캐시 재개 | 매 PR · JVM |
| 책갈피·진도 | `Locator` 왕복 (두 파이프라인) | 매 PR |
| PDF | 샘플 PDF 10종 렌더 · 메모리 상한 | 실기기 |
| GUI | 스크린샷 (Roborazzi — 테스트 전용) | 매 PR |
| 성능 | Macrobenchmark (고정 실기기) | 주간 |

**코퍼스**: EPUB 20권 · TXT 10개(UTF-8/EUC-KR 혼합) · PDF 10개(텍스트·스캔·대용량 혼합)

---

## 10. 착수 전 결정 (4건)

| # | 항목 | 권장 |
|---|---|---|
| **P1** | **PDF 렌더러** — ① `PdfRenderer`(0바이트, 목차·검색 없음) ② Pdfium(+3~6MB/ABI, 목차·텍스트·검색·암호 PDF) | **①로 시작.** `FixedPageDocument` 뒤에 있어 나중에 교체 가능. PDF 목차·검색이 처음부터 필수면 ② |
| **B1** | 앱 이름 / 패키지명 | Play 등록 후 변경 불가 |
| **B2** | 폰트 번들 — ① 미번들 ② KoPub 바탕 + Pretendard(+6~10MB) ③ 최초 실행 시 다운로드 | **②** (배포 전 각 서체 임베딩·재배포 조항 확인 필요) |
| **B3** | 테스트 코퍼스 확보 경로 (EPUB 20 · TXT 10 · PDF 10) | 보유 파일 + 공공 도메인 |

---

## 11. 착수 체크리스트

- [ ] P1 · B1 · B2 · B3 결정
- [ ] `android/` Gradle 골격 (R1.1)
- [ ] **의존 규칙 검사 태스크 — 가장 먼저.** 나중에 넣으면 이미 오염돼 있다
- [ ] 코퍼스 배치
- [ ] CI PR 게이트
- [ ] R1 착수

