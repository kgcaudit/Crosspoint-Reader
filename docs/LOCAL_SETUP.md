# 로컬에서 확인하기 (Windows)

현재 상태를 내 컴퓨터에서 직접 돌려 보는 절차. **20~30분** 걸린다(대부분 다운로드 대기).

---

## 0. 무엇을 확인하게 되나

지금까지 만든 것은 **파일을 읽고 페이지로 조판하는 엔진**이다. 화면이 있는 앱은 아직
없다. 그래서 확인하는 것은 "앱을 켜 보는 것"이 아니라 **테스트 222개가 통과하는지**다.

```
:document     143건   EPUB·TXT 읽기 (인코딩 감지, zip, 파서)
:core-layout   79건   줄바꿈·페이지 분할·캐시
```

화면이 나오는 것은 R1(2주)의 PDF 뷰어부터다.

---

## 1. Android Studio 설치 (약 10분)

앞으로 Android 앱을 만들 것이므로 이걸 깔면 **필요한 것이 다 들어온다**(JDK 21 + Android SDK).
JDK만 따로 깔 수도 있지만, 어차피 다음 단계에서 Android Studio가 필요하다.

1. https://developer.android.com/studio 에서 **Download Android Studio** 클릭
2. 내려받은 `.exe` 실행 → 전부 **Next / 기본값**으로 설치
3. 설치 후 처음 실행하면 설정 마법사가 뜬다 → **Standard** 선택 → Next → Finish
   - SDK를 몇 GB 내려받는다. 커피 한 잔.
4. 마법사가 끝나고 시작 화면이 나오면 성공

> 회사 PC라 설치 권한이 없으면 §6 를 보라.

---

## 2. Git 설치 (약 3분)

1. https://git-scm.com/download/win → 자동으로 내려받아진다
2. `.exe` 실행 → **전부 Next**(기본값이 안전하다) → Install

---

## 3. 코드 받기 (약 1분)

1. 시작 메뉴에서 **PowerShell** 실행
2. 아래를 **한 줄씩** 복사해 붙여넣고 Enter

```powershell
cd $HOME\Documents
git clone -b claude/android-epub-viewer-plan-y7m3k3 https://github.com/kgcaudit/Crosspoint-Reader.git
cd Crosspoint-Reader\android
```

GitHub 로그인을 물으면 계정(kgcaudit)으로 로그인한다. 브라우저 창이 뜨면 거기서 승인.

---

## 4. 테스트 실행 (첫 실행 약 5분)

같은 PowerShell 창에서:

```powershell
.\gradlew.bat :document:check :core-layout:check
```

첫 실행은 Gradle(약 130MB)과 라이브러리를 내려받아 몇 분 걸린다. 두 번째부터는 몇 초.

### 성공하면 이렇게 나온다

```
[reader] Android SDK 없음 → JVM 코어 모듈(:document, :core-layout)만 구성합니다. ...

BUILD SUCCESSFUL in 1m 12s
```

> **첫 줄의 "Android SDK 없음" 은 정상이다.** PowerShell 은 Android Studio 가 깐 SDK
> 위치를 모르기 때문이고, 애초에 이 테스트는 SDK 가 필요 없다. 오히려 "SDK 없이도
> 코어가 돈다" 는 것이 설계 의도다.

`BUILD SUCCESSFUL` 이 나오면 **끝났다.** 222개 테스트가 전부 통과한 것이다.

### 테스트 목록을 보고 싶으면

```powershell
.\gradlew.bat :document:test :core-layout:test --info | Select-String "PASSED"
```

### 보기 좋은 보고서

실행 후 아래 파일을 브라우저로 열면 테스트 목록과 소요 시간이 표로 나온다.

```powershell
start document\build\reports\tests\test\index.html
start core-layout\build\reports\tests\test\index.html
```

---

## 5. 곁들여 볼 것 (선택)

### 5.1 조판 결과를 눈으로 보기

조판 골든 테스트를 일부러 깨뜨리면 실제 조판 결과가 찍힌다.

```powershell
notepad core-layout\src\test\kotlin\io\github\kgcaudit\reader\layout\GoldenPaginationTest.kt
```

파일 아래쪽 `GOLDEN_LINE_COUNTS = listOf(6, 6)` 을 `listOf(5, 5)` 로 바꿔 저장하고:

```powershell
.\gradlew.bat :core-layout:test --tests "*GoldenPagination*"
```

실패 메시지에 이런 게 나온다:

```
p0 [0, 108)
  | 어린 왕자는 사막에 떨어진 조종사
  | 를 만났다. 그는 양 한 마리를 그려
  | 달라고 부탁했고, 조종사는 세 번을
p1 [108, 209)
  | 여우는 말했다. "가장 중요한 것은
  | 눈에 보이지 않아." 길들인다는 것은
```

한글이 글자 단위로 끊기고, 어절 공백이 보존되고, 첫 줄이 들여써진 것이 보인다.
확인했으면 **`6, 6` 으로 되돌려 놓는다**(`git checkout .` 로도 된다).

### 5.2 의존 규칙 가드가 실제로 막는지

코어에 Android 코드가 섞이면 빌드를 실패시키는 장치다.

```powershell
"package io.github.kgcaudit.reader.document`nimport android.graphics.Bitmap" | Out-File -Encoding utf8 document\src\main\kotlin\io\github\kgcaudit\reader\document\_Probe.kt
.\gradlew.bat :document:checkNoPlatformImports
```

이렇게 나오면 정상이다:

```
[:document] 순수 Kotlin 모듈에 플랫폼 임포트가 있습니다:
  - main/kotlin/.../_Probe.kt: import android.
```

확인 후 지운다:

```powershell
del document\src\main\kotlin\io\github\kgcaudit\reader\document\_Probe.kt
```

### 5.3 Android Studio 로 코드 열어 보기

1. Android Studio → **Open**
2. `Documents\Crosspoint-Reader\android` 폴더 선택 (`Crosspoint-Reader` 가 아니라 **android**)
3. 오른쪽 아래 Gradle sync 가 끝나기를 기다린다
4. 왼쪽 트리에서 `document` / `core-layout` 을 펼쳐 코드를 본다
5. 테스트 파일을 열고 클래스 이름 왼쪽 ▶ 를 누르면 그 테스트만 돈다

---

## 6. 막혔을 때

| 증상 | 원인과 해결 |
|---|---|
| `gradlew.bat : 용어가 ... 인식되지 않습니다` | `android` 폴더가 아닌 곳에 있다. `cd $HOME\Documents\Crosspoint-Reader\android` |
| `git : 용어가 ... 인식되지 않습니다` | Git 설치 후 **PowerShell 을 닫고 새로 열어야** PATH 가 반영된다 |
| `JAVA_HOME is not set` / `No Java` | JDK 가 없다. Android Studio 를 깔았다면 그 안의 JDK 를 알려 준다:<br>`$env:JAVA_HOME="$env:LOCALAPPDATA\Programs\Android Studio\jbr"`<br>(경로가 다르면 Android Studio → Settings → Build → Gradle → Gradle JDK 에서 실제 경로 확인) |
| 회사 PC 라 설치를 못 한다 | Android Studio 대신 **JDK 21 만** 깔면 된다(설치 파일이 작다): https://adoptium.net → Temurin 21 (LTS) → Windows x64 `.msi`. Android 앱 빌드는 나중에 필요하고, 지금 확인에는 JDK 만으로 충분하다 |
| 다운로드가 계속 실패한다 | 사내 프록시일 가능성이 크다. `android\gradle.properties` 맨 아래에 추가:<br>`systemProp.https.proxyHost=프록시주소`<br>`systemProp.https.proxyPort=포트`<br>(주소·포트는 사내 IT 에 문의) |
| `Could not GET ... 429` | 일시적 속도 제한이다. 1~2분 뒤 같은 명령을 다시 실행 |
| 테스트가 실패한다 | **알려 주세요.** 실패한 테스트 이름과 메시지를 그대로 붙여 주면 원인을 찾겠습니다 |

---

## 7. 확인이 끝나면

`BUILD SUCCESSFUL` 을 봤다고 알려 주세요. 그러면 다음 단계로 갑니다:

1. **`:text-platform`** — `TextMeasurer` 를 실제 `Paint` 로 구현. 지금 222개 테스트가
   가짜 측정기로 돌고 있는데, 실제 폰트로 한 번 돌려 조판 코어의 가정을 실물로 검증한다
2. **`:data`** — Room · SAF · 캐시 파일 입출력
3. **`:reader-pdf`** — 화면이 처음 나오는 단계 (PDF 뷰어 + 책갈피)

그리고 그때 결정할 것 네 가지:

| # | 결정 | 권장 |
|---|---|---|
| P1 | PDF 렌더러 | 플랫폼 내장 `PdfRenderer`(APK 0바이트, 단 PDF 목차·검색 없음) |
| B1 | 앱 이름 / 패키지명 | 지금 `io.github.kgcaudit.reader` / "Reader" 로 잠정. Play 등록 전까지 자유 |
| B2 | 폰트 번들 | KoPub 바탕 + Pretendard (+6~10MB) |
| B3 | 테스트 코퍼스 | 보유 EPUB 20권 · TXT 10개 · PDF 10개 |

