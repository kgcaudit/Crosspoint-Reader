import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 릴리스 서명 키를 찾는다. 저장소에는 절대 넣지 않는다(공개 저장소에 키를 두면 남이 같은 키로 서명한 앱을
 * 만들어 우리 앱의 업데이트인 척할 수 있다).
 *
 * 1. 환경 변수 OLO_RELEASE_KEYSTORE_B64(키 파일을 base64 로) + OLO_RELEASE_PASSWORD — 클라우드 세션용.
 * 2. Gradle 속성 olo.release.keystore(파일 경로) + olo.release.password — PC 의 ~/.gradle/gradle.properties.
 * 별칭은 OLO_RELEASE_ALIAS / olo.release.alias, 없으면 "olo-release".
 */
class ReleaseKey(val file: File, val password: String, val alias: String)

val releaseKey: ReleaseKey? = run {
    val alias = providers.environmentVariable("OLO_RELEASE_ALIAS").orNull
        ?: providers.gradleProperty("olo.release.alias").orNull ?: "olo-release"
    val b64 = providers.environmentVariable("OLO_RELEASE_KEYSTORE_B64").orNull
    val envPassword = providers.environmentVariable("OLO_RELEASE_PASSWORD").orNull
    if (!b64.isNullOrBlank() && !envPassword.isNullOrBlank()) {
        val out = layout.buildDirectory.file("release-key/olo-release.keystore").get().asFile
        out.parentFile.mkdirs()
        out.writeBytes(Base64.getMimeDecoder().decode(b64.trim()))
        return@run ReleaseKey(out, envPassword, alias)
    }
    val path = providers.gradleProperty("olo.release.keystore").orNull
    val password = providers.gradleProperty("olo.release.password").orNull
    if (!path.isNullOrBlank() && !password.isNullOrBlank() && File(path).isFile) ReleaseKey(File(path), password, alias) else null
}
if (releaseKey == null) {
    logger.info("OLO: no private release key - release APK is signed with the dev key (-devkey). See make-release-key.ps1.")
}

android {
    namespace = providers.gradleProperty("reader.namespace").get() + ".app"
    compileSdk = 35

    defaultConfig {
        applicationId = providers.gradleProperty("reader.applicationId").get()
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "0.15.1"
        resValue("string", "app_name", providers.gradleProperty("reader.appName").get())
        // 의존 라이브러리가 싣고 오는 80여 개 언어 번역을 뺀다. 화면이 한국어뿐이다.
        resourceConfigurations += listOf("ko", "en")
    }

    signingConfigs {
        // 개발용 키. 저장소(공개)에 커밋되어 있어 누구나 이 키로 서명할 수 있다 — 공개 배포 전에는 release 를
        // 비공개 키로 바꾼다. 어디서 빌드해도 같은 키라 덮어 설치가 되고 책갈피 · 진도가 남는다.
        create("dev") {
            storeFile = file("olo-dev.keystore")
            storePassword = "olo-dev"
            keyAlias = "olo-dev"
            keyPassword = "olo-dev"
        }
        // 사람에게 건네는 APK 의 키. 저장소 밖에만 둔다(make-release-key.ps1 이 만든다).
        releaseKey?.let { key ->
            create("release") {
                storeFile = key.file
                storePassword = key.password
                keyAlias = key.alias
                keyPassword = key.password
            }
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
            // 비공개 키가 없으면 개발용 키로 서명하고 파일 이름에 -devkey 를 붙인다(어느 키인지 이름으로 보이게).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("dev")
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
    // 글꼴 넣기 테스트가 :text-platform 의 테스트 폰트를 쓴다. 같은 파일을 두 번 싣지 않는다.
    sourceSets.getByName("test").resources.srcDir("../text-platform/src/test/resources")
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/LICENSE*", "kotlin/**", "DebugProbesKt.bin")
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// 산출물 이름에 앱 이름과 판을 넣는다. app-release.apk 로 여러 개가 쌓이면 구별이 안 된다.
base { archivesName.set("OLO-eBook-" + android.defaultConfig.versionName + (if (releaseKey == null) "-devkey" else "")) }

dependencies {
    implementation(project(":reader-reflow"))
    implementation(project(":reader-pdf"))
    implementation(project(":data"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(testFixtures(project(":document")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
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
