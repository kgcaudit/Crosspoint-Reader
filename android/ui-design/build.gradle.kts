plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CrossPoint GUI 의 부품(헤더·목록·상태바·팝업·탭·진행바)과 색 토큰. 화면 배치는 여기
// 없고 부품만 있다 — 부품을 바꾸면 모든 화면이 같이 바뀌는 것이 이 모듈의 존재 이유다.
//
// Material 을 쓰지 않는다. 모양을 CrossPoint 쪽으로 직접 정하고, APK 에서 Material 한
// 벌(수 MB)을 뺀다. foundation 만으로 부품 10개는 충분하다.
android {
    namespace = providers.gradleProperty("reader.namespace").get() + ".ui.design"
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
    // UI 글꼴(Pretendard)이 거기 있다. 본문을 재는 글꼴과 같은 파일을 쓴다.
    api(project(":text-platform"))
    api(platform(libs.compose.bom))
    api(libs.compose.foundation)
    api(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
