plugins { alias(libs.plugins.kotlin.jvm) }

// 조판 코어. R2에서 채운다(블록 모델 · TextMeasurer · LineBreaker · Paginator ·
// PageStore 포맷). :document 와 마찬가지로 순수 Kotlin을 유지한다.
dependencies {
    implementation(project(":document"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlin.test)
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
