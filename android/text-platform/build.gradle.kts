plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 코어의 유일한 플랫폼 경계(TextMeasurer)를 Paint 로 구현하고, 그 폭을 재는 글꼴 목록
// (시스템 · 사용자가 넣은 것)을 함께 둔다. 글꼴이 여기 있는 이유: 재는 글꼴과 그리는
// 글꼴이 한 곳에서 나와야 어긋나지 않는다. 폰트 파일은 싣지 않는다(B2 번복).
android {
    namespace = providers.gradleProperty("reader.namespace").get() + ".text"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core-layout"))
    implementation(project(":document"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    // Robolectric 은 android-all 을 Gradle 밖에서 Maven Central 로 따로 받는다. 클라우드
    // 세션에서는 429 가 나므로 미러를 넘길 수 있게 한다(설정은 HANDOFF §2).
    providers.gradleProperty("reader.robolectricRepo").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
}
