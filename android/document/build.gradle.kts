plugins {
    id("org.jetbrains.kotlin.jvm")
    // 시험용 PDF 를 짓는 도구(TestPdf)를 :reader-pdf · :app 의 테스트와 나눠 쓴다.
    `java-test-fixtures`
}

// 순수 Kotlin. 의존성은 stdlib 와 테스트 라이브러리뿐이며, 여기에 Android 의존을
// 추가하면 안 된다(checkNoPlatformImports 가 막는다). 이 모듈이 순수한 덕분에
// 포맷 파싱과 위치 모델을 기기 없이 JVM 테스트로 검증할 수 있다.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlin.test)
    // 테스트 전용. main 은 stdlib 만 쓴다 — suspend 선언 자체는 언어 기능이라
    // 코루틴 라이브러리가 필요 없고, 그걸 호출하는 테스트에만 필요하다.
    testImplementation(libs.coroutines.test)
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
