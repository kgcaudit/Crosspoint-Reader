# CrossPoint Reader → Android EPUB 뷰어 이식 계획서

**대상 원본**: `crosspoint-reader-ko 1.5.0-ko.3` (한국어 포크)
**작성일**: 2026-09-22
**상태**: 의사결정 대기 (§7 결정 항목 참조)

---

## 0. 한 장 요약

| 항목 | 결론 |
|---|---|
| 원본 정체 | ESP32-C3 e-ink **펌웨어**(C++17 / PlatformIO / Arduino / FreeRTOS). 안드로이드 앱이 아니라 380KB RAM 기기용 단말 펌웨어 |
| 재사용 가치가 높은 것 | **조판 알고리즘 사양**, **CSS 서브셋 규칙**, **디자인 토큰(ThemeMetrics)**, **화면·기능 구조**, **한국어 조판(글자 단위 줄바꿈·U+3000 들여쓰기)**, **UI 문자열 440여 개** |
| 재사용 가치가 없는 것 | 코드 그 자체. GfxRenderer(1bit 프레임버퍼), EpdFont(비트맵 폰트), 아레나 할당기, SD 캐시, HAL, FreeRTOS 액티비티 모델 — 전부 ESP32 제약에서 파생된 설계 |
| 권장 방식 | **C++ 직접 포팅(NDK) 아님.** 조판 엔진을 **Kotlin으로 재구현**하고, 디자인 시스템은 **1:1 토큰 이식** (§4 옵션 B) |
| 1차 릴리스 범위 | 로컬 EPUB 읽기 + 라이브러리 + 리더 설정 + 한국어 조판 (§6 M0~M2) |
| 예상 규모 | M0~M2(출시 가능 최소본) 약 **8~11주**, 전체 기능 동등 수준 약 **20~26주** (1인 기준) |

---

## 1. 원본 소스 분석

### 1.1 정체와 빌드 체계

- **플랫폼**: ESP32-C3(RISC-V 싱글코어, 사용 가능 RAM ≈ 380KB), Xteink X3/X4 e-ink 단말
- **빌드**: PlatformIO(`platformio.ini`, env: `default` / `gh_release` / `slim` / `sticky`), Arduino 프레임워크, `freeink-sdk` 서브모듈
- **언어**: C++17, `-fno-exceptions` (예외 없음 → OOM이 `abort()`로 직결되는 것이 설계 전반을 지배)
- **펌웨어 크기 예산**: 6.25MB OTA 파티션. 1.5.0-ko.3 기준 5.88MB, 여유 668KB — 이 예산 압박이 "테마를 SD로 빼자", "하이픈 사전을 SD로 빼자" 같은 로드맵 항목의 원인

### 1.2 코드 규모 (실측)

| 영역 | LOC | 비고 |
|---|---:|---|
| `lib/Epub` (조판 엔진, 생성 데이터 제외) | 14,471 | **핵심 자산** |
| `lib/Epub/.../hyphenation/generated` | 22,064 | 10개 언어 Liang 트라이 (생성물) |
| `src/activities/reader` | 8,153 | 리더 화면군 |
| `src/activities/settings` | 4,649 | 설정 화면군 |
| `src/network` | 3,975 | 웹서버/WebDAV/OTA |
| `lib/GfxRenderer` | 3,606 | e-ink 전용 (폐기 대상) |
| `src/components` (테마 4종 포함) | 3,237 | **디자인 시스템** |
| `src/util` | 2,542 | 사전/북마크/통계/QR |
| `lib/KOReaderSync` | 2,588 | 진도 동기화 |
| `src/activities/network` | 2,145 | Wi-Fi/Calibre/전송 |
| `lib/EpdFont` (코드만) | 2,611 | 비트맵 폰트 런타임 (폐기 대상) |
| `lib/EpdFont/builtinFonts` | 51MB | 내장 비트맵 폰트 데이터 (폐기 대상) |
| `lib/Xtc` | 1,468 | XTC 전용 포맷 |
| `lib/MiniBidi` | 1,408 | RTL bidi |
| 3rd-party (`expat` 15.4K, `miniz` 9.7K, `uzlib` 0.9K) | 26,080 | 안드로이드에서는 전부 플랫폼 API로 대체 |

**자체 코드 합계 ≈ 55,000 LOC** (3rd-party·생성 데이터 제외)

### 1.3 아키텍처 레이어

```
┌─────────────────────────────────────────────────────────┐
│ ActivityManager  (단일 FreeRTOS 렌더 태스크 + 액티비티 스택) │
│   startActivityForResult / setResult / finish           │
│   RenderLock(전역 뮤텍스)로 loop()↔render() 상태 보호      │
├─────────────────────────────────────────────────────────┤
│ Activities (약 45개)  ·  UITheme/BaseTheme (테마 4종)      │
│   Home / FileBrowser / Recent / EpubReader / Settings … │
├─────────────────────────────────────────────────────────┤
│ Epub 엔진: Epub → Section → Page → Block → ParsedText     │
│   CssParser · ChapterHtmlSlimParser(expat SAX)          │
│   Hyphenator(Liang) · MiniBidi · 이미지 디코더            │
├─────────────────────────────────────────────────────────┤
│ GfxRenderer (1bit/4레벨 디더 프레임버퍼, 스트립 렌더)        │
│ EpdFont / SdFontFamily (.epdfont 비트맵 폰트)             │
├─────────────────────────────────────────────────────────┤
│ HAL: Display · GPIO · Storage · PowerManager · Clock · Tilt│
└─────────────────────────────────────────────────────────┘
```

### 1.4 EPUB 렌더링 파이프라인 (이식의 핵심)

```
.epub (ZIP)
  └ META-INF/container.xml   → ContainerParser
      └ content.opf          → ContentOpfParser  (메타데이터·spine·manifest)
          ├ toc.ncx (EPUB2)  → TocNcxParser
          └ nav.xhtml (EPUB3)→ TocNavParser
                                    ↓  book.bin 으로 캐시
  CSS 파일 수집 → CssParser → css_rules.cache
                                    ↓
  챕터 XHTML → ChapterHtmlSlimParser (expat 스트리밍 SAX)
      · 태그별 기본 스타일 + CSS 규칙 병합 → CssStyle → BlockStyle
      · 인라인 텍스트를 ParsedText 토큰으로 누적 (CJK는 글자 단위 토큰)
      · <img> → ImageBlock,  <ruby> → ruby 텍스트,  각주 앵커 수집
                                    ↓
  ParsedText.layoutAndExtractLines()
      · 단어 단위 경로 : computeLineBreaks / computeHyphenatedLineBreaks
      · 글자 단위 경로 : layoutCharacterWrap  ← 한국어 기본값
      · 양쪽정렬 위치 계산 → TextBlock (평탄 아레나 1회 할당)
                                    ↓
  Page(요소 목록) → 직렬화 → sections/N.bin  (페이지 오프셋 LUT + 앵커맵)
                                    ↓
  Page.render(GfxRenderer) → 프레임버퍼 → e-ink 갱신
```

**주목할 설계 (안드로이드에서도 유효한 아이디어)**

1. **점진적 섹션 색인(incremental build)**: 큰 챕터의 첫 페이지를 즉시 보여주고 나머지는 백그라운드로 조판. `startBuild()` / `buildSomeMore(N)` / `suspendBuild()`. 앱을 나가도 부분 결과(`partial`)를 보존해 재진입이 즉시.
2. **콘텐츠 기준 진도 저장**: 페이지 번호가 아니라 **visible codepoint offset**으로 위치를 저장 → 폰트·여백·화면 크기가 바뀌어 재조판되어도 같은 글자로 복귀. 안드로이드는 화면 회전·분할화면이 일상이므로 **이 설계는 반드시 계승해야 함**.
3. **렌더 사양 키(ReaderRenderSpec)로 캐시 무효화**: 폰트/줄간격/여백/정렬/하이픈/뷰포트 등 조판에 영향 주는 전 필드가 캐시 키. 하나라도 다르면 재조판.

### 1.5 한국어 포크 고유 기능 (반드시 계승)

| 기능 | 내용 |
|---|---|
| **글자 단위 줄바꿈** (`characterWrap`, 기본 ON) | 공백이 아닌 임의 글자에서 줄바꿈. 어절 간격을 1.0x~1.5x 공백폭으로 유지하며 3단계(탐욕 채우기 → 여백 채우기 → 양쪽정렬 배분) 수행. `docs/character-wrap-algorithm.md`에 사양 문서화됨 |
| **글자 붙임 처리** | CJK는 한 글자 = 한 토큰. `wordNoSpaceBefore`/`wordContinues` 플래그로 음절 사이에 간격을 넣지 않고, 양쪽정렬 시에도 늘리지 않음. 이 처리를 빠뜨리면 문단 전체가 균일 자간으로 렌더됨 |
| **문단 들여쓰기** | CSS `text-indent`가 없을 때 첫 토큰 앞에 U+3000 삽입. 문단 간격 설정과 독립 |
| **폰트** | 기본 본문 KoPub 바탕, UI 폰트 Pretendard + 시스템 폰트 글리프 폴백(한자/가나) |
| **합성 볼드 / 자간 조절** | 비트맵 폰트에 볼드 자형이 없을 때 합성 |
| **줄 간격 1.00 / 1.20 / 1.40** | 한국어에 맞게 조정된 값 |
| **하이픈 게이팅** | 글자 단위 줄바꿈이 켜져 있으면 하이픈 무효화 (어절 경계가 없으므로) |
| **한국어 UI** | `lib/I18n/translations/korean.yaml` 518줄 |

### 1.6 디자인 시스템

**`ThemeMetrics`** — 80여 개 수치 토큰 하나의 구조체. 배터리 아이콘 크기, 헤더 높이, 리스트 행 높이, 탭바, 스크롤바, 홈 커버 높이, 버튼 힌트, 진행바, 키보드, 팝업(라운드 반경·프레임 두께·반전 여부), 옵션 팝업, 텍스트 필드까지 전부 여기 모여 있음.

**`BaseTheme`** — 그리기 API 표면이 곧 컴포넌트 목록:

`drawProgressBar` · `drawBattery(Left|Right)` · `drawButtonHints` · `drawSideButtonHints` · `drawList` · `drawHeader` · `drawSubHeader` · `drawTabBar` · `drawRecentBookCover` · `drawButtonMenu` · `drawPopup` · `drawOptionPopup` · `fillPopupProgress` · `drawStatusBar` · `drawHelpText` · `drawTextField`

**테마 4종**: Classic(원조) / Lyra(기본, 라운드+아이콘) / Lyra Extended(홈에 커버 3개) / RoundedRaff

**아이콘**: 1bit 비트맵 16종 (`book`, `bookmark`, `folder`, `library`, `recent`, `settings`, `transfer`, `wifi`, `hotspot`, `search`, `text`, `image`, `file`, `cover` — 24px/일반 2세트)

**색 모델**: `Clear/White/LightGray/DarkGray/Black` 5단계를 4x4 Bayer 디더링으로 표현. 안드로이드에서는 **실제 그레이스케일/컬러**로 승격하면 됨.

### 1.7 기능 인벤토리

<details>
<summary><b>리더</b></summary>

EPUB 2/3 렌더링 · 내장 CSS 사용 토글 · 이미지(JPEG/PNG, 표시/플레이스홀더/숨김) · 하이픈(10개 언어) · 커닝 · 루비(`<ruby>`) · 윗/아랫첨자 · `text-decoration` · 표 · `<br>` 섹션 브레이크 · 목록 불릿 · 챕터 이동 · 각주 이동(3단 깊이 복귀) · 북마크 · 사전 조회(StarDict + dictzip) · 퍼센트 점프 · 자동 페이지 넘김(1/3/6/12분) · 4방향 화면 회전 · 포커스 리딩(Bionic) · 읽기 시간 누적 · 책 끝 화면 + 다음 책 추천 · 진행률 상태바(책/챕터/숨김) · 스크린샷
</details>

<details>
<summary><b>라이브러리</b></summary>

폴더 브라우저 · 숨김파일 토글 · 길게 눌러 삭제 · 최근 읽은 책 · 커버 썸네일 생성 · SD 캐시 관리 · 완독 시 `/Read/`로 이동 · 완독 시 최근 목록에서 제거
</details>

<details>
<summary><b>네트워크</b> (안드로이드에서는 전면 재설계 대상)</summary>

파일 전송 웹 UI · EPUB 최적화기 · 웹 설정 UI/API · WebSocket 업로드 · WebDAV · AP/STA 모드 + QR · Calibre 무선 연결 · OPDS 브라우저(서버 8개, 검색·페이지네이션·다운로드) · KOReader 진도 동기화 · GitHub 릴리스 OTA
</details>

<details>
<summary><b>기타 포맷</b></summary>

`.xtc`/`.xtch` (Xteink 전용) · `.txt` · `.bmp` 뷰어
</details>

### 1.8 "ESP32 제약에서 태어난 것" = 안드로이드에서 버려야 할 것

| 원본 설계 | 존재 이유 | 안드로이드 |
|---|---|---|
| `TextBlock` 평탄 아레나 (6개 병렬 벡터 → 1회 할당) | 페이지당 250회 할당이 힙 단편화 주범 | 불필요. 일반 data class |
| `ParsedText`가 `vector` 아님 `deque` | 수천 토큰 시 연속 블록 64~128KB 할당 실패 → `abort()` | 불필요 |
| `makeUniqueNoThrow` / `valid()` 검사 | `-fno-exceptions`에서 OOM이 abort | 불필요 |
| 백그라운드 빌드 힙 플로어(32KB/16KB maxAlloc) | 단편화 방어 | 불필요 |
| `section.bin` / `book.bin` SD 캐시 + LUT | 램에 책을 못 올림 | **개념은 유지, 구현은 Room/파일로 교체** |
| 스트립 렌더(`beginStripTarget`) | 프레임버퍼 전체를 못 잡음 | 불필요. Canvas |
| 비트맵 `.epdfont` + 합성 볼드 | FreeType 못 씀 | 불필요. **Android Paint + 실제 TTF** |
| 4x4 Bayer 디더링 | 1bit 패널 | 불필요 |
| RenderLock / FreeRTOS 렌더 태스크 | 태스크당 8KB 스택 | Coroutine + StateFlow |
| Liang 하이픈 트라이 22K LOC 내장 | 오프라인 필수 | `android.text.Hyphenator`(API 23+)가 기본 제공 |
| MiniBidi | 플랫폼에 bidi 없음 | `android.text.BidiFormatter` / HarfBuzz 내장 |
| expat / miniz / uzlib | 표준 라이브러리 없음 | `XmlPullParser` / `java.util.zip` |

> **결론**: 원본 C++의 60~70%는 "ESP32라서 그렇게 쓴 코드"다. 그대로 NDK로 옮기면 안드로이드에서 **느리고 못생기고 유지보수 불가능한** 결과가 된다. 가져와야 할 것은 **알고리즘 사양과 디자인 토큰**이지 코드가 아니다.

---

## 2. 안드로이드 앱 목표 정의

### 2.1 제품 정의 (제안)

> **로컬 EPUB을 위한, 한국어 조판이 제대로 되는 오프라인 우선 리더.**
> CrossPoint의 절제된 화면 구성과 조판 품질을 안드로이드에 옮기되, e-ink 제약에서 비롯된 타협(흑백 디더링·비트맵 폰트·버튼 힌트)은 모두 걷어낸다.

### 2.2 타깃

| 항목 | 값 | 근거 |
|---|---|---|
| minSdk | **26** (Android 8.0) | Compose 요구치 21이지만 SAF·폰트 API·`Hyphenator` 안정선. 26 미만 점유율 1% 미만 |
| targetSdk | 35 | Play 정책 |
| 폼팩터 | 휴대폰 + 태블릿 (2단 레이아웃) | |
| **e-ink 안드로이드 기기(Onyx Boox 등) 지원 여부** | **미결 — §7 D4** | 지원 시 애니메이션 제거·고대비 테마·리프레시 제어가 추가 요구사항 |
| 언어 | 한국어 / 영어 (원본 yaml 이식) | |

### 2.3 비목표 (1차)

- PDF (원본도 명시적 out-of-scope)
- `.xtc` (Xteink 전용 포맷 — 안드로이드에서 의미 없음)
- 펌웨어 OTA, WebDAV 서버, AP 모드, Calibre 무선 연결, 웹 설정 UI, 기기 웹서버 — **전부 "단말이 서버 역할을 해야 했던" 기능**. 안드로이드는 SAF·공유 인텐트·클라우드 앱이 대신함

---

## 3. 기술 스택 (권장)

| 레이어 | 선택 | 대안/비고 |
|---|---|---|
| 언어 | **Kotlin** (+ 필요 시 일부 NDK) | |
| UI | **Jetpack Compose** + Material 3 | 원본 `ThemeMetrics` 80개 토큰이 Compose 디자인 토큰과 1:1 대응 구조라 궁합이 좋음 |
| 아키텍처 | 단일 Activity + Navigation-Compose, MVVM, UDF | 원본 ActivityManager 스택 ↔ Navigation 백스택이 개념적으로 동일 |
| DI | Hilt | |
| 비동기 | Coroutines + Flow | 원본 `startBuild/buildSomeMore`의 점진적 색인 ↔ Flow 기반 진행 스트림 |
| 저장 | **Room**(라이브러리·진도·북마크·캐시 인덱스) + **DataStore**(설정) + 앱 파일(조판 캐시) | 원본 `book.bin`/`progress.bin`/`recent.json`/`settings.json` 대체 |
| 파일 접근 | **SAF**(`ACTION_OPEN_DOCUMENT_TREE`) + 앱 전용 저장소 | 원본 SD 카드 루트 탐색 대체. scoped storage 필수 |
| ZIP | `java.util.zip` (+ 대용량 시 랜덤 액세스용 자체 CDR 파서) | `lib/ZipFile` 대체 |
| XML | `XmlPullParser` (스트리밍) | expat 대체, SAX 구조 동일 |
| 이미지 | Coil + `BitmapFactory`(inSampleSize 서브샘플링) | JPEG/PNG/GIF/WebP 무료 |
| 텍스트 측정 | **`android.graphics.Paint`** (`getTextRunAdvances`, 커닝·리거처·셰이핑 포함) | EpdFont 대체 |
| 폰트 | KoPub 바탕/돋움, Pretendard 번들 + 사용자 TTF 임포트 | `.epdfont` 대체 |
| 테스트 | JUnit5 + Robolectric + **골든 조판 테스트**(원본 test/epubs 9종 재활용) | |

---

## 4. 【핵심 결정】 조판 엔진 전략 — 3안 비교

### 옵션 A — C++ 엔진 NDK 직접 포팅

`lib/Epub`을 거의 그대로 NDK로 빌드하고, `GfxRenderer`를 Canvas 백엔드로 교체, 텍스트 측정은 JNI 콜백 또는 FreeType/HarfBuzz 번들.

| | |
|---|---|
| 장점 | 조판 결과가 원본과 **바이트 단위로 동일**. 알고리즘 재검증 불필요 |
| 단점 | ① `GfxRenderer`(3.6K LOC)·`EpdFont`(2.6K LOC)를 전부 새로 써야 함 — "그대로 포팅"이 성립 안 됨<br>② 텍스트 측정이 엔진의 심장인데, 글자마다 JNI 왕복하면 성능 붕괴. 피하려면 HarfBuzz+FreeType 번들(APK +2~3MB) 후 Android 렌더링과 **글자 위치가 미세하게 어긋남**<br>③ `-fno-exceptions`·아레나·nothrow 할당 등 ESP32용 코드가 그대로 남아 영구 부채<br>④ 텍스트 선택/복사/TTS/접근성(TalkBack)을 전부 수작업 구현<br>⑤ 디버깅·프로파일링 비용, 빌드 복잡도 |
| 적합한 경우 | "원본과 픽셀 동일"이 절대 요구사항일 때만 |

### 옵션 B — Kotlin 재구현 (알고리즘 사양 이식) ★ **권장**

`lib/Epub`의 **동작 사양**을 Kotlin으로 옮긴다. 파싱·블록 모델·CSS 서브셋·줄바꿈/양쪽정렬·페이지네이션·캐시 키 전략은 그대로 계승하고, 텍스트 측정·폰트·그리기는 Android 네이티브 스택을 쓴다.

| | |
|---|---|
| 장점 | ① 커닝·리거처·복합 문자·한글 조합·이모지 셰이핑이 **공짜** (Paint/HarfBuzz)<br>② 안티앨리어싱·서브픽셀·벡터 폰트 → e-ink 비트맵 폰트보다 확실히 나은 품질<br>③ 텍스트 선택·복사·사전·TTS·TalkBack을 플랫폼 기능으로 연결 가능<br>④ ESP32 부채 0. 코드량이 원본의 **40~50%로 줄어듦**<br>⑤ 유지보수·채용·테스트 전부 표준 안드로이드 |
| 단점 | ① 줄바꿈 결과가 원본과 **글자 단위로 다를 수 있음** (측정 엔진이 다르므로 당연. 품질은 오히려 상승)<br>② 글자 단위 줄바꿈·양쪽정렬 3단계 알고리즘을 **정확히** 옮겨야 함 — 다행히 `docs/character-wrap-algorithm.md`에 사양이 문서화되어 있고, 함정(`realGapCount` vs `fillGapCount`)까지 기록되어 있음<br>③ CSS 서브셋 파서를 새로 작성 (원본 `CssParser` ≈ 900 LOC → Kotlin 600 LOC 예상) |
| 적합한 경우 | **일반적인 안드로이드 앱을 만들려는 경우 = 현재 상황** |

### 옵션 C — WebView / Readium 기반

챕터 XHTML을 WebView에 그대로 띄우고 CSS column으로 페이지네이션. 또는 Readium Kotlin Toolkit 채택.

| | |
|---|---|
| 장점 | EPUB CSS 완전 지원(표·플로트·폰트페이스·SVG). 초기 구축 최속 (4~6주면 읽기 가능) |
| 단점 | ① **글자 단위 줄바꿈 + 어절 간격 1.0~1.5x 제어가 불가능** — `word-break: keep-all`/`break-all` 수준밖에 못 씀. CrossPoint 한국어 조판의 핵심을 통째로 포기<br>② 페이지 경계·진도 오프셋 제어가 브라우저 내부에 종속 → §1.4의 "콘텐츠 기준 진도" 구현 난이도 급상승<br>③ CrossPoint 디자인/레이아웃을 재현하는 목적과 어긋남 (엔진이 곧 제품인데 엔진을 버림)<br>④ 메모리·배터리 부담, WebView 버전 파편화 |
| 적합한 경우 | 조판 품질보다 **포맷 호환 범위**가 우선이고, 한국어 조판 특화를 포기할 때 |

### 권장: **옵션 B**

근거:
1. 사용자가 계승하려는 것은 **"디자인·레이아웃·기능"** 이지 C++ 코드가 아니다. 옵션 B는 이 셋을 전부 보존한다.
2. 원본 코드의 다수가 ESP32 제약 대응이며, 안드로이드에서는 **순손실**이다 (§1.8).
3. 한국어 조판(글자 단위 줄바꿈)이 이 포크의 정체성인데, 옵션 C는 그것을 구현할 수 없다.
4. Android의 텍스트 스택이 EpdFont보다 모든 면에서 우월하므로, 측정 엔진 교체는 품질 **상승** 요인이다.

> **보완책**: 옵션 A의 장점(검증된 조판)을 살리기 위해, 원본 `test/epubs/` 9종 테스트 EPUB으로 **골든 조판 테스트**를 만들고, 원본 펌웨어의 페이지 분할 결과를 기준선으로 비교한다. 100% 일치가 목표가 아니라 **"회귀 감지"** 가 목표.

---

## 5. 안드로이드 설계안

### 5.1 모듈 구조

```
app/                    Compose 화면, Navigation, DI 조립
:core:design            ★ CrossPointTheme — ThemeMetrics 이식 토큰 + 컴포넌트
                          (CpList, CpHeader, CpTabBar, CpPopup, CpOptionPopup,
                           CpStatusBar, CpProgressBar, CpButtonMenu, CpTextField…)
:core:model             Book, Spine, TocEntry, Bookmark, Progress, ReaderRenderSpec
:core:data              Room DB, DataStore 설정, SAF 파일 접근, 캐시 관리
:epub:container         ZIP 리더, container.xml / content.opf / ncx / nav 파서
:epub:css               CSS 서브셋 파서 → CssStyle → BlockStyle   (CssParser 이식)
:epub:layout            ★ 조판 엔진 — ChapterParser(XmlPullParser SAX),
                          ParsedText, 줄바꿈(단어/글자/하이픈), 양쪽정렬,
                          Paginator(점진적 색인), PageCache
:epub:render            Page → Canvas 드로잉, 이미지 블록, 루비, 첨자, 밑줄
:feature:library        홈 / 파일 브라우저 / 최근 / 커버
:feature:reader         리더 · 메뉴 · 목차 · 북마크 · 각주 · 사전 · 퍼센트 이동
:feature:settings       설정 화면군
:feature:sync           (옵션) KOReader 동기화 / OPDS
```

### 5.2 화면 매핑 (원본 Activity → 안드로이드)

| 원본 | 안드로이드 | 비고 |
|---|---|---|
| `HomeActivity` | `LibraryHomeScreen` | 최근 책 커버 + 메뉴. 태블릿은 2단 |
| `FileBrowserActivity` | `FileBrowserScreen` | **SAF 트리 기반**으로 재설계 |
| `RecentBooksActivity` | 홈에 통합 (또는 탭) | |
| `EpubReaderActivity` | `ReaderScreen` | 핵심 |
| `EpubReaderMenuActivity` | `ReaderMenuSheet` (Bottom Sheet) | 15개 액션 |
| `EpubReaderChapterSelectionActivity` | 목차 탭 (드로어/시트) | |
| `EpubReaderBookmarksActivity` | 북마크 탭 | |
| `EpubReaderFootnotesActivity` | 각주 팝업 | 탭하면 즉시 표시 |
| `EpubReaderPercentSelectionActivity` | 진도 슬라이더 | 시크바로 자연스럽게 승격 |
| `DictionaryWordSelect/Definition` | 텍스트 선택 → 컨텍스트 액션 | **플랫폼 텍스트 선택으로 대체 → UX 대폭 개선** |
| `SettingsActivity` 외 15종 | `SettingsScreen` + 하위 라우트 | 4개 카테고리(Display/Reader/Controls/System) 유지 |
| `ReaderOptionsActivity` | 리더 내 빠른 설정 시트 | |
| `KeyboardEntryActivity` | 시스템 IME | **삭제** |
| `ConfirmationActivity` / `FullScreenMessageActivity` | Dialog / Snackbar | |
| `QrDisplayActivity` | 공유 인텐트 | **재설계** |
| `BmpViewerActivity` | 이미지 뷰어 | 선택 |
| `TxtReaderActivity` | `ReaderScreen`의 TXT 모드 | 조판 엔진 공유 |
| 네트워크 계열 9종 | 대부분 삭제 (§2.3) | OPDS/KOSync만 선택적 존치 |
| `BootActivity` / `SleepActivity` / `CrashActivity` | Splash / 없음 / Crashlytics | |

### 5.3 디자인 시스템 이식

```kotlin
// ThemeMetrics(C++ struct) → Compose 디자인 토큰
@Immutable
data class CpMetrics(
    val topPadding: Dp = 5.dp,
    val headerHeight: Dp = 45.dp,
    val listRowHeight: Dp = 30.dp,
    val listWithSubtitleRowHeight: Dp = 50.dp,
    val menuRowHeight: Dp = 45.dp,
    val tabBarHeight: Dp = 50.dp,
    val contentSidePadding: Dp = 20.dp,
    val homeCoverHeight: Dp = 400.dp,
    val popupCornerRadius: Dp = 0.dp,
    /* … 80개 토큰 전부 … */
)
val LocalCpMetrics = staticCompositionLocalOf { CpMetrics() }
```

- 원본 픽셀 값은 **480x800 e-ink 기준**이므로 그대로 dp로 옮기면 안 됨. **비율로 재해석 + 터치 타깃 48dp 최소 보장**
- 테마 4종 → `CpThemeVariant.Classic / Lyra / LyraExtended / RoundedRaff` (각각 `CpMetrics` 인스턴스 + 컴포넌트 오버라이드)
- 색: 5단계 흑백 디더 → **Light / Dark / Sepia / HighContrast(e-ink)** 팔레트로 승격
- 아이콘 16종 1bit 비트맵 → **벡터(SVG/ImageVector) 재작성**
- **버튼 힌트/사이드 버튼 힌트는 삭제** (물리 버튼이 없음). 대신 **탭 영역 + 스와이프 제스처**

### 5.4 리더 상호작용 재설계

| 원본 (물리 버튼) | 안드로이드 |
|---|---|
| 좌/우 버튼 = 페이지 | 화면 좌/우 탭 + 가로 스와이프 (+ 볼륨키 옵션) |
| 확인 짧게 = 리더 메뉴 | 화면 중앙 탭 → 상단 앱바 + 하단 시트 표시 |
| 확인 길게 = 북마크/사전/동기화 | 각각 독립 UI 어포던스 |
| 뒤로 = 홈/파일 브라우저 | 시스템 뒤로 + 예측형 뒤로 제스처 |
| 전원 짧게 = 슬립/새로고침/각주 | **삭제** |
| 기울임 페이지 넘김 | **삭제** (또는 옵션) |
| 자동 페이지 넘김 | 유지 |
| 화면 4방향 회전 설정 | 시스템 회전 + 앱 내 잠금 |

**추가 (안드로이드에서만 가능)**
- 텍스트 선택 → 복사 / 사전 / 하이라이트 / 공유
- TTS 읽어주기
- 페이지 전환 애니메이션 (슬라이드/페이드/없음 — e-ink 모드는 "없음")
- 스크롤 모드 (페이지 모드와 별개)
- 다크 모드 / 밝기·색온도 조절

### 5.5 데이터 모델

```
Room
├─ books(id, uri, contentHash, title, author, language, spineCount, bookSize, coverPath, addedAt)
├─ spine(bookId, index, href, size, cumulativeSize)
├─ toc(bookId, index, label, href, spineIndex, depth)
├─ progress(bookId, spineIndex, visibleOffset, pageInSection, percent, updatedAt)  ← 콘텐츠 기준
├─ bookmarks(id, bookId, spineIndex, visibleOffset, snippet, createdAt)
├─ readingStats(bookId, totalSeconds, sessions)
└─ layoutCache(bookId, specHash, spineIndex, filePath, pageCount, complete)

DataStore  : ReaderRenderSpec 전 필드 + UI 설정  (원본 settings.json 이식)
파일 캐시  : /data/.../layout/<bookId>/<specHash>/<spineIndex>.bin  ← section.bin 계승
             /data/.../covers/<bookId>.webp
```

**캐시 무효화**: `specHash = hash(fontFamily, fontSizeSp, lineSpacing, margin, alignment, hyphenation, embeddedStyle, characterWrap, paragraphIndent, viewportW, viewportH)` — 원본 `ReaderRenderSpec`과 동일한 발상.

### 5.6 점진적 색인 (원본 설계 계승)

```kotlin
sealed interface PaginationEvent {
    data class PageReady(val index: Int) : PaginationEvent
    data class Progress(val built: Int, val estimatedTotal: Int) : PaginationEvent
    data object Complete : PaginationEvent
}

interface SectionPaginator {
    fun paginate(spineIndex: Int, spec: RenderSpec): Flow<PaginationEvent>
    suspend fun page(spineIndex: Int, page: Int): Page
    suspend fun pageForOffset(spineIndex: Int, visibleOffset: Int): Int
    suspend fun suspendBuild()   // 부분 결과 보존
}
```
- 첫 페이지 준비 즉시 표시, 나머지는 `Dispatchers.Default`에서 계속
- 앱 백그라운드 진입 시 `suspendBuild()` → 부분 캐시 저장
- 총 페이지 수는 바이트 비율 기반 추정치(EMA 평활) — 원본 `estimatedTotalPages()` 동일

---

## 6. 단계별 로드맵

| 마일스톤 | 내용 | 산출물 | 기간(1인) |
|---|---|---|---|
| **M0 — 기반** | 모듈 골격, Compose 테마 골격, ZIP/OPF/NCX·NAV 파서, Room/DataStore, SAF 파일 브라우저 | 책 목록 표시 + 메타데이터/커버 | 2~3주 |
| **M1 — 조판 엔진** | CSS 서브셋 파서, ChapterParser(SAX), 블록 모델, **단어/글자 단위 줄바꿈 + 양쪽정렬**, Paginator, 페이지 캐시, 골든 테스트 | 챕터가 페이지로 정확히 나뉨 (헤드리스 검증) | 3~4주 |
| **M2 — 리더 화면** | Canvas 렌더, 페이지 제스처, 상태바, 목차, 콘텐츠 기준 진도, 이미지, 리더 설정(폰트/크기/줄간격/여백/정렬/들여쓰기/글자단위 줄바꿈) | **출시 가능 최소본** | 3~4주 |
| | **↑ 여기까지 8~11주 — 첫 배포 권장 지점** | | |
| **M3 — 리더 심화** | 북마크, 각주, 텍스트 선택+사전, 퍼센트 이동, 읽기 시간, 자동 넘김, 책 끝 화면, 루비/첨자/표/밑줄, 하이픈, 포커스 리딩 | 원본 리더 기능 동등 | 4~5주 |
| **M4 — 디자인 완성** | 테마 4종, 아이콘 벡터화, 다크/세피아/고대비, 태블릿 2단, 애니메이션, 접근성(TalkBack), i18n(ko/en) | 디자인 동등 + 안드로이드 품질 | 3~4주 |
| **M5 — 연동(선택)** | KOReader 동기화, OPDS 브라우저, 클라우드 임포트, TTS, 백업/복원 | | 3~5주 |

> M1과 M2는 일부 병행 가능. e-ink 안드로이드 기기 지원(§7 D4)을 넣으면 M4에 +1~2주.

---

## 7. 의사결정 필요 항목

| # | 결정 사항 | 선택지 | 권장 |
|---|---|---|---|
| **D1** | **조판 엔진 전략** | A: C++ NDK 포팅 / B: Kotlin 재구현 / C: WebView·Readium | **B** (§4) |
| **D2** | **UI 툴킷** | Compose / View 시스템 | **Compose** |
| **D3** | **1차 릴리스 범위** | M2까지(8~11주) / M3까지(12~16주) / 전체(20~26주) | **M2까지 배포 후 반복** |
| **D4** | **e-ink 안드로이드 기기(Onyx Boox 등) 지원** | 지원 / 미지원 / 나중 | 기기 보유 여부에 따라. 지원 시 M4 +1~2주 |
| **D5** | **원본 4개 테마 전부 이식?** | 4종 전부 / Lyra만 / 새로 디자인 | **Lyra 1종 + 다크/세피아**로 시작 (원본 로드맵도 "테마 동결" 상태) |
| **D6** | **네트워크 기능** | 전부 제외 / KOSync만 / KOSync+OPDS | **1차 제외, M5에서 KOSync 우선** |
| **D7** | **`.txt` / `.xtc` / `.bmp` 지원** | EPUB만 / +TXT / 전부 | **EPUB + TXT** (TXT는 엔진 재활용으로 저비용, XTC는 무의미) |
| **D8** | **폰트 번들** | KoPub+Pretendard 번들 / 시스템 폰트만 / 사용자 임포트 | **번들 + 사용자 TTF 임포트** (APK +6~10MB) |
| **D9** | **조판 호환성 기준** | 원본과 페이지 분할 일치 / 품질 우선 | **품질 우선 + 골든 회귀 테스트** |
| **D10** | **저장소 정책** | SAF 전용 / 앱 전용 복사 / 혼합 | **혼합** (SAF로 읽고 캐시는 앱 전용) |
| **D11** | **배포** | Play 스토어 / APK 직배포 / 오픈소스 | — |
| **D12** | **라이선스** | 원본 MIT 계승 여부 | 원본이 MIT이므로 **MIT 계승 + 출처 표기** 권장 |

---

## 8. 리스크

| 리스크 | 영향 | 완화 |
|---|---|---|
| 글자 단위 줄바꿈 알고리즘 재현 실패 | 한국어 조판 품질 저하 = 제품 정체성 상실 | `docs/character-wrap-algorithm.md` 사양을 테스트 케이스로 선(先) 고정. `realGapCount`/`fillGapCount` 함정은 문서에 명시되어 있음 |
| 대용량 단일 spine EPUB (수 MB 챕터) | 조판 지연·ANR | 점진적 색인 계승 + `Dispatchers.Default` + 부분 캐시 |
| EPUB CSS 다양성 | 일부 책 레이아웃 깨짐 | 원본 CSS 서브셋(18개 속성)을 기준선으로 시작, 실책(實冊) 회귀 코퍼스 구축 |
| 화면 회전·분할화면 재조판 | 진도 유실 | **콘텐츠 오프셋 기준 진도**(§1.4-2) 설계 시점부터 적용 |
| SAF 성능 (ZIP 랜덤 액세스) | 책 열기 지연 | 최초 1회 인덱싱 후 Room 캐시, 필요 시 앱 전용 저장소로 복사 |
| 폰트 번들 APK 크기 | 다운로드 이탈 | Play Asset Delivery 또는 최초 실행 시 다운로드 |
| 원본 기능 인플레 (45개 Activity) | 일정 초과 | D3 범위 결정 후 **엄격히** 준수. 원본도 SCOPE.md로 범위 방어 중 |

---

## 9. 다음 단계

1. **§7의 D1~D12 결정** — 특히 D1(엔진 전략), D3(1차 범위), D4(e-ink 기기)
2. 결정 확정 후 **M0 상세 작업 분해(WBS) + 저장소 스캐폴딩** 작성
3. 병행: 원본 `test/epubs/` 9종 + 한국어 실책 10~20권으로 **검증 코퍼스** 확보

