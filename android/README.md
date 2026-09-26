# Reader — 안드로이드 전자책 뷰어

EPUB · TXT · PDF를 폴더에서 바로 열어 읽고, 책갈피와 이어읽기가 확실한 가벼운 뷰어.
설계 근거는 [`../docs/ANDROID_BUILD_SPEC.md`](../docs/ANDROID_BUILD_SPEC.md) 참조.

## 빌드

| 하려는 것 | 필요한 것 | 명령 |
|---|---|---|
| **코어 모듈 테스트** (파싱·조판) | **JDK 21만** | `./gradlew :document:check :core-layout:check` |
| 앱 빌드 | JDK 21 + Android SDK | `./gradlew :app:assembleRelease` (건넬 APK) |

Android SDK가 없으면 `settings.gradle.kts` 가 Android 모듈을 아예 구성에서 빼고
JVM 코어만 빌드한다. 이 덕분에 CI의 빠른 게이트가 SDK 설치 없이 돈다.

SDK가 있으면 `ANDROID_HOME` 환경변수나 `local.properties` 의 `sdk.dir` 로 알려 준다.

## 모듈

| 모듈 | 성격 | 상태 |
|---|---|---|
| `:document` | **순수 Kotlin** — 위치 모델 · 포맷 감지 · Document 계약 | R1 진행 중 |
| `:core-layout` | **순수 Kotlin** — 블록 모델 · 조판 · 페이지 캐시 | R2 |
| `:data` | Room(책·진도·책갈피·최근) · SAF 폴더 스캔 · `Uri` 바이트 원천 | **완료** (DataStore 는 설정 화면과 함께) |
| `:text-platform` | `AndroidTextMeasurer`(`Paint`) · `FontCatalog`(휴대폰 글꼴 + 사용자 글꼴 — 폰트를 싣지 않는다) | **완료** |
| `:reader-reflow` | EPUB·TXT 리더 · Canvas 그리기 | **완료** |
| `:reader-pdf` | PDF 리더 · `PdfRenderer` · 타일 줌 | R1 |
| `:ui-design` | CpTheme 토큰(OLO 디자인 시스템) + 컴포넌트 | **완료**(기본 부품) |
| `:app` | OLO eBook — 라이브러리 화면 · 조립 · 아이콘 · 서명 | **v0.7.1** (`./gradlew :app:assembleRelease`) |

## 지켜야 할 규칙 하나

**`:document` 와 `:core-layout` 에 `android.*` / `androidx.*` 를 넣지 않는다.**

이 둘이 순수한 동안에만 파싱·조판 로직을 기기 없이 JVM 단위 테스트로 검증할 수
있고, 그게 이 프로젝트에서 수정 비용을 결정하는 가장 큰 요인이다. 위반하면
`checkNoPlatformImports` 가 빌드를 실패시킨다(`check` 에 물려 있어 PR 게이트에서
항상 돈다).

플랫폼 타입이 필요하면 코어에는 **인터페이스만** 두고 구현을 Android 모듈에 둔다.
예: 페이지를 비트맵으로 만드는 일은 `:document` 의 `PagedDocument` 가 아니라
`:reader-pdf` 의 `PageRasterizer` 가 맡는다.

## 이름 바꾸기

패키지명·앱 이름은 `gradle.properties` 의 `reader.namespace` / `reader.appName`
한 줄씩이다. Play 등록 전까지는 자유롭게 바꿀 수 있다.
