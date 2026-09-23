# Crosspoint-Reader — 안드로이드 전자책 뷰어

이 저장소의 `android/` 는 CrossPoint Reader(ESP32-C3 e-ink 펌웨어)의 **GUI 구성만**
참고해 새로 쓰는 안드로이드 뷰어다. C++ 코드를 옮기는 작업이 아니다 — 원본의 60~70% 는
ESP32 제약 때문에 존재하는 코드다.

**제품 정의**: EPUB · TXT · PDF 를 간편하게 읽고 책갈피를 꽂는다. 그 이상은 v2 다.

작업 브랜치: `claude/android-epub-viewer-plan-y7m3k3`. 요청 없이 PR 을 만들지 않는다.

## 먼저 읽을 것

| 문서 | 내용 |
|---|---|
| `docs/HANDOFF.md` | **현재 상태 · 다음 할 일 · 미결 결정.** 세션을 이어받으면 여기부터 |
| `docs/ANDROID_BUILD_SPEC.md` | 시행계획. §6.5 진행 상황 · §6.6 환경 제약 · §7 캐시 포맷 · §8 CSS 서브셋 |
| `docs/ANDROID_ARCHITECTURE_DECISION.md` | 왜 네이티브 Canvas + 디스크 캐시인가 |
| `docs/LOCAL_SETUP.md` | 윈도우 PC 에서 돌리는 법(프록시 · JDK 버전 등 실제로 걸린 것들) |

## 빌드

```bash
cd android && ./gradlew check          # SDK 없으면 :document + :core-layout 만
./gradlew :app:assembleRelease         # APK (OLO eBook). 화면 확인은 :app:testDebugUnitTest 의 스크린샷
```

안드로이드 SDK 가 없으면 `settings.gradle.kts` 가 순수 Kotlin 모듈만 구성한다. 그게
정상 동작이고, 로그가 영어인 것도 의도다(한국어 윈도우 콘솔은 CP949 라서 깨진다).

## 지켜야 하는 규칙

1. **`:document` 와 `:core-layout` 에 `android.*` / `androidx.*` 를 넣지 않는다.**
   두 겹으로 막혀 있다 — SDK 없이 구성되는 것 자체, 그리고 `checkNoPlatformImports`.
   이 순수성 덕분에 파일 바이트부터 픽셀 좌표까지 기기 없이 검증된다.
2. **플랫폼 경계는 `TextMeasurer` 하나다.** 새 플랫폼 의존을 코어에 끌어들이기 전에
   이 경계로 표현할 수 없는지 먼저 본다.
3. **저장하는 위치는 언제나 `Locator`(글자 오프셋)다.** 페이지 번호를 저장하면 글자
   크기를 한 번 바꾸는 순간 자리가 미끄러진다.
4. **조판 결과를 바꾸는 설정은 `LayoutSpec` 에 넣는다.** 빠뜨리면 `cacheKey` 가 같아서
   설정을 바꿨는데 옛 페이지가 보인다.
5. **null 과 0/false 를 구분한다.** CSS 선언, `BlockStyle.firstLineIndentEm`,
   `InheritedStyle` 이 모두 "아무도 정하지 않았다" 와 "0 으로 정했다" 를 구분한다.
   섞으면 낮은 우선순위 규칙이 높은 쪽 값을 지운다.
6. **깨진 입력에 관대하다.** 닫히지 않은 태그·주석, 없는 CSS, 어긋난 목차 링크는
   그 부분만 버리고 계속 읽는다. "이 책은 열리지 않습니다" 가 최악이다.

## 코드 규칙

- 주석은 한국어로, **무엇이 아니라 왜**를 적는다. 특히 "이렇게 안 하면 무슨 증상이
  나는가". 기존 파일의 주석 밀도와 어조를 맞춘다.
- 커밋 메시지도 같다. 제목은 영어 한 줄, 본문은 한국어로 선택의 근거와 찾은 버그.
- 테스트 이름은 영어 backtick 문장, 본문 주석은 한국어. 각 테스트가 **사용자가 느끼는
  성질** 하나를 지킨다.
- 새 기능에는 "일부러 망가뜨린 입력이 실제로 걸리는지" 확인하는 테스트를 함께 둔다.
  통과만 확인하는 테스트는 테스트가 아니다.
