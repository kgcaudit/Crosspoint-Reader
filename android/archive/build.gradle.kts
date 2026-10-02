plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 압축 해제(0.37.0): RAR · 7z 의 C++ 기준 해제기(UnRAR · LZMA SDK)와 JNI 다리. OLO Explorer 에서 그대로 가져왔다.
// 휴대폰용은 arm64 만 — 32비트 기기에서는 이 형식만 열리지 않는다. 같은 원본을 PC(리눅스)용으로도 빌드해 PC 시험이
// 실제 압축을 풀어 본다(아래 hostNatives).
android {
    namespace = providers.gradleProperty("reader.namespace").get() + ".archive"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation(testFixtures(project(":document")))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

// PC 시험용 해제기. cmake · C++ 컴파일러가 없는 PC(윈도우 기본)에서는 건너뛰고, 시험은 "해제기 없음" 으로 넘어간다.
// 설정 캐시에 담기도록 스크립트 객체를 붙잡지 않는 작업 클래스로 쓴다.
abstract class HostNatives @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory abstract val source: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun build() {
        if (System.getProperty("os.name").lowercase().contains("windows")) return
        val out = output.get().asFile
        val src = source.get().asFile
        val ok = runCatching {
            exec.exec { commandLine("cmake", "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-S", src.path, "-B", out.path) }
            exec.exec { commandLine("cmake", "--build", out.path) }
        }
        if (ok.isFailure) logger.lifecycle("[archive] host native build skipped: ${ok.exceptionOrNull()?.message}")
    }
}

val hostNativeDir = layout.buildDirectory.dir("host-natives")
val hostNatives = tasks.register<HostNatives>("hostNatives") {
    source.set(layout.projectDirectory.dir("src/main/cpp"))
    output.set(hostNativeDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(hostNatives)
    systemProperty("java.library.path", hostNativeDir.get().asFile.path)
}
