plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// 저장과 파일 접근. :document 가 정해 둔 보관소 인터페이스를 Room 으로, 바이트 원천을
// SAF Uri 로 구현한다. 규칙(정렬·덮어쓰기·깨진 값 처리)은 여기서, 판단(무엇을 언제
// 저장하나)은 :core-layout 의 ReadingSession 이 한다.
android {
    namespace = providers.gradleProperty("reader.namespace").get() + ".data"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// 스키마를 저장소에 커밋한다. 다음 버전에서 테이블을 바꿀 때 마이그레이션 테스트가
// 이 파일과 비교한다 — 없으면 "업데이트했더니 책갈피가 사라졌다" 를 출시 전에 못 잡는다.
room { schemaDirectory("$projectDir/schemas") }

dependencies {
    api(project(":document"))
    implementation(project(":archive"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.commons.net)
    // SFTP(SSH). 순수 자바라 안드로이드에서 그대로 돈다(BSD).
    implementation(libs.jsch)

    testImplementation(project(":document-testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ftpserver.core)
    testImplementation(libs.sshd.sftp)
}

tasks.withType<Test>().configureEach {
    // RAR · 7z 해제기의 PC 빌드(:archive 의 hostNatives)를 시험이 싣는다.
    dependsOn(":archive:hostNatives")
    systemProperty("java.library.path", rootProject.layout.projectDirectory.dir("archive/build/host-natives").asFile.path)
    // 테스트는 한글 파일 이름을 실제 디스크에 쓴다. 로케일이 POSIX 인 리눅스(클라우드
    // 세션·CI)에서는 JVM 이 경로를 ASCII 로 바꿔 "??.epub" 가 되므로 UTF-8 로 고정한다.
    environment("LC_ALL", "C.UTF-8")
    // Robolectric 은 android-all 을 Gradle 밖에서 Maven Central 로 따로 받는다. 클라우드
    // 세션에서는 429 가 나므로 미러를 넘길 수 있게 한다(설정은 HANDOFF §2).
    providers.gradleProperty("reader.robolectricRepo").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
}

// 서버 시험(진짜 FTPS · SFTP 서버)은 따로 JVM 에서 돌린다. MINA SSHD(시험용 SFTP 서버)가 자바 파일 시스템 제공자를 등록하는데,
// 같은 JVM 의 Robolectric 이 그것을 만들지 못해 그 뒤 시험이 모두 SQLite 네이티브 오류로 무너졌다(전체 시험 129건 — 순서에 따라
// 나타났다 안 나타났다 한다). 보통 시험의 실행 경로에서는 MINA 를 빼, 순서가 바뀌어도 다시 일어나지 않게 한다.
afterEvaluate {
    // 디버그 · 릴리스 둘 다 — `check` 는 두 작업을 모두 부른다. 디버그에만 걸었을 때 릴리스 작업에 MINA 가 남아 `check` 에서
    // SQLite 시험 128건이 무너졌다.
    listOf("Debug", "Release").forEach { variant ->
        val unit = tasks.named<Test>("test${variant}UnitTest")
        // MINA 를 빼기 전의 경로 — 서버 시험은 이것으로 돈다.
        val full = unit.get().classpath
        val servers = tasks.register<Test>("testSyncServers$variant") {
            description = "FTP · FTPS · SFTP server tests in their own JVM (MINA SSHD breaks Robolectric)."
            testClassesDirs = unit.get().testClassesDirs
            classpath = full
            filter.includeTestsMatching("*.FtpConnectionTest")
            filter.isFailOnNoMatchingTests = false
        }
        unit.configure {
            classpath = full.filter { !it.name.startsWith("sshd-") }
            filter.excludeTestsMatching("*.FtpConnectionTest")
            // "--tests '*FtpConnectionTest*'" 처럼 서버 시험만 고르면 이 작업에는 남는 시험이 없다 — 실패로 보지 않는다(서버 시험은 위가 돈다).
            filter.isFailOnNoMatchingTests = false
            dependsOn(servers)
        }
    }
}
