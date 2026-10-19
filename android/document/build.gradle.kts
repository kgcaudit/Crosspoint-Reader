plugins { id("org.jetbrains.kotlin.multiplatform") }

// 순수 Kotlin. 안드로이드와 iOS 가 같은 코드로 책 파일을 읽는다(2026-10-18 사용자 결정 — iOS 앱은 이 코어를 함께 쓴다).
// commonMain 에는 stdlib 만 쓴다: java.* 를 쓰면 iOS 에서 컴파일되지 않는다. 플랫폼마다 다른 것(압축 풀기 · 인코딩 ·
// 정규화)은 expect 로 선언하고 jvmMain / iosMain 이 채운다. android.* 는 어느 쪽에도 넣지 않는다(checkNoPlatformImports).
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
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            implementation(libs.kotlin.test)
            // 테스트 전용. main 은 stdlib 만 쓴다 — suspend 선언 자체는 언어 기능이라
            // 코루틴 라이브러리가 필요 없고, 그걸 호출하는 테스트에만 필요하다.
            implementation(libs.coroutines.test)
            // 시험용 PDF · 압축을 짓는 도구. :reader-pdf · :app 의 테스트와 나눠 쓴다(멀티플랫폼은 test fixtures 를 못 쓴다).
            implementation(project(":document-testing"))
        }
    }
}

dependencies { "jvmTestRuntimeOnly"(libs.junit.platform.launcher) }
tasks.named<Test>("jvmTest") { useJUnitPlatform() }
