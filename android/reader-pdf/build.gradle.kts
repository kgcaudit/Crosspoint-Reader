plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// PDF 리더 화면. PDF 는 조판하지 않는다 — 페이지는 이미 정해져 있고, 여기서 하는 일은
// 플랫폼 PdfRenderer 로 **보이는 만큼만** 그려 넘기고·확대하고·책갈피를 꽂는 것이다(결정 P1 ①).
// 렌더러는 PdfSource 뒤에 있어서 Pdfium 으로 바꿔도 위쪽은 그대로다.
android {
    namespace = providers.gradleProperty("reader.namespace").get() + ".pdf"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // 화면 시험(PdfScreenTest)의 빈 액티비티를 매니페스트에서 찾는다.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":ui-design"))
    api(project(":document"))
    api(project(":listen"))
    implementation(libs.androidx.annotation)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit4)
    testImplementation(testFixtures(project(":document")))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    // PDF 화면을 앱 없이 띄운다 — 밀기 · 폭 맞춤 · 듣기 판처럼 화면에서만 드러나는 동작을 이 모듈 안에서 지킨다.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // 화면 시험의 빈 Activity 를 release 단위 시험에도 — debugImplementation 만이면 release 시험이 Activity 를 못 찾았다.
    testImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("reader.robolectricRepo").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
}
