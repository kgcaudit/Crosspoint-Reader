# OLO eBook 릴리스 서명 키를 한 번 만든다.
#
# 왜: 저장소(공개)에 커밋된 개발용 키는 누구나 쓸 수 있다. 공개 배포 전에는 이 PC 에만 있는 키로 서명한다.
#
# 이 파일은 UTF-8 BOM 으로 저장한다(Windows PowerShell 5.1 이 BOM 없는 파일의 한글을 깨뜨린다).

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$dir = Join-Path $HOME '.olo'
$store = Join-Path $dir 'olo-release.keystore'
$gradleDir = Join-Path $HOME '.gradle'
$props = Join-Path $gradleDir 'gradle.properties'

# 이미 있으면 절대 새로 만들지 않는다. 키가 바뀌면 설치된 앱을 업데이트할 수 없어 지웠다 깔아야 하고,
# 그러면 책갈피 · 진도 · 형광펜이 사라진다.
if (Test-Path -LiteralPath $store) {
    Write-Host "키가 이미 있습니다: $store"
    Write-Host '새로 만들지 않습니다. 이 파일과 gradle.properties 의 olo.release.* 줄을 그대로 쓰세요.'
    exit 0
}

# keytool 찾기: JAVA_HOME → Android Studio 가 깐 JDK → PATH.
$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\keytool.exe') }
$candidates += (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr\bin\keytool.exe')
$candidates += 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
$keytool = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $keytool) {
    $found = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($found) { $keytool = $found.Source }
}
if (-not $keytool) {
    Write-Host 'keytool 을 찾지 못했습니다. JDK(Android Studio)를 설치했는지, JAVA_HOME 이 맞는지 확인하세요.'
    exit 1
}

# 암호는 무작위 32자. 사람이 외울 필요가 없다 — gradle.properties 가 기억한다.
$chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
$password = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })

New-Item -ItemType Directory -Force -Path $dir | Out-Null
& $keytool -genkeypair -keystore $store -storetype PKCS12 -alias olo-release -keyalg RSA -keysize 3072 `
    -validity 10000 -storepass $password -keypass $password -dname 'CN=OLO eBook' -noprompt
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $store)) {
    Write-Host '키를 만들지 못했습니다.'
    exit 1
}

# Gradle 이 읽는 곳(저장소 밖)에 적는다. 경로의 \ 는 properties 에서 이스케이프라 / 로 바꾼다.
New-Item -ItemType Directory -Force -Path $gradleDir | Out-Null
$storeForGradle = $store -replace '\\', '/'
Add-Content -LiteralPath $props -Encoding ASCII -Value @(
    '',
    '# OLO eBook release key (make-release-key.ps1). Never commit this.',
    "olo.release.keystore=$storeForGradle",
    "olo.release.password=$password"
)

# 클라우드 세션에서도 같은 키로 서명하려면 이 두 값을 환경 변수로 넣는다(선택).
$b64Path = Join-Path $dir 'olo-release.keystore.base64.txt'
[Convert]::ToBase64String([IO.File]::ReadAllBytes($store)) | Set-Content -LiteralPath $b64Path -Encoding ASCII
$pwPath = Join-Path $dir 'olo-release.password.txt'
$password | Set-Content -LiteralPath $pwPath -Encoding ASCII

Write-Host ''
Write-Host '릴리스 키를 만들었습니다.'
Write-Host "  키 파일      : $store"
Write-Host "  Gradle 설정  : $props (olo.release.* 두 줄)"
Write-Host ''
Write-Host '꼭 할 일: C:\Users\...\.olo 폴더를 USB 나 개인 클라우드에 따로 보관하세요.'
Write-Host '          이 키를 잃으면 앱을 업데이트할 수 없습니다(지웠다 깔아야 합니다).'
Write-Host ''
Write-Host '클라우드 세션에서도 쓰려면(선택) 환경 설정에 변수 두 개를 넣으세요:'
Write-Host "  OLO_RELEASE_KEYSTORE_B64 = $b64Path 파일의 내용"
Write-Host "  OLO_RELEASE_PASSWORD     = $pwPath 파일의 내용"
Write-Host ''
Write-Host '이제 gradlew :app:assembleRelease 로 만든 APK 는 이 키로 서명됩니다(파일 이름에 -devkey 가 없으면 성공).'
