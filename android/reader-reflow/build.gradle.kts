plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// EPUB·TXT 리더 화면. 조판은 :core-layout 이 끝내 두었으므로 여기 남은 일은 페이지를
// 좌표 그대로 찍고, 넘김·책갈피·목차를 사람 손에 연결하는 것뿐이다.
//
// :data 에 의존하지 않는다. 책을 여는 것(SAF)은 앱이 하고, 여기는 ReflowDocument 와
// 보관소 인터페이스만 받는다 — 저장 수단이 바뀌어도 리더는 그대로다.
android {
    namespace = providers.gradleProperty("reader.namespace").get() + ".reflow"
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
    api(project(":document"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}
