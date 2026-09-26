plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 듣기(TTS). EPUB 과 PDF 가 함께 쓴다 — 리더는 [ListenSource] 로 "장(또는 쪽) 하나의 글과 문장" 을 내주고,
// 여기는 문장을 엔진에 넘기고 쪽을 따라가게 하고 화면을 끈 채 읽는 일(서비스 · 미디어 세션)만 한다.
// 0.18.0 에서 EPUB 모듈에서 떼어 냈다(PDF 듣기가 EPUB 리더에 기대지 않게).
android {
    namespace = providers.gradleProperty("reader.namespace").get() + ".listen"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":ui-design"))
    api(project(":core-layout"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}
