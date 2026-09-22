plugins { alias(libs.plugins.kotlin.jvm) }

// 조판 코어. R2에서 채운다(블록 모델 · TextMeasurer · LineBreaker · Paginator ·
// PageStore 포맷). :document 와 마찬가지로 순수 Kotlin을 유지한다.
dependencies {
    implementation(project(":document"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlin.test)
    // 테스트 전용. main 은 stdlib 만 쓴다 — suspend 선언 자체는 언어 기능이라
    // 코루틴 라이브러리가 필요 없고, 그걸 호출하는 테스트에만 필요하다.
    testImplementation(libs.coroutines.test)
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
