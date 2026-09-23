# Reader 코어 테스트 실행기.
#
# Windows PowerShell 5.1 은 BOM 이 없는 .ps1 을 ANSI 로 읽는다. 이 파일은 UTF-8 BOM
# 으로 저장해야 한글 주석·메시지가 깨지지 않는다.

# gradlew 는 진행 상황을 표준오류로도 내보낸다. 'Stop' 이면 그걸 예외로 보고 중단하므로
# 반드시 'Continue' 여야 한다.
$ErrorActionPreference = 'Continue'
Set-Location -LiteralPath $PSScriptRoot

# Gradle 은 로그를 UTF-8 로 내보내는데 한국어 Windows 콘솔은 기본이 CP949 라 한글이
# 깨진다. 콘솔 쪽을 UTF-8 로 맞춘다(실패해도 빌드에는 지장 없으므로 무시한다).
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

Write-Host '================================================'
Write-Host ' Reader - core tests'
Write-Host '================================================'
Write-Host ''

# 사내망이 HTTPS 를 가로채는 경우(TLS 검사 프록시) Java 가 인증서를 거부해
# "PKIX path building failed" 로 다운로드가 실패한다. 회사가 배포한 루트 인증서는
# 이미 Windows 인증서 저장소에 있으므로(그래서 브라우저는 된다) Java 도 그걸 쓰게 한다.
# 프록시가 없어도 Windows 저장소에는 표준 공인 인증서가 들어 있어 그대로 동작한다.
#
# JAVA_TOOL_OPTIONS 인 이유: Gradle 배포본을 내려받는 래퍼 JVM 과 그 뒤에 라이브러리를
# 내려받는 데몬 JVM 이 서로 다른 프로세스인데, 이 변수는 둘 다에 붙는다.
# 이미 같은 설정이 들어 있으면 덧붙이지 않는다(손으로 설정해 둔 경우 두 번 붙는다).
$tlsOption = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'
if ($env:JAVA_TOOL_OPTIONS -notlike ('*' + $tlsOption + '*')) {
    $env:JAVA_TOOL_OPTIONS = ($tlsOption + ' ' + $env:JAVA_TOOL_OPTIONS).Trim()
}

# ── 쓸 수 있는 JDK 찾기 ────────────────────────────────────────────
#
# 이 프로젝트는 JDK 17~21 이 필요하다. Gradle 8.14 는 Java 25 를 알지 못해
# "What went wrong: 25.0.4.1" 처럼 버전 문자열만 찍고 죽는다 — 원인을 짐작하기
# 어려운 메시지라 여기서 먼저 걸러 안내한다.
#
# Gradle 을 9 로 올리지 않는 이유: Android Gradle Plugin 8.x 가 Gradle 9 를 지원하지
# 않아 나중에 Android 층에서 막힌다. JDK 21 이 Android 개발의 표준이다.

function Get-JavaVersionInfo([string]$JavaExe) {
    $output = & $JavaExe -version 2>&1 | Out-String

    # 'version' 이 든 줄만 본다. JAVA_TOOL_OPTIONS 가 설정돼 있으면 JVM 이
    # "Picked up JAVA_TOOL_OPTIONS: ..." 를 먼저 찍으므로 첫 줄을 잡으면 틀린다.
    $match = [regex]::Match($output, 'version "([0-9][0-9._]*)')
    if (-not $match.Success) { return $null }

    $version = $match.Groups[1].Value
    $parts = $version.Split('.')
    # "1.8.0_x" 는 major 가 두 번째 조각, "21.0.5" 는 첫 조각.
    if ($parts[0] -eq '1' -and $parts.Length -gt 1) {
        $major = [int]$parts[1]
    } else {
        $major = [int]$parts[0]
    }
    return @{ Version = $version; Major = $major }
}

# 후보를 넓게 모은다.
#
# PATH 의 첫 java 만 보면 안 된다 — 시스템에 Java 25 가 앞에 있으면, JDK 21 을 새로
# 설치해도 그것만 보고 "쓸 수 있는 JDK 없음" 이 된다. 그래서 흔한 설치 위치를 전부
# 훑고, PATH 도 첫 항목이 아니라 전부 본다.
#
# Android Studio 의 JDK 를 맨 앞에 두는 이유: Android 개발에서 쓰는 바로 그 버전이고,
# 시스템에 최신 Java 가 따로 깔려 있어도 영향을 받지 않는다.
$searchPaths = @(
    @{ Pattern = (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'); From = 'Android Studio' }
    @{ Pattern = (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'); From = 'Android Studio' }
    @{ Pattern = (Join-Path $env:ProgramFiles 'Eclipse Adoptium\jdk-*'); From = 'Temurin' }
    @{ Pattern = (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium\jdk-*'); From = 'Temurin' }
    @{ Pattern = (Join-Path $env:ProgramFiles 'Java\jdk-*'); From = 'Java' }
    @{ Pattern = (Join-Path $env:ProgramFiles 'Microsoft\jdk-*'); From = 'Microsoft OpenJDK' }
    @{ Pattern = (Join-Path $env:ProgramFiles 'Amazon Corretto\jdk*'); From = 'Corretto' }
    @{ Pattern = (Join-Path $env:ProgramFiles 'Zulu\zulu-*'); From = 'Zulu' }
    @{ Pattern = (Join-Path $env:ProgramFiles 'JetBrains\*\jbr'); From = 'JetBrains' }
)

$candidates = @()
$searched = @()

foreach ($entry in $searchPaths) {
    $searched += $entry.Pattern
    # Resolve-Path 는 와일드카드와 정확한 경로를 모두 처리한다.
    $resolved = Resolve-Path -Path $entry.Pattern -ErrorAction SilentlyContinue
    foreach ($item in $resolved) {
        $candidates += @{ Home = $item.Path; From = $entry.From }
    }
}

if ($env:JAVA_HOME) {
    $candidates += @{ Home = $env:JAVA_HOME; From = 'JAVA_HOME' }
    $searched += $env:JAVA_HOME
}

# PATH 의 java 를 전부 본다(-All). 첫 항목만 보면 앞에 있는 Java 25 에 가려진다.
foreach ($command in (Get-Command java -All -ErrorAction SilentlyContinue)) {
    $binDir = Split-Path -Parent $command.Source
    $candidates += @{ Home = (Split-Path -Parent $binDir); From = 'PATH' }
}

$picked = $null
foreach ($candidate in $candidates) {
    if (-not $candidate.Home) { continue }
    $exe = Join-Path $candidate.Home 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $exe)) { continue }

    $info = Get-JavaVersionInfo $exe
    if (-not $info) { continue }

    if ($info.Major -lt 17 -or $info.Major -gt 21) {
        Write-Host (' 건너뜀: {0} 의 Java {1} (이 프로젝트는 17~21 이 필요합니다)' -f $candidate.From, $info.Version)
        continue
    }

    $picked = @{ Home = $candidate.Home; From = $candidate.From; Version = $info.Version }
    break
}

if (-not $picked) {
    Write-Host ''
    Write-Host ' [FAIL] 쓸 수 있는 JDK(17~21)를 찾지 못했습니다.'
    Write-Host ''
    Write-Host '   둘 중 하나를 설치하면 됩니다:'
    Write-Host '     Android Studio : https://developer.android.com/studio'
    Write-Host '     JDK 21         : https://adoptium.net  (Temurin 21 LTS, Windows x64 .msi)'
    Write-Host ''
    Write-Host '   이미 설치했다면 이 창을 닫고 새로 연 뒤 다시 실행해 주세요.'
    Write-Host ''
    Write-Host '   찾아본 위치:'
    foreach ($location in $searched) {
        Write-Host ('     ' + $location)
    }
    exit 1
}

$env:JAVA_HOME = $picked.Home
Write-Host (' Java : {0}' -f $picked.Home)
Write-Host ('        버전 {0} ({1})' -f $picked.Version, $picked.From)
Write-Host ''
Write-Host ' 테스트를 실행합니다. 처음 실행은 몇 분 걸립니다(다운로드).'
Write-Host ''

$gradlew = Join-Path $PSScriptRoot 'gradlew.bat'
$logFile = Join-Path $env:TEMP 'reader-gradle.log'

function Invoke-Checks {
    & $gradlew ':document:check' ':core-layout:check' 2>&1 | Tee-Object -FilePath $logFile
    $code = $LASTEXITCODE
    $log = ''
    if (Test-Path -LiteralPath $logFile) {
        $log = Get-Content -LiteralPath $logFile -Raw -ErrorAction SilentlyContinue
    }
    return @{ Code = $code; Log = $log }
}

$result = Invoke-Checks

# Gradle 이 임시 폴더를 최종 위치로 옮기지 못하는 경우가 Windows 에서 종종 있다.
# 이전 실행이 중간에 죽어 캐시가 반쯤 남았거나, 보안 소프트웨어가 갓 만들어진
# 파일을 검사하며 잠그고 있을 때다. 캐시를 지우고 한 번만 다시 시도한다 —
# 사용자가 원인을 짐작하기 어려운 오류라 여기서 자동으로 푸는 편이 낫다.
if ($result.Code -ne 0 -and $result.Log -match 'Could not move temporary workspace') {
    Write-Host ''
    Write-Host ' Gradle 캐시가 꼬였습니다. 정리하고 한 번 다시 시도합니다...'
    Write-Host ''

    & $gradlew '--stop' 2>&1 | Out-Null

    foreach ($cache in (Resolve-Path (Join-Path $env:USERPROFILE '.gradle\caches\*\transforms') -ErrorAction SilentlyContinue)) {
        Remove-Item -LiteralPath $cache.Path -Recurse -Force -ErrorAction SilentlyContinue
    }

    $result = Invoke-Checks
}

$exitCode = $result.Code

Write-Host ''
if ($exitCode -eq 0) {
    Write-Host '================================================'
    Write-Host ' [OK] 성공! 모든 테스트가 통과했습니다.'
    Write-Host ''
    Write-Host ' 자세한 보고서:'
    Write-Host '   document\build\reports\tests\test\index.html'
    Write-Host '   core-layout\build\reports\tests\test\index.html'
    Write-Host '================================================'
} else {
    Write-Host '================================================'
    Write-Host ' [FAIL] 실패했습니다.'
    Write-Host ''
    Write-Host ' 위에 나온 메시지를 그대로 복사해서 알려주세요.'
    Write-Host ' docs\LOCAL_SETUP.md 6장에 흔한 증상별 해결이 있습니다.'
    if ($result.Log -match 'Could not move temporary workspace') {
        Write-Host ''
        Write-Host ' 캐시를 지우고 다시 시도했는데도 같은 오류가 납니다.'
        Write-Host ' 보안 소프트웨어가 아래 폴더를 검사하며 잠그고 있을 가능성이 큽니다.'
        Write-Host ' 사내 IT 에 이 폴더의 실시간 검사 제외를 요청해 보세요:'
        Write-Host ('   ' + (Join-Path $env:USERPROFILE '.gradle'))
    }
    Write-Host '================================================'
}

exit $exitCode
