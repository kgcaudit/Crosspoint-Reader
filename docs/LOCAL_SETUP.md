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

## 1. JDK 설치 (약 3분)

**시스템에 Java 가 이미 있어도 그것으로는 안 될 수 있다.** 이 프로젝트는 JDK **17~21**
이 필요하다(Gradle 8.14 가 Java 22 이상을 모른다). 최신 Java 25 가 깔려 있으면
`run-tests.bat` 이 그걸 건너뛰고 "쓸 수 있는 JDK 없음" 이라고 알려 준다.

### 지금 확인만 하려면 — **Temurin JDK 21** (권장, 설치 3분)

1. https://adoptium.net 접속
2. **Temurin 21 (LTS)** → **Windows** → **x64** → `.msi` 내려받기
3. 실행 → Next → **`Set JAVA_HOME variable` 항목을 켠다**
   (목록에서 그 항목을 클릭해 `Will be installed on local hard drive` 선택)
4. Next → Install → Finish
5. **PowerShell 을 닫고 새로 연다** (환경변수가 반영되려면 필요하다)

기존 Java 25 는 지워도 되고 그대로 둬도 된다. `run-tests.bat` 이 둘 다 보고
17~21 인 것을 골라 쓴다.

### 나중에 Android 앱을 만들 때 — **Android Studio**

JDK 21 과 Android SDK 가 함께 들어오므로, 그때는 이것만 설치하면 된다.
지금 단계(코어 테스트 확인)에는 필요 없고 다운로드가 몇 GB 라 오래 걸린다.

1. https://developer.android.com/studio → **Download Android Studio**
2. `.exe` 실행 → 전부 **Next / 기본값**
3. 첫 실행 마법사 → **Standard** → Next → Finish

> `run-tests.bat` 은 Android Studio 의 JDK, Temurin, Microsoft OpenJDK, Corretto,
> Zulu, JetBrains, `JAVA_HOME`, `PATH` 를 전부 훑어 쓸 수 있는 것을 고른다.
> 어디에 설치하든 대개 알아서 찾는다.

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

### 방법 A — 더블클릭 (권장)

**타이핑이 필요 없다.**

1. 파일 탐색기에서 `문서\Crosspoint-Reader\android` 폴더로 간다
2. **`run-tests.bat`** 을 **더블클릭**
3. 창이 열리고 알아서 실행된다. 첫 실행은 다운로드 때문에 몇 분 걸린다
4. 끝나면 결과가 나오고 창은 `계속하려면 아무 키나 누르십시오` 상태로 **멈춰 있다**

실행하면 어떤 Java 를 쓰는지 먼저 알려 준다:

```
 건너뜀: PATH 의 Java 25.0.4.1 (이 프로젝트는 17~21 이 필요합니다)
 Java : C:\Users\user\AppData\Local\Programs\Android Studio\jbr
        버전 21.0.6 (Android Studio)
```

성공하면:

```
================================================
 [OK] 성공! 모든 테스트가 통과했습니다.
================================================
```

> **이 스크립트가 알아서 처리하는 것 두 가지.** 사내 TLS 검사 프록시에 맞춰 Java 가
> Windows 인증서 저장소를 쓰게 하고(§6.1), 시스템에 Gradle 이 지원하지 않는 Java 가
> 깔려 있어도 쓸 수 있는 JDK 를 골라 쓴다(§6.2). 그래서 `.\gradlew.bat` 을 직접
> 실행할 때와 달리 추가 설정이 필요 없다.

macOS·Linux 라면 터미널에서 `./run-tests.sh` 를 실행한다.

### 방법 B — 명령을 직접 입력

터미널에 익숙하거나, 다른 테스트만 골라 돌리고 싶을 때.

1. 시작 메뉴에서 **PowerShell** 을 찾아 실행 (검은 창이 열린다)
2. 아래를 복사해서 창에 **마우스 우클릭**(붙여넣기) 하고 **Enter**

```powershell
cd $HOME\Documents\Crosspoint-Reader\android
```

3. 이어서 아래를 같은 방법으로 붙여넣고 **Enter**

```powershell
.\gradlew.bat :document:check :core-layout:check
```

이 한 줄이 뜻하는 것:

| 조각 | 뜻 |
|---|---|
| `.\gradlew.bat` | 지금 폴더(`.`)에 있는 `gradlew.bat` 실행 |
| `:document:check` | `document` 모듈의 검사·테스트 실행 |
| `:core-layout:check` | `core-layout` 모듈의 검사·테스트 실행 |

성공하면 마지막에 `BUILD SUCCESSFUL` 이 나온다.

> PowerShell 에서는 **우클릭이 붙여넣기**다(Ctrl+V 도 최신 Windows 에서는 된다).
> 여러 줄을 한꺼번에 붙여넣지 말고 한 줄씩 Enter 를 누른다.

### 첫 줄에 나오는 이 메시지는 정상이다

```
[reader] Android SDK 없음 → JVM 코어 모듈(:document, :core-layout)만 구성합니다. ...
```

PowerShell 은 Android Studio 가 깐 SDK 위치를 모르기 때문이고, 이 테스트는 애초에
SDK 가 필요 없다. 오히려 "SDK 없이도 코어가 돈다" 는 것이 설계 의도다.

### 보기 좋은 보고서

실행 후 아래 파일을 브라우저로 열면 테스트 목록과 소요 시간이 표로 나온다.
파일 탐색기에서 더블클릭해도 된다.

```
android\document\build\reports\tests\test\index.html
android\core-layout\build\reports\tests\test\index.html
```

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

### 5.4 휴대폰에 설치하기 — "내 파일" 앱으로

메신저 · 브라우저로 받은 APK 를 바로 열어 설치하면 삼성 휴대폰이 "잠재적으로 유해한 앱 — 피싱 시도 후에
설치되었습니다" 로 막아 실행되지 않는다. APK 를 휴대폰에 옮긴 뒤 **삼성 "내 파일" 앱에서 눌러 설치**한다.

### 5.5 비공개 릴리스 키 (선택 — 공개 배포 전에)

지금 APK 는 저장소에 들어 있는 개발용 키로 서명한다(파일 이름의 `-devkey`). 저장소가 공개라 누구나 같은 키로
서명할 수 있으므로, 남에게 배포하기 전에는 이 PC 에만 있는 키로 바꾼다.

1. `android\make-release-key.bat` 을 더블클릭한다. `C:\Users\<이름>\.olo\` 에 키가 생기고,
   `C:\Users\<이름>\.gradle\gradle.properties` 에 `olo.release.*` 두 줄이 붙는다.
2. **`.olo` 폴더를 USB 나 개인 클라우드에 따로 보관한다.** 이 키를 잃으면 앱을 업데이트할 수 없다.
3. (클라우드 세션이 APK 를 만들게 하려면) 클라우드 환경 설정 → 환경 변수에 두 개를 넣는다:
   `OLO_RELEASE_KEYSTORE_B64` = `.olo\olo-release.keystore.base64.txt` 의 내용,
   `OLO_RELEASE_PASSWORD` = `.olo\olo-release.password.txt` 의 내용. 채팅창에는 붙여 넣지 않는다.

키를 바꾼 첫 APK 는 옛 앱 위에 덮어 깔리지 않는다 — 지우고 깔아야 하고 책갈피 · 진도 · 형광펜이 사라진다.

## 6. 막혔을 때

### 6.1 `PKIX path building failed` / `SSLHandshakeException` — **사내망에서 가장 흔하다**

```
Exception in thread "main" javax.net.ssl.SSLHandshakeException: (certificate_unknown)
PKIX path building failed: ... unable to find valid certification path to requested target
```

**원인.** 회사 네트워크가 HTTPS 를 가로채 검사한다(TLS 검사 프록시). 회사가 자체
인증서로 연결을 끊었다 다시 맺는데, Java 는 그 인증서를 모르니 거부한다. 브라우저가
되는 이유는 회사가 그 인증서를 **Windows 인증서 저장소**에 이미 깔아 뒀기 때문이다.

**해결.** Java 도 Windows 인증서 저장소를 쓰게 한다.

`run-tests.bat` 에는 이 설정이 **이미 들어 있다.** 최신 코드를 받았는지 확인하고
더블클릭하면 된다:

```powershell
cd $HOME\Documents\Crosspoint-Reader
git pull
```

직접 명령을 입력하는 경우에는 PowerShell 에서 먼저 이 줄을 실행한다(창을 닫으면
사라지므로 새 창마다 한 번씩):

```powershell
$env:JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

그 다음 평소대로:

```powershell
.\gradlew.bat :document:check :core-layout:check
```

`Picked up JAVA_TOOL_OPTIONS: ...` 줄이 먼저 나오는데 **정상**이다.

> **왜 `JAVA_TOOL_OPTIONS` 인가**: Gradle 배포본을 내려받는 래퍼 JVM 과, 그 뒤에
> 라이브러리를 내려받는 데몬 JVM 이 서로 다른 프로세스다. 이 환경변수는 둘 다에
> 적용되므로 한 번에 해결된다.

**영구 적용**(매번 안 쳐도 되게):

```powershell
[Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", "-Djavax.net.ssl.trustStoreType=Windows-ROOT", "User")
```

실행 후 PowerShell 을 닫고 새로 연다.

**그래도 안 되면** — 프록시가 인증서 검사뿐 아니라 접속 자체를 막는 경우다.
사내 IT 에 프록시 주소와 포트를 물어 `android\gradle.properties` 맨 아래에 추가한다:

```
systemProp.https.proxyHost=프록시주소
systemProp.https.proxyPort=포트
systemProp.http.proxyHost=프록시주소
systemProp.http.proxyPort=포트
```

**가장 빠른 우회** — 집 PC 나 개인 네트워크(휴대폰 테더링 포함)에서 한 번 실행한다.
한 번 내려받으면 `C:\Users\<사용자>\.gradle` 에 캐시되어 이후에는 사내망에서도
네트워크 없이 돈다.

### 6.2 `What went wrong:` 뒤에 `25.0.4.1` 같은 숫자만 나온다

```
FAILURE: Build failed with an exception.
* What went wrong:
25.0.4.1
```

**원인.** 그 숫자는 **Java 버전**이다. PC 에 Java 25 가 깔려 있는데 Gradle 8.14.3 이
그 버전을 모른다(Java 25 는 Gradle 9.1 부터 지원한다). 버전을 해석하지 못해 숫자만
찍고 죽는다.

**Gradle 을 9 로 올리지 않는다.** Android Gradle Plugin 8.x 가 Gradle 9 를 지원하지
않아 나중에 Android 층에서 막힌다. **JDK 21 을 쓰는 것이 맞다** — Android 개발의
표준이고 Android Studio 가 그 버전을 함께 설치한다.

**해결 1 — `run-tests.bat` 을 쓴다 (권장).**

최신 코드를 받으면 배치 파일이 **쓸 수 있는 JDK 를 알아서 찾는다.** 시스템에 Java 25
가 있어도 Android Studio 의 JDK 21 을 골라 쓰고, 쓸 수 있는 것이 없으면 무엇을
설치해야 하는지 알려 준다.

```powershell
cd $HOME\Documents\Crosspoint-Reader
git pull
```

그다음 `android\run-tests.bat` 더블클릭. 실행하면 첫 줄에 어떤 Java 를 쓰는지 나온다:

```
 Java : C:\Users\user\AppData\Local\Programs\Android Studio\jbr  (버전 21, Android Studio)
```

**해결 2 — 직접 지정한다.** Android Studio 를 설치했다면:

```powershell
$env:JAVA_HOME="$env:LOCALAPPDATA\Programs\Android Studio\jbr"
.\gradlew.bat :document:check :core-layout:check
```

**해결 3 — JDK 21 을 설치한다.** Android Studio 가 없거나 위 경로가 틀리면:

https://adoptium.net → **Temurin 21 (LTS)** → Windows x64 `.msi` → 설치 중
**"Set JAVA_HOME variable"** 항목을 **켜** 둔다. 설치 후 PowerShell 을 닫고 새로 연다.

> 지금 쓰는 Java 버전이 궁금하면: `java -version`

### 6.3 그 밖의 증상

| 증상 | 원인과 해결 |
|---|---|
| `gradlew.bat : 용어가 ... 인식되지 않습니다` | `android` 폴더가 아닌 곳에 있다. `cd $HOME\Documents\Crosspoint-Reader\android` |
| `git : 용어가 ... 인식되지 않습니다` | Git 설치 후 **PowerShell 을 닫고 새로 열어야** PATH 가 반영된다 |
| `JAVA_HOME is not set` / `No Java` | JDK 가 없다. `run-tests.bat` 은 Android Studio 가 깐 JDK 를 알아서 찾으니 그걸 쓰면 된다. 직접 지정하려면:<br>`$env:JAVA_HOME="$env:LOCALAPPDATA\Programs\Android Studio\jbr"` |
| 회사 PC 라 설치를 못 한다 | Android Studio 대신 **JDK 21 만** 깔면 된다: https://adoptium.net → Temurin 21 (LTS) → Windows x64 `.msi` |
| `Could not GET ... 429` | 일시적 속도 제한이다. 1~2분 뒤 다시 실행 |
| 테스트가 실패한다 | **알려 주세요.** 실패한 테스트 이름과 메시지를 그대로 붙여 주면 원인을 찾겠습니다 |

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

