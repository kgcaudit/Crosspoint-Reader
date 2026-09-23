plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = providers.gradleProperty("reader.namespace").get() + ".app"
    compileSdk = 35

    defaultConfig {
        applicationId = providers.gradleProperty("reader.applicationId").get()
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        resValue("string", "app_name", providers.gradleProperty("reader.appName").get())
        // 의존 라이브러리가 싣고 오는 80여 개 언어 번역을 뺀다. 화면이 한국어뿐이다.
        resourceConfigurations += listOf("ko", "en")
    }

    signingConfigs {
        // 저장소에 커밋한 **개발용** 키. 비밀이 아니다(암호도 여기 적혀 있다).
        //
        // 왜 커밋하나: 안드로이드는 서명이 다른 APK 로 덮어 설치하지 못한다. 세션·PC 마다
        // 자동 생성되는 debug 키를 쓰면 다음 빌드를 설치할 때 앱을 지워야 하고, 그러면
        // 책갈피·진도가 전부 사라진다. 어디서 빌드해도 같은 키로 서명되게 한다.
        //
        // 공개 배포(Play 등) 전에는 비공개 릴리스 키로 바꾼다. 그때부터는 그 키로만
        // 업데이트할 수 있다.
        create("dev") {
            storeFile = file("olo-dev.keystore")
            storePassword = "olo-dev"
            keyAlias = "olo-dev"
            keyPassword = "olo-dev"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", providers.gradleProperty("reader.appName").get() + " (dev)")
        }
        release {
            // 사람에게 건네는 APK 는 이것이다. Compose 는 debug 빌드에서 눈에 띄게 느리다
            // (R8·최적화 없음) — 페이지 넘김 체감이 전혀 다르다.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        resValues = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/LICENSE*", "kotlin/**", "DebugProbesKt.bin")
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// 산출물 이름에 앱 이름과 판을 넣는다. app-release.apk 로 여러 개가 쌓이면 구별이 안 된다.
base { archivesName.set("OLO-eBook-" + android.defaultConfig.versionName) }

dependencies {
    implementation(project(":reader-reflow"))
    implementation(project(":data"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    environment("LC_ALL", "C.UTF-8")
    providers.gradleProperty("reader.robolectricRepo").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
    // 스크린샷을 남길 곳. 사람이 눈으로 확인하는 산출물이라 테스트 결과 옆에 둔다.
    systemProperty("reader.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
