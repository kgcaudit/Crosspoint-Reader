plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// 저장과 파일 접근. :document 가 정해 둔 보관소 인터페이스를 Room 으로, 바이트 원천을
// SAF Uri 로 구현한다. 규칙(정렬·덮어쓰기·깨진 값 처리)은 여기서, 판단(무엇을 언제
// 저장하나)은 :core-layout 의 ReadingSession 이 한다.
android {
    namespace = providers.gradleProperty("reader.namespace").get() + ".data"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// 스키마를 저장소에 커밋한다. 다음 버전에서 테이블을 바꿀 때 마이그레이션 테스트가
// 이 파일과 비교한다 — 없으면 "업데이트했더니 책갈피가 사라졌다" 를 출시 전에 못 잡는다.
room { schemaDirectory("$projectDir/schemas") }

dependencies {
    api(project(":document"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // 테스트는 한글 파일 이름을 실제 디스크에 쓴다. 로케일이 POSIX 인 리눅스(클라우드
    // 세션·CI)에서는 JVM 이 경로를 ASCII 로 바꿔 "??.epub" 가 되므로 UTF-8 로 고정한다.
    environment("LC_ALL", "C.UTF-8")
    // Robolectric 은 android-all 을 Gradle 밖에서 Maven Central 로 따로 받는다. 클라우드
    // 세션에서는 429 가 나므로 미러를 넘길 수 있게 한다(설정은 HANDOFF §2).
    providers.gradleProperty("reader.robolectricRepo").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
}
