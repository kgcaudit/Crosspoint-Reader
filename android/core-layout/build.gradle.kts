plugins { id("org.jetbrains.kotlin.multiplatform") }

// 조판 코어(블록 모델 · TextMeasurer · LineBreaker · Paginator · PageStore 포맷). :document 와 같이 순수 Kotlin 이고
// 안드로이드와 iOS 가 함께 쓴다 — 플랫폼 경계는 TextMeasurer 하나다.
kotlin {
    jvmToolchain(21)
    // expect/actual 클래스(io/ 의 InputStream · Inflater …)는 아직 베타라 컴파일할 때마다 경고가 난다. 쓰기로 정했으니 끈다.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    jvm()
    // iOS 는 맥에서만 컴파일 · 링크된다. 리눅스(이 저장소의 클라우드 세션)에서는 꺼진 채로 두고, 공통 코드(commonMain)가
    // iOS 에서도 통하는지는 compileCommonMainKotlinMetadata 가 검사한다 — java.* 를 쓰면 여기서 깨진다.
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        // 플랫폼과 무관한 시험은 commonTest 에 둔다(2026-10-21, iOS 세션 요청) — 맥에서는 같은 시험이 iOS(iosSimulatorArm64Test)로도
        // 돈다. 두 앱이 같은 책을 다르게 읽으면 여기서 걸린다. java.* 를 쓰는 시험(시험용 zip · 그림 짓기 · 파일)은 jvmTest 에 남는다.
        commonTest.dependencies { implementation(libs.kotlin.test) }
        commonMain.dependencies { implementation(project(":document")) }
        // iOS 의 actual(zlib · iconv · CommonCrypto · NSFileManager)을 시뮬레이터에서 시험한다. 맥에서만 돈다.
        iosTest.dependencies { implementation(libs.kotlin.test) }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}

dependencies { "jvmTestRuntimeOnly"(libs.junit.platform.launcher) }
tasks.named<Test>("jvmTest") { useJUnitPlatform() }
