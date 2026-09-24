# 인계 문서

마지막 갱신: 2026-09-23 · 브랜치 `claude/android-epub-viewer-text-measurer-oqy6rq`
(`claude/android-epub-viewer-plan-y7m3k3` 에서 이어짐)

이 문서는 **세션을 이어받는 사람(또는 다음 Claude 세션)** 이 읽는 것이다. 무엇이 끝났고
무엇이 남았고 왜 그렇게 정했는지를 한자리에 둔다. 결정을 다시 논의하지 않게 하는 것이
목적이다.

---

## 1. 지금 상태

**OLO eBook 0.10.1 이 나왔다**(0.2.0: OLO 디자인 시스템 · 0.3.0: 그림 크기 · 리더 메뉴 · 폴더 단추 · 0.4.0: 번들 폰트 제거, 시스템 글꼴 · 0.5.0: 사용자 글꼴 넣기 · 0.5.1: 파일 관리자의 "연결 프로그램" 으로 열기 · 0.6.0: 출판사 글꼴 · 0.7.0: 가변 폰트 · 0.7.1: 글꼴 목록을 세 갈래로 · 0.8.0: PDF 리더 · 0.9.0: PDF 목차 · 0.10.0: 암호화된 PDF 목차 · 쪽 이름표 · 0.10.1: 출판사 글꼴의 빈 곳은 휴대폰 글꼴). 폴더 등록 → 라이브러리 → EPUB/TXT/PDF 열기 → 페이지
넘김 → 목차·책갈피 → 글꼴·크기 바꾸기까지 된다. 앱 전체를 Robolectric 으로 실제로 띄워
사람이 쓰는 순서대로 한 바퀴 도는 테스트가 있고, 화면을 스크린샷으로 남긴다.
PDF 는 쪽 그대로 보이고 두 손가락·두 번 누르기로 확대한다. 목차·제목·저자는 파일 구조에서 직접 읽는다.

```bash
cd android && ./gradlew check                 # 550개 + lint
./gradlew :app:assembleRelease                # → app/build/outputs/apk/release/OLO-eBook-0.10.1-release.apk
./gradlew :app:testDebugUnitTest              # → app/build/screenshots/*.png (화면 확인용)
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
| `:text-platform` | `AndroidTextMeasurer`(`Paint`) · `FontCatalog`(휴대폰 글꼴 + 사용자 글꼴, 글자 폭 지문) · `UserFonts` · `SfntReader`(폰트 머리 판독, 순수 Kotlin) — conformance 통과 |
| `:data` | Room(`books` `progress` `bookmarks` `recent`) 보관소 · SAF 폴더 등록·재귀 스캔 · `Uri` → `SeekableSource`/`ByteSource` · `ReaderData`(묶음) |
| `:ui-design` | `CpTheme`(색·치수·글꼴 토큰, 라이트/다크) · `CpHeader` `CpListRow` `CpTabBar` `CpStatusBar` `CpProgressBar` `CpPopup` `CpButton` `CpStepper` `CpChoice` · 선 아이콘 12종. Material 없음 |
| `:reader-reflow` | `BookReader`(조판 스레드·넘김·책갈피·목차·설정 변경 시 읽던 글자로 복귀) · `drawPage` · `ReaderScreen`(탭·스와이프·메뉴) |
| `:app` | `OloApp`·`AppContainer`(수동 DI) · `LibraryScreen` · `MainActivity` · 앱 아이콘 · 개발용 서명 키 |

### 남은 것 — 전부 안드로이드 모듈이다

| 모듈 | 내용 | 비고 |
|---|---|---|
| 실기기 확인 | §3.3 의 목록 | **여기부터.** 에뮬레이터가 없어 기기에서만 볼 수 있는 것들 |
| GUI 나머지 | `CpCoverTile`(최근 책 표지) · `CpButtonMenu` · 세피아 지면 · 라이선스 화면 | R4 |

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
val fonts = FontCatalog()                                    // 앱에 하나(AppContainer.fonts)
val spec = LayoutSpec(..., baseSizePx = px, fontId = fonts.layoutFontId(prefs.font))
val measurer = AndroidTextMeasurer.forSpec(fonts, spec)      // 글꼴·크기를 spec 에서 꺼낸다
val paint = measurer.paintFor(run.style)                    // 그릴 때도 같은 Paint
```

- **글꼴은 `LayoutSpec.fontId` 에 들어간다**(규칙 4). 글꼴을 바꾸면 폭이 달라지므로
  캐시 키에 없으면 명조로 잰 페이지를 고딕으로 그린다. `fontId` 는 `system-sans@<지문>` 꼴이고,
  지문은 기준 문자열을 **실제로 잰 폭**의 해시다 — OS 업데이트나 삼성 "글꼴 스타일" 로 시스템
  폰트가 바뀌면 캐시가 저절로 갈린다. 설정에는 지문 없는 키를 저장한다(null = 기기 기본).
- **측정기는 `forSpec` 으로만 만든다.** 글꼴을 따로 넘기면 캐시 키와 실제로 잰 글꼴이
  갈라질 수 있다. 모르는 `fontId` 는 기본 글꼴로 연다(규칙 6).
- **줄 높이는 1em 으로 정규화했다** — 인계 초안의 "`fontMetrics` 에서" 와 다르다.
  폰트마다 hhea 지표가 1.2~1.5em 으로 제각각이라(KoPubWorld 1.54em, Pretendard 1.19em),
  그대로 쓰면 글꼴만 바꿔도 한 페이지의 줄 수가 30% 가까이 바뀐다. 베이스라인은 실제 글리프
  윗변(보통 글꼴에서 한 번 잰 값)에 둔다. 사용자 글꼴(P2)에서 더 중요해진다.
- **힌팅된 폭을 쓴다**(`ANTI_ALIAS | SUBPIXEL`, `LINEAR_TEXT` 끔). 선형 텍스트는
  글리프 캐시를 꺼서 페이지 그리기가 느려진다. 같은 `Paint` 로 재고 그리므로 어긋나지 않는다.
- 기울임은 `textSkewX` 합성(한글 글꼴에 이탤릭이 없다). 굵게는 굵은 서체(`FontPair.bold`), 없으면
  `isFakeBoldText` 로 합성한다.
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

### 3.3 `:ui-design` · `:reader-reflow` · `:app` — 첫 APK 나옴. 여기서 정한 것

- **B1**: 표시 이름 **OLO eBook**, `applicationId` **`io.github.kgcaudit.oloebook`**
  (안드로이드 패키지 ID 에는 공백·대문자를 못 쓴다). 코드 네임스페이스는
  `io.github.kgcaudit.reader` 그대로 — 사용자에게 보이지 않는다. debug 빌드는
  `.debug` 접미사가 붙어 release 와 나란히 설치된다.
- **아이콘**: OLO Explorer 와 같은 주황 바탕 · 밝은 그레이 도형 · 찢는 모티프(펼친 책의
  오른쪽 페이지가 사선으로 찢겨 들림). 원본은 `app/icon-src/`.
- **서명**: `app/olo-dev.keystore` 를 **커밋했다**(비밀 아님, 암호도 build.gradle.kts 에
  있음). 세션마다 다른 debug 키로 서명하면 다음 APK 를 덮어 설치할 수 없고, 지우고 다시
  깔면 책갈피가 사라진다. **공개 배포 전에는 비공개 릴리스 키로 바꿔야 한다.**
- 사람에게 건네는 것은 **release**(R8) APK 다. Compose 는 debug 빌드에서 눈에 띄게 느리다.
- Material 을 쓰지 않는다. `CpTheme` 과 foundation 만으로 부품을 그린다.
- **색·모양은 OLO 디자인 시스템을 따른다**(2026-09-23 사용자 지정: "OLO Explorer 의 디자인
  컨셉을 활용"). 원본은 `kgcaudit/Filezilla-Client` 의 `docs/OLO-Design-System.md` 와
  `ui/theme/Theme.kt`. 클레이 `#B95B3B`(다크 `#E8A183`) · 아이보리 웜톤 중립색 · 크림슨
  에러 · 의미색(진행바 트랙, 파일 타일) 분리 · 모서리 6/10/14/18/20dp · 제목 19sp. 값을
  바꾸지 않고 옮겼으므로 **원본 문서가 바뀌면 `CpTheme.kt` 도 따라 바꾼다**.
  `ContrastTest` 가 WCAG 대비와 원본 값 일치를 붙들고 있다.
- 목록 아이콘은 Explorer 의 `FileTile` 처럼 색 타일 + 흰 글리프. TXT·PDF 는 Explorer 와
  같은 문서(슬레이트), EPUB 은 Explorer 표에 없어 팔레트 3차색(틸)을 썼다. 폴더는 클레이.
- 앱 아이콘 바탕은 Explorer 런처와 같은 그라데이션(`#E07B55` → `#C5613F`).
- 보기 설정은 SharedPreferences(DataStore 아님): 리더를 여는 순간 동기로 읽어야 첫 조판을
  옛 설정으로 한 번 더 하지 않는다.
- 두 화면뿐이라 내비게이션 라이브러리 없이 상태 하나로 오간다. 열던 책 id 는
  `rememberSaveable` 이라 프로세스가 죽었다 살아나도 읽던 책으로 돌아온다.
- 회전·다크 모드 전환은 `configChanges` 로 액티비티를 다시 만들지 않는다(EPUB 파일
  디스크립터를 닫았다 열지 않도록). 조판은 새 크기로 읽던 글자에 돌아온다.

**0.3.0 — 실제 책 세 권(Calibre·편집기·Sigil 제작본)으로 찾은 것과 정한 것** (2026-09-23)

- **그림 크기.** 172개 중 171개가 같은 1124×843 상자를 받아 세로 표지가 납작해지고
  118px 로고가 폭 가득 부풀었다. 크기를 정하는 곳이 `<img>` 속성이 아니라 CSS 클래스
  (`.calibre4 {width:45%}`, `.w100 {width:100%}`)와 **속성의 퍼센트**(`width="100%"`)였는데
  셋 다 못 읽고 있었다. 이제:
  - 그림 파일 **머리**에서 원래 크기를 읽는다(`:document` `ImageHeader` — PNG·GIF·JPEG·WebP,
    디코드 없음). `ChapterLoader` 가 채운다.
  - 크기 지정: HTML `width`/`height`(px·%) < CSS `width`·`height`·`max-width`·`max-height`.
    높이 %는 **최대 높이**로 다룬다(흐르는 본문에는 높이 기준이 없다).
  - 원래 크기는 **1 CSS px = 1dp**(`LayoutSpec.cssPxScale`, 캐시 키에 들어감).
  - **비율은 언제나 지킨다**(SVG `preserveAspectRatio="none"` 도). 지정 없는 작은 그림은 키우지
    않는다. 그리는 쪽도 상자 안에 비율대로 넣는다(방어).
  - `PageCodec.VERSION` 2 — 옛 규칙의 페이지 캐시는 자동으로 버려진다. **조판 결과를 바꾸면
    이 값을 올린다.**
- **빈 면 그림**(종이책의 빈 페이지를 옮긴 단색 그림)은 **그대로 둔다**(사용자 결정 (가)).
  출판사가 넣은 페이지를 앱이 지우지 않는다.
- **문장 속 그림**(로고를 글자처럼 줄 안에)은 v2. 세 권 합쳐 1개였고, 지금은 원래 크기의 작은
  블록으로 문단 위에 선다.
- **리더 메뉴**: 가운데를 누르면 얇은 도구줄(진행 막대 + 목차·책갈피·보기)만 뜬다. 목차·책갈피는
  전체 화면(한 줄 48dp, 지금 위치로 스크롤), 보기는 도구줄 위의 작은 판. 목차의 "지금" 은
  **지금 위치 이전의 마지막 항목**(`currentTocIndex`) — 실제 책은 목차가 챕터보다 훨씬 적다.
  진행 막대는 `BookLayout.locatorAtPercent`(100% 가 마지막 챕터의 **처음**으로 떨어지던 버그를
  테스트가 잡았다).
- **폴더 단추 하나**: ＋ 를 없애고 폴더 단추가 "책 폴더"(추가·빼기)를 연다.
- 올려 준 책 세 권은 저장소에 넣지 않았다. 테스트는 그 구조를 흉내 낸 견본으로 한다
  (`ImageSizingTest` 의 사례가 전부 실제 책의 값이다).

**0.4.0 · 0.5.0 — 글꼴** (2026-09-23, B2 번복의 P1 · P2)

- 폰트를 싣지 않는다. 목록은 **명조**(기기에 한국어 명조가 따로 있을 때만) · **휴대폰 글꼴** ·
  사용자가 넣은 글꼴. "고딕" 이 아니라 "휴대폰 글꼴" 인 이유: 삼성은 설정 › 글꼴 스타일
  (SamsungOne · 굵은 고딕 · 내려받은 글꼴)로 시스템 산세리프 자체를 바꾼다. 앱은 그 목록을 읽을
  수 없지만 `Typeface.SANS_SERIF` 가 그 선택을 따른다. 글꼴 ID 의 폭 지문 덕에 글꼴 스타일을 바꾸면
  캐시가 저절로 갈린다.
- **넣기**: 보기 › 글꼴 › "글꼴 추가" → SAF 로 파일 하나. 앱 영역(`filesDir/fonts/<sha256>.ttf`)에
  복사한다 — 원본 URI 를 붙잡으면 다운로드 폴더를 비울 때 글꼴이 사라진다. 색인 파일 없이 폴더를
  읽어 목록을 만든다. 같은 파일은 한 번만.
- 이름·굵기·한글 여부는 `SfntReader` 가 name · OS/2 · head · cmap 을 직접 읽어 안다.
  `Paint.hasGlyph` 는 대체 글꼴까지 뒤져 영문 폰트에도 "한글 있음" 이라 답하므로 쓰지 않는다.
  한국어 이름 판정은 **가족 이름(ID 1·16)만** 본다 — 영문 폰트도 저작권 칸은 한국어인 경우가 흔하다
  (테스트가 잡았다). 표가 파일 끝을 넘으면(덜 받은 파일) 거절한다.
- **짝짓기**: 영어 가족 이름으로 묶는다. 보통 = 400 에 가장 가까운 바로 선 서체, 굵게 = 600 이상 중
  700 에 가장 가까운 것. 굵게가 없으면 `isFakeBoldText`. 굵은 파일을 나중에 더하면 글꼴 ID 가 바뀐다
  (지문이 굵은 서체도 잰다).
- TTC 는 한 파일에 여러 나라 가족이 있으면 **한국어 이름이 있는 가족만** 올린다(Noto CJK).
- 한글 없는 글꼴은 거절하지 않고 알린다(영문 책용). WOFF · 폰트 아닌 파일 · 깨진 파일 · 64MB 초과는
  이유와 할 일을 말하고 거절한다. 파일 종류는 `*/*` 로 연다 — `.otf` 를 OpenDocument 서식으로 아는
  파일 관리자가 있어서, 좁히면 진짜 폰트가 회색으로 눌리지 않는다.
- 빼면 파일이 지워지고, 그 글꼴을 고른 설정은 **null(기기 기본)로 되돌린다**. 되돌리지 않으면 지운
  글꼴의 ID 로 기본 글꼴 폭이 캐시에 들어간다.
- 앱 테스트는 파일 선택기에 Robolectric 으로 답해서 "글꼴 추가 → 고름 → 조판" 을 실제로 돈다
  (`AppWalkthroughTest`). 테스트 폰트는 `:text-platform` 의 `olo-test-fonts/` 를 함께 쓴다 — 폴더
  이름을 `fonts/` 로 하면 Robolectric 이 자기 시스템 폰트 대신 이걸 읽어 죽는다.

**0.10.0 — 암호화된 PDF 의 목차 · 쪽 이름표** (2026-09-24, 사용자 요청 "이 PDF 도 분석해서 목차 생성 로직을 고도화")

사용자가 올린 잡지 두 권(씨네21 No.1569 · 좋은생각 Vol.415)을 분석했다. 둘 다 PDF 1.7, 선형화, 상호
참조 스트림 + 객체 스트림, **AES-256(V5 R6) 으로 암호화**, 권한 P=-3392(인쇄·복사 제한), 사용자 암호는
비어 있다(누구나 연다). 0.9.0 은 암호화된 파일에 빈 목차를 줬다 — 그래서 이 잡지들에 목차가 없었다.

- `PdfSecurity`: 표준 보안 처리기를 **빈 사용자 암호로만** 연다(R2–R4 RC4 · AESV2, R5/R6 AESV3).
  암호를 묻지 않는다 — 진짜 암호가 걸린 파일은 전처럼 빈 목차(엔진도 못 연다). 권한은 건드리지 않는다
  (읽기만 한다). 명세대로: 상호 참조 스트림 · 암호 사전은 풀지 않고, 객체 스트림은 스트림째 한 번만 푼다.
- 결과: 씨네21 31항목(76ms), 좋은생각 28항목(20ms). 쪽 번호가 잡지 목차와 같다.
- 분석에서 나온 다듬기:
  - 맨 위 항목이 하나("목차")이고 전부가 그 아래면 한 단계 올린다(두 잡지 다 이 모양).
  - 문서 정보의 `<C88B><C740>…` 는 한글을 유니코드 번호로 적은 것 → "좋은생각". 한글·한자·가나 범위만
    푼다(진짜 꺾쇠 `<동궁>` 은 그대로). 저자 "USER" 같은 계정 이름은 버린다.
- 찾았지만 이번에 고치지 않은 것: 책을 연 채 화면(액티비티)이 다시 만들어지면 옛 리더를 닫지 않아
  디스크립터 하나가 남는다(시험의 CloseGuard 경고로 발견, 0.10.0 이전부터). 리더 닫기를 화면 수명
  (DisposableEffect)에 묶으면 되는데, 닫기가 두 번 불려도 되는지부터 봐야 한다 — 다음 작업.
- 쪽 이름표(`/PageLabels`): 인쇄된 쪽 번호(로마 숫자 머리말, 접두어, 시작값). 목차의 쪽 번호 · 상태 막대 ·
  진행 막대 · 책갈피 이름이 그것을 쓴다. 1부터 세는 이름표(두 잡지)는 파일 순서와 같아 따로 보이지 않는다.
- 시험용 암호화 PDF 는 `document/src/test/resources/pdf/make_encrypted.py`(파이썬 + openssl)가 Kotlin
  코드와 **따로** 만든다 — 같은 코드로 암호화·복호화하면 같이 틀려도 통과한다. 복호화의 여섯 군데를 일부러
  망가뜨려 모두 잡히는 것을 확인했다.

**0.9.0 — PDF 목차 · 제목 · 저자** (2026-09-24, 사용자 요청 "PDF 도 목차 메뉴를 활성화")

- PdfRenderer 는 목차를 주지 않는다. 목차는 파일 구조에 적힌 나무라 **엔진 없이 읽을 수 있다** —
  `:document` 의 `pdf/` 에 순수 Kotlin 으로 읽개를 두었다(`PdfSyntax` 문법 · `PdfFile` 객체 찾기 ·
  `PdfStructureReader` 목차·문서 정보). APK 는 그대로, 엔진(P1 ①)도 그대로다.
- 받는 모양: 옛 상호 참조표 + `/Prev`(덧붙여 고친 파일), PDF 1.5 상호 참조 스트림 · 객체 스트림
  (FlateDecode + PNG 예측자), 섞은 파일(`/XRefStm`). 목적지는 쪽 배열 · `/Dests` 이름 · `/Names`
  이름 나무 · GoTo 동작. 쪽 없는 머리("제1부")는 첫 자식의 쪽.
- 상호 참조가 **틀렸을 때만** 파일을 훑어 되살린다. 끊긴 참조 하나마다 훑으면 큰 파일이 몇 초씩 걸린다
  (처음 짠 것이 그랬고, 일부러 망가뜨린 시험이 못 잡아서 찾았다 — 훑기가 다른 버그를 덮고 있었다).
- 사용자가 올린 두 권으로 확인: 휴남동 서점(옛 표, 쪽 직접, 42항목, 53ms) · 소년이 온다(명명 목적지,
  ExifTool 덧붙임, 10항목, 11ms). 쪽 번호가 다른 뷰어의 목차와 같다. 파일은 커밋하지 않았다 —
  같은 구조를 `TestPdf`(`:document` 테스트 픽스처)로 지어 시험한다.
- 실제 책에서 본 함정: UTF-16 제목의 0x08 바이트는 `\b` 로 적힌다('절'·'눈'). 틀리면 "거졢", "뉢동자".
  저자 "한강" 의 0x5C 는 `\\`.
- (0.9.0 에서는 암호화된 PDF 의 목차를 주지 않았다. 0.10.0 에서 빈 사용자 암호로 푼다.)
- 목차를 **엔진에 넘기기 전에** 읽는다. 디스크립터를 복제해 읽고 복제본을 닫으면 환경에 따라 원본까지
  닫혔다(시험에서 걸림) — 지금은 원본을 빌려 위치 지정 읽기(pread)만 하고 닫지 않는다.
- 화면: 도구줄에 목차 단추(EPUB 과 같은 자리), 목차 · 책갈피 탭, 쪽 번호(1부터), 지금 장에 불.
  라이브러리는 파일에 제목이 적혀 있으면 그 제목 · 저자를 보인다("Microsoft Word - …" 같은 것은 다듬거나 버린다).

**0.8.0 — PDF 리더** (2026-09-23, 결정 P1 ① 확정: 플랫폼 `PdfRenderer`, APK 증가 0)

- `:reader-pdf`. 엔진은 `PdfSource` 인터페이스 뒤에 있다 — 구현은 `PlatformPdfSource`(PdfRenderer)
  하나, 테스트는 가짜. Pdfium 으로 바꿀 때 바꾸는 곳은 `AppContainer.pdfEngine` 한 줄이다.
- 조작은 EPUB 과 같다(왼쪽·오른쪽 3분의 1 누르기, 옆으로 밀기, 가운데 = 도구줄). 더한 것은 확대:
  두 손가락으로 벌리거나 두 번 누른다(최대 5배). 확대한 동안 밀면 쪽 안을 움직이고 넘기지 않는다.
  손을 멈추면 **보이는 부분만** 화면 해상도로 다시 그린다 — 쪽 전체를 5배로 그리면 비트맵 하나가
  250MB 다. 한 번 누르기는 두 번 누르기를 기다리느라 약 0.3초 늦다(받아들임).
- 확대 1 은 **쪽 전체가 보이는 크기**(폭 맞춤이 아니다). 넘길 때마다 한 장이 온전히 보여야 한다.
- 위치는 `Locator.FixedPage`(쪽 번호). 쪽 수가 줄어든 파일이면 마지막 쪽으로 연다. 앞뒤 쪽을 미리
  그려 두어(3장 LRU) 넘길 때 빈 종이가 번쩍이지 않는다.
- (0.8.0 에서는 목차 단추가 없었다. 0.9.0 에서 파일 구조를 직접 읽어 더했다.)
- 파이프로 오는 파일(일부 클라우드·메일 첨부)은 PdfRenderer 가 거절한다. `UriSources.seekableDescriptor`
  가 캐시로 옮긴 뒤 열자마자 사본을 지운다(디스크립터가 닫힐 때 공간이 돌아온다).
- 암호 PDF(SecurityException "password")·깨진 PDF(IOException)는 각각 이유를 말한다.
- "연결 프로그램" 목록에 PDF 도 오른다(매니페스트 `application/pdf`).
- **실기기 확인 필요**: Robolectric 에는 pdfium 이 없어 PdfRenderer 가 실제 파일을 그리는 것은
  기기에서만 볼 수 있다. 화면·동작 테스트(`PdfAppTest`)는 쪽을 손으로 그리는 가짜 엔진으로 돈다.

**코드 점검** (2026-09-23, 0.7.1 뒤 · PDF 앞) — 중복 · 불필요 · 오류를 모듈별로 훑었다. 고친 오류:

- `<script>`·`<title>` 을 버릴 때 **부모의 스타일 틀을 대신 꺼냈다** — 그 뒤 문단이 부모 스타일을 잃었다.
- `margin: 0 auto 1em` 의 `auto` 가 빠지며 **뒤 값들이 한 칸씩 당겨졌다**(아래 여백이 왼쪽으로).
- `p::before`·`p::first-letter` 규칙이 `p` 전체에 걸렸다 → 가상 요소 규칙은 버린다.
- 목차 `<a><span>제목</span></a>` 의 span 을 새 항목으로 읽었다.
- 폴더가 다른 같은 이름의 CSS(`../style.css` 두 개)를 하나로 캐시했다 → 풀린 경로로 캐시.
- 따옴표 없는 속성값 `src=img/a.png` 가 `/` 에서 끊겼다.
- 강제 줄 나눔이 이모지(서로게이트 쌍)를 반으로 잘랐다.
- 압축 해제 스트림을 닫아도 `Inflater` 네이티브 메모리가 남았다.
- 페이지 캐시: 페이지 수가 u16 이라 65,535 쪽을 넘는 챕터가 깨졌다 → **v4**(개수는 색인 크기에서,
  텍스트 바이트 수를 머리말에 두어 넘길 때마다 텍스트 전체를 읽지 않는다).
- 같은 파일을 "연결 프로그램" 으로 두 번 받으면 "책을 여는 중…" 에 멈췄다. 여는 도중 화면이 떠나면
  연 파일을 아무도 닫지 않았다(`openKeepingResult`).
- 글꼴 크기를 바꾸면 읽던 자리가 아니라 **조판 중인 자리**로 돌아갈 수 있었다 → 보이는 페이지의 Locator 를 기준으로.
- 백업: `pages/`·`fonts/` 캐시가 클라우드 백업에 실렸다 → 제외 규칙.

정리한 것: 스트림 읽기 · 해시 · 굵기 고르기 중복을 한 곳으로(`Streams.kt`·`fnv1aHex`·`FontFaces.kt`),
리더 막대 · 전체 화면 판을 `:ui-design`(`CpReaderBar`·`CpFullScreen`)으로 — PDF 리더가 같은 것을 쓴다.
쓰지 않는 의존성(Room testing · DataStore · Coil · Navigation · Material3 · tooling 등)과 죽은 코드를 뺐다.

**0.7.1 — 글꼴 목록은 세 갈래: 출판사 글꼴 · 휴대폰 글꼴 · 사용자 글꼴** (2026-09-23 사용자 결정)

- 실기기 화면을 보고 사용자가 정리를 요청: **시스템 명조와 "받을 수 있는 글꼴(Google Fonts)" 을
  뺐다.** 추천 글꼴 받기(아래 0.7.0 의 P4)는 코드째 지웠다 — `FontDownloads`, 인증서 목록,
  매니페스트 `<queries>`, androidx.core 의존. 가변 폰트 지원은 사용자 글꼴이 쓰므로 남겼다.
- "글꼴 추가" 는 **"사용자 글꼴"** 로 바꿨다. 넣기 단추이자 넣은 글꼴들의 머리다 — 넣은 글꼴은 그
  아래에 붙고, 목록은 늘 세 갈래로 보인다.
- 기본 본문 글꼴은 휴대폰 글꼴. 예전 설정의 `system-serif`(명조)·번들 폰트 키는 `PrefsStore` 가
  null(기본)로 지운다. 명조로 읽고 싶으면 명조 파일을 사용자 글꼴로 넣는다.

**0.7.0 — 추천 글꼴 받기 · 가변 폰트** (2026-09-23, B2 번복의 P4 — 받기는 0.7.1 에서 뺐다)

- 보기 › 글꼴 에 "받을 수 있는 글꼴 · Google Fonts": 나눔명조 · 고운바탕 · 본명조(Noto Serif KR) ·
  나눔고딕 · 고운돋움 · IBM Plex Sans KR(모두 OFL). 명조를 앞에 둔다 — 한국어 명조가 빠진 기기(삼성)가
  이 기능이 필요한 이유다. 누르면 받아서 바로 그 글꼴로 바꾼다.
- **Google Play 서비스의 글꼴 제공자**(`FontsContractCompat`)로 받는다. 앱에는 여전히 인터넷 권한이
  없다 — 제공자가 받는다. 인증서 목록 `text-platform/res/values/font_certs.xml` 은 안드로이드 공식
  예제(Apache 2.0)의 것. 안드로이드 11+ 에서 제공자를 찾으려면 매니페스트 `<queries><provider>` 가
  필요하다(빠지면 Play 서비스가 있어도 "없음").
- 받은 파일은 **사용자 글꼴(`UserFonts`)로 복사**한다. 제공자 캐시가 비워져도·오프라인이어도 남고,
  보통·굵게 짝짓기·빼기가 P2 와 같다. 굵게를 못 받으면 보통만 넣는다(합성 굵게).
- "이미 받음" 판정은 **파일 안의 가족 이름**으로 한다. 나눔 글꼴은 Google 이름("Nanum Myeongjo")과
  파일 이름("NanumMyeongjo")이 달라 `RecommendedFont.fileFamily` 에 따로 적었다(google/fonts 저장소
  파일에서 확인).
- **가변 폰트**: `fvar` 의 `wght` 범위를 읽어 보통=400, 굵게=700 을 축 값으로 만든다
  (`setFontVariationSettings`). 그냥 읽으면 기본 인스턴스라 Noto Serif KR 은 200(실처럼 가늘게)으로
  나온다. 사용자가 넣는 Pretendard Variable 도 같은 길을 탄다. 출판사 글꼴이 가변이면 보통(400)만.
- 실패는 이유와 할 일을 말한다: Play 서비스 없음(파일로 추가하라고) · 인터넷 · 없는 글꼴 · 깨진 파일.
- 실기기에서 볼 것: 실제로 받아지는지(테스트 환경엔 Play 서비스가 없어 가짜 제공자로만 확인),
  Noto Serif KR 을 제공자가 정적 파일로 주는지 가변 파일로 주는지.

**0.6.0 — 출판사 글꼴(책에 든 글꼴)** (2026-09-23, B2 번복의 P3)

- 흐름: CSS `@font-face` → `BookFontTable`(core, 순수) → `TextStyle.face` 번호 → 페이지 캐시(런
  레코드의 예약 u16, `PageCodec` v3) → `BookTypefaces`(text-platform, 파일을 꺼내 `Typeface`) →
  `AndroidTextMeasurer` 가 번호로 서체를 고른다. 재는 쪽과 그리는 쪽이 `BookReader.measurer(spec)`
  하나로 만든다.
- **글꼴표는 책의 모든 CSS(매니페스트, 경로 정렬)로 만든다.** 챕터를 연 순서로 만들면 번호가
  달라지고, 번호는 캐시에 남으므로 다음에 열 때 다른 글꼴로 그린다.
- **쓰이는 가족만** 넣는다: 어떤 규칙의 `font-family` 목록에서 처음으로 책에 있는 이름. 삼체는 8개를
  선언하지만 본문 목록은 늘 첫 번째(kofd1)만 쓴다. 꺼내는 것은 처음으로 책 글꼴로 조판할 때,
  캐시 영역 `book-fonts/<책 해시>/` 에.
- `font-family: serif` 처럼 책에 없는 이름만 있으면 0(본문 글꼴)으로 **돌아간다**. 적지 않으면 상속.
- **출판사 글꼴이 켜져 있을 때의 "본문 글꼴"(번호 0)은 늘 휴대폰 글꼴이다**(0.10.1, 사용자 결정 "책이 정하지 않은
  곳은 휴대폰 글꼴"). 그 전에는 마지막에 고른 사용자 글꼴이 들어갔는데 화면 어디에도 보이지 않았다 —
  목록 설명은 "아래 고른 글꼴" 이라면서 아래 줄엔 불이 꺼져 있었다. 규칙은 `ReaderPrefs.bodyFont` 한 곳.
  고른 사용자 글꼴은 설정에 남아, 출판사 글꼴을 끄면 그것으로 돌아간다.
- 굵기는 `@font-face` 에 적힌 값, **없으면 폰트 파일의 OS/2 굵기**. 퀴즈 책은 "바탕B"(굵은 파일 하나)를
  굵은 h1 에 쓴다 — 적힌 값만 보면 한 번 더 굵혀 획이 뭉개진다.
- 세로 지표(줄 높이·베이스라인)는 본문 글꼴 하나로 정한다. 책 글꼴마다 재면 제목 글꼴이 든 줄만
  베이스라인이 달라진다.
- 난독화(`META-INF/encryption.xml`, IDPF·Adobe)를 푼다. 열쇠는 `unique-identifier` 가 가리키는
  식별자(첫 identifier 가 아니다 — ISBN 을 먼저 적는 책이 흔하다). 진짜 DRM 은 풀지 않고 본문 글꼴로.
- 설정 `publisherFonts`(기본 켬)은 **책마다가 아니라 하나**. 목록에서 다른 글꼴을 고르면 꺼진다 —
  출판사 글꼴이 싫은 사람이 책마다 끄지 않게. "출판사 글꼴" 줄은 글꼴이 든 책에서만 보인다.
  캐시 키에는 `useBookFonts`(끈 상태의 키는 예전 그대로 — 글꼴 없는 책을 다시 조판하지 않는다).
- 관대하게: 세미콜론 빠진 `@font-face`(삼체 `"kofb2":src:url(...)`), 없는 파일, WOFF, 깨진 파일,
  깨진 `encryption.xml` 은 그 가족만 본문 글꼴로 그린다. 읽지 못한 파일은 캐시에서 지운다.
- **앱 UI 테스트가 가끔 멈추던 것(0.5.1 부터) — 0.10.0 에서 원인을 찾아 고쳤다.** 증상은 전체 `check` 처럼
  부하가 클 때 "책을 연 뒤 첫 쪽" 을 30초 기다리다 실패하거나, 드물게 `CalledFromWrongThreadException`.
  원인: Compose 시험 도구는 효과(LaunchedEffect · collectAsState)를 **UnconfinedTestDispatcher** 로 돌린다
  (`AndroidComposeUiTestEnvironment` 바이트코드로 확인). 그래서 백그라운드에서 값이 온 코루틴 — 조판
  스레드의 리더 상태, Room 질의 결과, `withContext(IO)` 에서 돌아온 효과 — 이 **그 스레드에서** 이어져,
  화면 상태 쓰기와 프레임이 메인 밖에서 돌았다. 실제 앱(AndroidUiDispatcher)에서는 생기지 않는, 시험만의
  스레드 모델이었다. 고침: 앱 시험 규칙에 `StandardTestDispatcher()` 를 넘긴다(돌아온 코루틴을 대기열에
  넣고 시험 스레드에서 차례로 돌린다 = UI 스레드 하나). 전에 "coroutines-debug 를 붙이면 사라진다" 던
  것도 이것과 맞는다(디버그 탐침이 재개 시점을 바꿨을 것이다 — 확인하지는 않았다). 새 앱 시험을 만들 때도 이 인자를 넘긴다.
- 알려진 한계: 챕터 안 `<style>` 의 `@font-face` 는 보지 않는다. 책 파일을 같은 URI 로 바꿔치면 꺼내 둔
  글꼴이 옛것일 수 있다(캐시를 지우면 된다).

**0.5.1 — "연결 프로그램" 으로 열기** (2026-09-23)

- 증상: OLO Explorer·내 파일에서 EPUB 을 눌러도 목록에 OLO eBook 이 없었다. 원인은 매니페스트에
  `MAIN/LAUNCHER` 뿐이고 **`VIEW` 선언이 없었던 것**. 안드로이드는 선언으로 목록을 만든다.
- 이제 `application/epub+zip` · `text/plain` 의 `content://` 를 받는다. **PDF 는 리더가 생기면
  더한다**(먼저 올리면 열 수 없는 파일을 받는다). OLO Explorer 는 정확한 형식부터 묻고 넓혀 가므로,
  이 선언이 있으면 EPUB 을 눌렀을 때 OLO eBook 이 첫 질문에서 답한다.
- 받은 파일(`Incoming`): 형식은 **파일 이름 먼저, 없으면 보낸 MIME**. 이름·크기가 같은 책이
  라이브러리에 있으면 **그 책으로** 연다(진도·책갈피 한 벌). 없으면 받은 URI 를 id 로 열고
  라이브러리·최근 목록에는 넣지 않는다 — 읽기 권한이 이 화면 동안뿐이라 나중에 누르면 안 열린다.
- `launchMode=singleTask`: 앱이 떠 있으면 새 창을 쌓지 않고 `onNewIntent` 로 연다. 받은 책을 닫으면
  라이브러리가 아니라 **보낸 앱으로 돌아간다**(`moveTaskToBack`).
- 받은 URI·형식은 `rememberSaveable` — 프로세스가 죽었다 살아나도 다시 연다. 형식을 함께 저장하는
  이유: 확장자 없는 파일은 인텐트의 MIME 으로만 알 수 있다(테스트가 잡았다).
- 테스트가 잡은 것 둘 더: `ContentResolver.query` 옛 5인자 판은 `DocumentsProvider` 가 거절해 크기를
  못 얻었다(Bundle 판으로). 화면이 다시 만들어지며 **취소된 열기를 실패로 다뤄** 받은 책을 잊었다
  (`CancellationException` 은 다시 던진다).
- OLO Explorer 쪽은 바꿀 것이 없다. 파일을 **보내는** 앱이고, 자기 목록에서 자신을 빼는 것도 맞다.

**실기기에서 볼 것**(Robolectric 이 못 보여 주는 것):
- 페이지 넘김 체감 속도(16ms 예산). 넘길 때 디스크 캐시에서 페이지를 읽는다 — 느리면 앞뒤
  한 장씩 미리 읽기를 `BookReader` 에 더한다.
- release(R8) 빌드의 시작. 테스트는 축소 전 코드로 돈다. Room·Compose 는 자체 keep 규칙을
  싣고 오고 우리 코드는 리플렉션이 없으므로 문제없을 것으로 보지만 확인은 기기에서.
- 몰입 모드(시스템 바 숨김)와 디스플레이 컷아웃 여백, 제스처 내비게이션과 스와이프 충돌.
- 폴더 선택기 → 등록 → 스캔(실제 파일 관리자·SD 카드·Google Drive).
- 호스트와 기기의 글자 폭 차이(§3.1).
- 글꼴: 파일 관리자·Google Drive 에서 폰트 고르기,
  큰 한글 폰트(10~20MB) 넣는 시간.
- 연결 프로그램: OLO Explorer·내 파일·Gmail 첨부에서 EPUB/TXT 를 눌러 목록에 뜨는지, 닫으면 보낸
  앱으로 돌아가는지. 한 번 "항상" 을 고르면 그 뒤로 바로 열린다.

알려진 한계: 표지·검색·세피아 없음. 그림은 넣었지만 견본 책에 그림이 없어 스크린샷으로
확인하지 못했다. lint 경고 45개는 전부 "새 버전 있음" — 버전 올리기는 따로 판단한다.

### 3.4 `:reader-pdf`, GUI, 나머지

`docs/ANDROID_BUILD_SPEC.md` §6 의 R1·R4 대로.

---

## 4. 미결 결정 1건 — 사용자 확인 필요

| # | 항목 | 권장 | 왜 지금 필요한가 |
|---|---|---|---|
| **B3** | 테스트 코퍼스 | EPUB 20 · TXT 10 · PDF 10 | `corpus/README.md` 참고. 파일은 커밋하지 않고 골든만 커밋한다 |

---

## 5. 이미 내린 결정 — 다시 논의하지 말 것

| 결정 | 근거 |
|---|---|
| **B1: 앱 이름 OLO eBook · 아이콘 컨셉 · OLO 디자인 시스템** (2026-09-23 사용자 결정) | 표시 이름 "OLO eBook", `applicationId` `io.github.kgcaudit.oloebook`. 아이콘은 OLO Explorer 와 바탕색 통일·도형 그레이·찢는 느낌. 배포 후 `applicationId` 를 바꾸면 다른 앱이 되어 데이터가 끊긴다 |
| **P1: PDF 는 플랫폼 `PdfRenderer`** (2026-09-23 사용자 결정 ①) | APK 증가 0, 넘기기·확대·책갈피에 충분. 목차·제목은 0.9.0 부터 `:document` 가 파일 구조에서 직접 읽는다. 검색·암호 PDF 가 필요해지면 `PdfSource` 뒤에서 Pdfium 으로 바꾼다(+3~6MB/ABI) |
| **B2 (번복): 폰트를 싣지 않는다 — 시스템 글꼴 + 사용자 글꼴 + 책 내장 글꼴** (2026-09-23 사용자 결정 "권장대로") | 아래 옛 B2 의 근거("기기마다 조판이 다르다")는 위치를 글자 오프셋으로 저장하고 캐시를 기기마다 만드는 구조에서 사용자에게 드러나지 않는다. 번들은 APK 12MB 중 11MB 였고, 올려 받은 책 세 권이 모두 KoPub 을 **내장**하고 있었다. 한국어 명조는 AOSP 에 대체 글꼴(Noto Serif CJK, 보통 굵기 하나)로만 있고 제조사가 빼기도 해서 **있는지 재 보고**(`FontCatalog.hasKoreanSerif`) 없으면 목록에서 뺀다. 순서: P1 시스템 글꼴(0.4.0, 끝남) → P2 사용자 글꼴 추가(0.5.0, 끝남 — SAF, TTF/OTF/TTC) → P3 출판사 내장 글꼴(0.6.0, 끝남 — `@font-face`, 내장 글꼴이 있는 책은 그것으로 시작) → P4 추천 글꼴 받기(0.7.0 에 넣었다가 0.7.1 에서 사용자 요청으로 뺐다). 시스템 명조도 0.7.1 에서 뺐다 — 목록은 출판사 · 휴대폰 · 사용자 글꼴 세 갈래 |
| ~~B2: KoPubWorld 바탕 + Pretendard 번들~~ (위 결정으로 대체) | 시스템 글꼴은 기기마다 조판이 달라진다. KoPub 구판이 아니라 **KoPubWorld** 인 이유: 구판에는 `—`(U+2014)가 없고 한자가 4,620자뿐이다(World 는 6,007자). 두 글꼴 모두 한글 11,172자 전부. 라이선스: Pretendard 는 OFL, **KoPubWorld 는 OFL 이 아니라 KOPUS 약관**(무료 재배포 가능 · 유료 판매 금지 · 약관 동봉 의무 · 수정본에 "KoPub" 이름 금지) — 그래서 서브셋하지 않고 원본을 넣었다. 저장소 +21.5MB, APK +11MB(압축) |
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
첫 APK(OLO eBook 0.1.0, EPUB·TXT)까지 나와 있다. HANDOFF §2 대로 SDK 와 Maven 미러를
설정하고, §3.3 의 실기기 확인 결과를 먼저 물어본 뒤 이어가라.
미결 1건(B3 코퍼스)이 필요해지면 먼저 물어봐.
```
