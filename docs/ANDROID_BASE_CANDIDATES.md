# 안드로이드 뼈대(Base) 후보 조사

**전제**: `docs/ANDROID_PORT_PLAN.md` §4 옵션 B(조판 엔진 Kotlin 재구현) 기준
**조사일**: 2026-09-22
**결론 요약**: **단일 프로젝트를 포크하는 방식은 권장하지 않음.** 뼈대를 3개 층으로 나눠 조달하고, 그중 **조판 엔진 층만 직접 구현**하는 것이 가장 비용이 낮다.

---

## 0. 결론 먼저

| 층 | 조달 방식 | 근거 |
|---|---|---|
| **앱 셸** (라이브러리·SAF·설정·Compose 네비게이션·테마) | **Book's Story 또는 FrogReader를 참조 구현으로 두고 신규 작성** | 이 층은 어느 프로젝트든 3주면 만들어짐. 포크해서 GPL/AGPL을 떠안을 이유가 없음 |
| **EPUB 컨테이너·파싱** (ZIP/OPF/NCX/NAV/CSS) | **`epub4j-kotlin` 또는 Readium `readium-streamer`를 채택**하거나 직접 구현 | 표준화된 영역. 바퀴 재발명 불필요 |
| **조판 엔진** (줄바꿈·양쪽정렬·페이지네이션·캐시) | **직접 구현 (대체 불가)** | §3에서 증명 — **한국어 글자 단위 줄바꿈 + 어절 간격 1.0~1.5x 제어를 제공하는 오픈소스가 안드로이드 생태계에 존재하지 않음** |

즉, 질문에 대한 정직한 답은: **"앱 껍데기용 뼈대는 여럿 있지만, CrossPoint의 핵심인 조판 엔진의 뼈대는 없다."**
다만 **참조할 가치가 매우 높은 소스는 있고**(§2), 그것들을 어떻게 쓸지가 §4다.

---

## 1. 후보 비교표

| 프로젝트 | 라이선스 | 규모 | 스택 | 렌더링 방식 | 페이지네이션 | 한국어/CJK 조판 | 뼈대 적합도 |
|---|---|---|---|---|---|---|---|
| **Readium Kotlin Toolkit** (`readium/kotlin-toolkit`) | **BSD-3** ✅ | ★382 / EDRLab 공식 / v3.4 (2026) | Kotlin, 모듈 5종(shared·streamer·navigator·opds·lcp), minSdk 24 | **WebView + Readium CSS** | 브라우저 CSS column | ❌ CSS 수준(`word-break`)이 한계 | **파싱층만** ◎ / 리더층 ✕ |
| **Book's Story** (`Acclorite/book-story`) | **GPL-3.0** ⚠️ | ★1.4k / 744커밋 / 활발 | Kotlin, Compose, Material You, SAF, minSdk 26 | Compose `Text` + jsoup | ❌ **스크롤 전용** (이슈 #13 "Cannot fix"로 종결) | ❌ | **앱 셸 참조** ◎ / 리더 ✕ |
| **Episteme** (`Aryan-Raj3112/episteme`) | **AGPL-3.0** ❌ | ★1.2k / 475커밋 / 성숙 | **Kotlin Multiplatform** + Compose MP (Android+Desktop) | jsoup·libmobi·Pdfium 조합 | ✅ 페이지 + 스크롤 양쪽 | 미확인 | 참조 ○ / 포크 ✕(AGPL) |
| **FrogReader** (`KNITIPKA/frog-reader`) | **MIT** ✅ | ★6 / 28커밋 / **알파·1인** ⚠️ | Kotlin 2.x, Compose M3, minSdk 26 | **네이티브 커스텀 조판** | ✅ **커스텀 페이지네이션 엔진 + 페이지 레이아웃 캐시** | ❌ (RTL은 지원, CJK는 문서에 없음) | **아키텍처 참조 ◎◎** / 의존 ✕ |
| **CoolReader / crengine** (`buggins/coolreader`, `koreader/crengine`) | GPL-2 ❌ | 20년+ 성숙 | **C++ 엔진 + JNI** | 자체 엔진 | ✅ | ✅ **검증된 CJK 조판** | 엔진 참조 ◎ / 포크 ✕(GPL) |
| **KOReader** | AGPL-3 ❌ | 매우 성숙, 안드로이드 빌드 존재 | Lua + C(crengine/MuPDF) | 자체 | ✅ | ✅ | **사양 참조 ◎** (원본이 KOSync로 연동 중) |
| **Myne** (`Pool-Of-Tears/Myne`) | **Apache-2.0** ✅ | ★활발 | Kotlin, Compose, 단일 Activity | 내장 간이 리더 | 스크롤 | ❌ | 앱 셸 참조 ○ |
| **epub4j-kotlin** (`B1ays/epub4j-kotlin`) | Apache-2.0 ✅ | 소규모 | **Kotlin Multiplatform** 파싱 라이브러리 | — (파서 전용) | — | — | **파싱층 채택 후보** ◎ |
| **epubify** (`nextstack-llc/epubify`) | 확인 필요 | 소규모 | Compose 라이브러리 | WebView 기반으로 추정 | — | ❌ | ✕ |

> ★ 수치는 2026-09 조사 시점. FrogReader는 스타 6·커밋 28로 **의존 대상으로 삼기에는 위험**하지만, 설계가 우리 계획과 가장 가깝다.

---

## 2. 후보별 상세 평가

### 2.1 Readium Kotlin Toolkit — "파싱은 가져오고 리더는 버린다"

EDRLab이 유지하는 **EPUB 업계 표준 툴킷**. BSD-3이라 라이선스 마찰이 없다.

- **가져올 것**: `readium-shared`(Publication 모델) + `readium-streamer`(컨테이너·OPF·NCX·NAV 파싱, 리소스 접근). 우리 계획 §5.1의 `:epub:container` 모듈을 통째로 대체 가능.
- **버릴 것**: `readium-navigator`. **EPUB 리플로우 렌더링이 WebView + Readium CSS 기반**이라, 계획서 §4 옵션 C와 동일한 한계를 그대로 물려받는다. CSS `word-break`/`line-break` 이상의 줄바꿈 제어가 불가능하고, 페이지 경계·콘텐츠 오프셋 제어가 브라우저 내부에 갇힌다.
- **부가**: `readium-opds`는 원본의 OPDS 기능(M5)을 거의 공짜로 해결해 준다.
- **주의**: minSdk 24, core library desugaring 필수.

### 2.2 Book's Story — "앱 셸의 교과서, 그러나 리더는 아님"

Material You + Compose + SAF + 다중 포맷. 우리가 M0에서 만들 라이브러리/설정 화면의 **참조 구현으로는 최상급**이다.

**그러나 결정적 한계**: 페이지네이션 요청(이슈 #13, 2024-07)이 **`🚫 Cannot fix`로 종결**되었다. 스크롤 전용 설계이며, 리더가 jsoup으로 파싱한 텍스트를 Compose `Text`에 흘려보내는 구조라 페이지 단위 조판이 들어갈 자리가 없다. CrossPoint의 정체성(페이지 기반 + 양쪽정렬 + 글자 단위 줄바꿈)과 정면으로 어긋난다.

**라이선스 주의**: GPL-3.0. 포크하면 결과물 전체가 GPL-3이 된다. 원본 CrossPoint는 **MIT**이므로, GPL 포크는 원본보다 라이선스를 후퇴시키는 선택이다.

### 2.3 FrogReader — "설계가 가장 닮았지만 아직 뼈대가 아니다"

조사 중 **우리 계획과 아키텍처가 가장 일치**하는 프로젝트:

| FrogReader | CrossPoint / 우리 계획 |
|---|---|
| 커스텀 페이지네이션 엔진 + **페이지 레이아웃 캐시** | `Section`/`Page` + `section.bin` 캐시 |
| Publisher CSS 캐스케이드·상속, `em/rem/ch/ex/%` | `CssParser` + `CssLength.toPixels(emSize, vw)` |
| 양쪽정렬 + 하이픈 | `ParsedText` 정렬 + Liang 하이픈 |
| H1–H6 폰트 상대 위계, 비대칭 간격 | `BlockStyle` 마진 병합 |
| Adobe/IDPF 폰트 난독화 해제, WOFF/WOFF2 | (원본에 없음 — **우리가 추가로 필요한 항목**) |
| RTL 인식(아랍어·히브리어) | `MiniBidi` |
| MIT, minSdk 26, Compose M3 | 동일 목표 |

**하지만** 스타 6개·커밋 28개·1인 알파. **의존하면 안 되고, 읽어야 한다.** MIT이므로 설계를 참고하거나 특정 모듈을 발췌해 출처 표기 후 사용하는 데 법적 문제가 없다. 특히 **폰트 난독화 해제 / WOFF2 압축 해제**는 원본 CrossPoint에 없는 기능이라 바로 쓸 값어치가 있다.

### 2.4 crengine (CoolReader / KOReader) — "CJK 조판의 정답지, 그러나 GPL"

20년 이상 다듬어진 C++ 리플로우 엔진. **CJK 줄바꿈·양쪽정렬이 실전 검증된 유일한 오픈소스**이고, CoolReader가 이미 **안드로이드 JNI 브리지를 갖추고 있다**(`android/jni/cr3engine.cpp`). 계획서 §4 옵션 A를 "CrossPoint C++ 대신 crengine으로" 바꾸면 조판 품질 리스크가 사라진다.

**막는 요소 두 가지**:
1. **GPL-2** (crengine) / **AGPL-3** (KOReader). 앱 전체가 GPL이 되며, Play 스토어 배포와 상용화에 제약.
2. 옵션 A의 단점(텍스트 선택·TTS·접근성 수작업, 빌드 복잡도, 안드로이드 렌더링과의 글자 위치 불일치)이 그대로 남는다.

→ **코드가 아니라 "조판 규칙의 정답지"로 참조**할 것. 특히 CJK 금칙 처리(줄 시작에 올 수 없는 문자 등)는 CrossPoint에도 없는 영역이라 crengine의 규칙표가 유용하다.

### 2.5 KOReader — "사양 참조 + 상호운용 대상"

원본 CrossPoint가 이미 KOSync 프로토콜로 연동 중이다(`lib/KOReaderSync`). 안드로이드 앱도 이 프로토콜을 그대로 쓰면 **기기↔안드로이드 진도 동기화**가 성립한다. 계획서 M5의 D6 결정에 직접 연결되는 자산.

---

## 3. 【핵심 발견】 Android 플랫폼 텍스트 API로는 CrossPoint 한국어 조판이 안 된다

뼈대를 고르기 전에 확정해야 할 사실이다. "`StaticLayout`에 양쪽정렬 켜면 되지 않나"의 답은 **아니오**다.

| API | 레벨 | 동작 | CrossPoint 요구와의 차이 |
|---|---|---|---|
| `StaticLayout.Builder.setJustificationMode()` | **26** | 양쪽정렬 진입점 | — |
| `JUSTIFICATION_MODE_INTER_WORD` | **26** | **공백에만** 여유 공간 분배 | 한글은 어절이 길어 한 줄에 공백이 1~2개뿐 → **간격이 폭발**. 원본 문서가 지적한 바로 그 문제 |
| `JUSTIFICATION_MODE_INTER_CHARACTER` | **35** (Android 15) | **모든 글자 사이**에 균일 분배 | ① minSdk 26 대비 9레벨 위 ② 그리고 이건 **원본이 버그로 규정하고 고친 동작**이다 — `1.5.0-ko.0` 릴리스 노트: *"한국어 글자 단위 줄바꿈 조판이 음절마다 공백을 넣던 문제를 수정"*, *"문단 전체가 균일 자간으로 렌더"* |
| `LineBreakConfig` / `lineBreakWordStyle="phrase"` | **33** | 한국어·일본어를 **어절(문절) 단위로 묶어** 줄바꿈 | **글자 단위 줄바꿈의 정반대**. 어절을 쪼개지 않으려는 기능이라 `characterWrap`에 쓸 수 없음 |

**CrossPoint가 하는 일**은 이 셋 중 어느 것도 아니다:
> 어절 경계에만 간격을 주고(1.0~1.5x 공백폭 범위로 제한), 글자 사이는 폰트 설계 그대로 붙이며, 그 범위를 못 맞추면 **다음 어절의 앞 몇 글자를 끌어와** 줄을 채운다.

이건 플랫폼 API로 표현할 수 없는 정책이다. **`Paint`는 측정에만 쓰고, 줄 채우기·간격 분배·글자 끌어오기는 직접 구현해야 한다.** (= 계획서 §4 옵션 B가 상정한 그대로. 다만 "StaticLayout으로 때울 수 있다"는 낙관은 여기서 폐기.)

**그래서 필요한 최소 구현체**:
```kotlin
// 측정은 플랫폼, 정책은 우리 것
Paint.getTextRunAdvances(...)   // 글리프 advance (커닝·셰이핑 포함)
  → LineFiller(minSpacing = 1.0f, maxSpacing = 1.5f)   // 원본 3단계 알고리즘
  → Justifier(realGapCount)                            // 어절 경계에만 분배
  → Canvas.drawText(run 단위)                           // 붙는 글자는 한 런으로
```
원본 `docs/character-wrap-algorithm.md`가 이 알고리즘의 **완성된 사양서**이고, 흔한 함정(`realGapCount` vs `fillGapCount`)까지 기록되어 있다. 사실상 **우리가 이미 설계도를 들고 있다.**

---

## 4. 권장 조합 3안

### 안 1 — **하이브리드** ★ 권장

```
앱 셸        : 신규 작성 (Book's Story·FrogReader를 UI/구조 참조)
EPUB 파싱     : Readium readium-shared + readium-streamer   (BSD-3)
조판 엔진     : 직접 구현 — CrossPoint 사양 + FrogReader 아키텍처 참조 (MIT)
CJK 금칙 규칙 : crengine 규칙표 참조 (코드 아님)
동기화(M5)    : KOSync 프로토콜 (원본 lib/KOReaderSync 사양 그대로)
```

| | |
|---|---|
| 장점 | 라이선스 전부 허용형(BSD/MIT/Apache) → **원본 MIT 유지 가능**. 파싱이라는 지루한 영역을 검증된 구현으로 건너뜀. 조판 엔진만 집중 |
| 단점 | Readium 의존이 들어와 모듈 경계 설계가 필요. Readium Publication 모델 ↔ 우리 모델 어댑터 한 겹 |
| 일정 영향 | 계획서 **M0에서 1~2주 단축** (파서 직접 구현 회피) |

### 안 2 — **완전 자체 구현**

```
전부 신규. 파싱도 XmlPullParser + java.util.zip으로 직접.
FrogReader(MIT)에서 폰트 난독화 해제 / WOFF2 부분만 발췌.
```

| | |
|---|---|
| 장점 | 외부 의존 0. 원본 구조와 1:1 대응이 가장 깨끗함. APK 최소 |
| 단점 | M0 +1~2주 |
| 선택 이유가 되는 경우 | Readium의 추상화(Publication/Resource/Fetcher)가 우리 구조에 과하다고 판단될 때 |

### 안 3 — **crengine JNI 채택** (조판 리스크 최소화)

```
crengine을 JNI로 embed, Compose UI만 신규 작성.
```

| | |
|---|---|
| 장점 | CJK 조판 품질이 처음부터 검증됨. 조판 엔진 구현(M1, 3~4주) 통째로 제거 |
| 단점 | **GPL-2 전염** · 텍스트 선택/TTS/접근성 수작업 · 빌드 복잡도 · CrossPoint 고유 조판(어절 간격 1.0~1.5x, U+3000 들여쓰기)을 crengine 위에 다시 얹어야 함 |
| 선택 이유가 되는 경우 | GPL 수용 가능 + 조판 품질 리스크를 절대 지고 싶지 않을 때 |

---

## 5. 라이선스 매트릭스

원본 CrossPoint = **MIT**. 여기서 어디로 갈 수 있는지:

| 채택 대상 | 라이선스 | 결과물 라이선스 | 상용/비공개 |
|---|---|---|---|
| Readium kotlin-toolkit | BSD-3 | **MIT 유지 가능** | 가능 |
| epub4j-kotlin / Myne | Apache-2.0 | **MIT 유지 가능** (특허 조항 유의) | 가능 |
| FrogReader | MIT | **MIT 유지 가능** | 가능 |
| Book's Story | GPL-3.0 | **GPL-3 강제** | 소스 공개 의무 |
| crengine / CoolReader | GPL-2 | **GPL-2 강제** | 소스 공개 의무 |
| Episteme / KOReader | AGPL-3 | **AGPL-3 강제** | 네트워크 사용까지 공개 의무 |

> **참조(읽고 배우기)는 라이선스와 무관하다.** 문제가 되는 것은 코드를 복사하거나 링크할 때다. crengine·KOReader·Book's Story는 **읽기 전용 참조**로 두면 된다.

---

## 6. 추가 조사가 필요한 항목

| # | 항목 | 이유 |
|---|---|---|
| A1 | Readium `readium-streamer`를 navigator 없이 단독 사용 가능한지 (의존 그래프 확인) | 안 1의 전제 |
| A2 | FrogReader 조판 엔진 실제 코드 품질 (`git clone` 후 확인) | 참조 가치 확정 |
| A3 | `epub4j-kotlin` vs Readium streamer 기능 비교 (EPUB3 nav, 암호화 폰트, 미디어 오버레이) | 파서 선택 |
| A4 | CJK 금칙(줄 시작/끝 금지 문자) 규칙 — 원본 CrossPoint에 구현 여부 확인 | 없으면 우리가 **추가**해야 할 신규 기능 |
| A5 | 한국어 EPUB 실물 코퍼스에서 `<ruby>`·세로쓰기 사용 빈도 | 우선순위 판단 |

---

## 7. 계획서에 반영할 변경

`docs/ANDROID_PORT_PLAN.md` 기준:

1. **§4 옵션 A를 "crengine JNI"로 치환** — CrossPoint C++ 직접 포팅보다 crengine 채택이 모든 면에서 우월(단 GPL). 옵션 A의 현실적 형태는 이것.
2. **§7에 D13 추가** — "EPUB 파싱: Readium 채택 / 자체 구현" (안 1 vs 안 2)
3. **§6 M0에 조건부 단축** — Readium 채택 시 2~3주 → 1~2주
4. **§3 스택 표에 명시** — `JUSTIFICATION_MODE_*`는 **쓰지 않는다**. 측정만 `Paint`, 정책은 자체 구현
5. **신규 작업 항목** — 폰트 난독화 해제(Adobe/IDPF) + WOFF2 압축 해제. 원본에 없으나 실제 EPUB에 흔함

