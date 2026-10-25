import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins { id("org.jetbrains.kotlin.multiplatform") }

// iOS 앱이 받는 프레임워크 OloCore. 공통 코어(:document · :core-layout)를 그대로 내보내고(export), iOS 에만 있는 것 —
// CoreText 로 재는 TextMeasurer, 쪽 그리기, 파일 바이트 — 을 더한다. 공통 코드의 동작은 여기서 바꾸지 않는다.
//
// ./gradlew :shared-ios:assembleXCFramework → build/XCFrameworks/{debug,release}/OloCore.xcframework
kotlin {
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    val xcframework = XCFramework("OloCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "OloCore"
            // 정적으로 묶는다. 동적이면 앱에 따로 넣고(embed) 서명해야 하고, 위젯 같은 확장을 붙일 때 두 벌이 실린다.
            isStatic = true
            export(project(":document"))
            export(project(":core-layout"))
            xcframework.add(this)
        }
    }
    // RAR · 7z 해제기(안드로이드 :archive 의 UnRAR · LZMA SDK C 원본 그대로 + iOS 다리 src/nativeInterop/cpp). 대상마다 정적
    // 라이브러리로 빌드해 cinterop 으로 부른다 — 원본을 옮겨 적지 않아 두 앱이 같은 해제기를 쓴다.
    listOf(iosArm64() to "iphoneos", iosSimulatorArm64() to "iphonesimulator").forEach { (target, sdk) ->
        val name = target.name.replaceFirstChar(Char::uppercase)
        val lib = tasks.register<NativeArchiveLib>("oloArchive$name") {
            sources.set(rootProject.layout.projectDirectory.dir("archive/src/main/cpp"))
            bridge.set(layout.projectDirectory.dir("src/nativeInterop/cpp"))
            this.sdk.set(sdk)
            output.set(layout.buildDirectory.dir("olo-archive/${target.name}"))
        }
        target.compilations.getByName("main").cinterops.create("oloarchive") {
            definitionFile.set(file("src/nativeInterop/cinterop/oloarchive.def"))
            includeDirs(file("src/nativeInterop/cpp"))
            extraOpts("-libraryPath", lib.get().output.get().asFile.path)
        }
        tasks.named("cinteropOloarchive$name") { dependsOn(lib) }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":document"))
            api(project(":core-layout"))
            // 큰 TXT 의 쪽 수를 뒤에서 센다(다른 스레드 · 조각 자물쇠, 안드로이드 0.60.0). 공통 코어는 코루틴에 기대지 않는다.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
        }
        iosTest.dependencies {
            implementation(libs.kotlin.test)
            // 시험에서 suspend 를 부르는 데만 쓴다(runTest). 프레임워크에는 들어가지 않는다.
            implementation(libs.coroutines.test)
        }
    }
}

// 이름을 붙인 XCFramework 는 assembleOloCoreXCFramework 만 만든다. 문서 · Xcode 빌드 단계가 부르는 짧은 이름을 둔다.
tasks.register("assembleXCFramework") {
    group = "build"
    description = "OloCore.xcframework (debug + release) 를 만든다."
    dependsOn("assembleOloCoreXCFramework")
}

// 시험이 저장소의 시험 글꼴(OFL, text-platform 의 것 그대로)을 읽는 곳. 시뮬레이터 안 프로세스에는 SIMCTL_CHILD_ 를 뗀 이름으로
// 간다. 글꼴을 시험 코드에 바이트로 박으면 수백 KB 짜리 소스가 생긴다.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_OLO_TEST_FONTS", rootProject.file("text-platform/src/test/resources/olo-test-fonts").absolutePath)
    environment("SIMCTL_CHILD_OLO_TEST_ARCHIVES", rootProject.file("archive/src/test/resources").absolutePath)
}

/**
 * 해제기 정적 라이브러리(libolo_archive.a) — 안드로이드 CMakeLists.txt 의 원본 목록 그대로(몇 .cpp 는 다른 .cpp 가 #include 하므로
 * 통째로 모으면 기호가 겹친다). 설정 캐시에 담기도록 스크립트 객체를 붙잡지 않는 작업 클래스로 쓴다.
 */
abstract class NativeArchiveLib @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory abstract val sources: DirectoryProperty
    @get:InputDirectory abstract val bridge: DirectoryProperty
    @get:Input abstract val sdk: Property<String>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun build() {
        val src = sources.get().asFile
        val out = output.get().asFile
        val objs = File(out, "obj").apply { deleteRecursively(); mkdirs() }
        val cmake = File(src, "CMakeLists.txt").readText()
        fun listed(set: String, ext: String) = Regex("""set\($set([^)]*)\)""").find(cmake)!!.groupValues[1]
            .split(Regex("\\s+")).filter { it.endsWith(ext) }
        val triple = if (sdk.get() == "iphonesimulator") "arm64-apple-ios17.0-simulator" else "arm64-apple-ios17.0"
        val sysroot = ByteArrayOutputStream().also { o ->
            exec.exec { commandLine("xcrun", "--sdk", sdk.get(), "--show-sdk-path"); standardOutput = o }
        }.toString().trim()
        val common = listOf("-target", triple, "-isysroot", sysroot, "-O2", "-w", "-I", src.path)
        val units = listed("UNRAR_SOURCES", ".cpp").map { File(src, it) to listOf("-std=c++14", "-fexceptions", "-DRARDLL", "-D_FILE_OFFSET_BITS=64") } +
            listed("SEVENZ_SOURCES", ".c").map { File(src, it) to listOf("-DZ7_PPMD_SUPPORT") } +
            listOf("olo_rar.cpp", "olo_7z.cpp").map { File(bridge.get().asFile, it) to listOf("-std=c++14", "-fexceptions", "-DRARDLL", "-D_FILE_OFFSET_BITS=64", "-DZ7_PPMD_SUPPORT") }
        val built = units.mapIndexed { i, (file, flags) ->
            val o = File(objs, "$i-${file.nameWithoutExtension}.o")
            val compiler = if (file.extension == "c") "clang" else "clang++"
            exec.exec { commandLine(listOf("xcrun", "--sdk", sdk.get(), compiler) + common + flags + listOf("-c", file.path, "-o", o.path)) }
            o.path
        }
        val lib = File(out, "libolo_archive.a").apply { delete() }
        exec.exec { commandLine(listOf("xcrun", "--sdk", sdk.get(), "libtool", "-static", "-o", lib.path) + built) }
    }
}
