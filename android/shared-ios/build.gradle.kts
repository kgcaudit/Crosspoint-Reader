import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

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
    sourceSets {
        commonMain.dependencies {
            api(project(":document"))
            api(project(":core-layout"))
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
