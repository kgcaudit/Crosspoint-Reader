plugins { alias(libs.plugins.kotlin.jvm) }

// 순수 Kotlin. 의존성은 stdlib 와 테스트 라이브러리뿐이며, 여기에 Android 의존을
// 추가하면 안 된다(checkNoPlatformImports 가 막는다). 이 모듈이 순수한 덕분에
// 포맷 파싱과 위치 모델을 기기 없이 JVM 테스트로 검증할 수 있다.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlin.test)
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
