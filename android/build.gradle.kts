// Gradle 플러그인은 여기 buildscript 클래스패스에 **한 번만** 올린다.
//
// 모듈마다 plugins { alias(...) } 로 버전을 적으면 Kotlin 플러그인이 모듈마다 다른
// 클래스로더에 올라간다. Gradle 은 이를 "지원하지 않으며 빌드가 깨질 수 있다" 고
// 경고하고, KSP·Compose 컴파일러처럼 Kotlin 플러그인에 붙는 플러그인이 늘수록 실제로
// 깨진다. 그래서 모듈은 버전 없이 id(...) 로만 적용한다.
//
// 흔한 관례인 루트 `plugins { alias(...) apply false }` 를 쓰지 않는 이유: 그 블록은
// 조건을 달 수 없어서 AGP 까지 항상 해석한다. AGP 는 dl.google.com 에만 있으므로,
// 그 호스트가 막힌 환경(클라우드 기본 정책·CI 의 빠른 JVM 게이트)에서 코어 모듈
// 테스트조차 돌지 않게 된다. buildscript 는 조건을 달 수 있다.
buildscript {
    // settings.gradle.kts 와 같은 판정이다. 둘이 어긋나면 안드로이드 모듈은 구성되는데
    // AGP 가 클래스패스에 없거나, 그 반대가 된다.
    val androidSdkAvailable = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

    repositories {
        // Maven Central 을 먼저 본다. SDK 없는 환경에서는 google() 을 아예 넣지 않는다 —
        // 막힌 호스트를 한 번이라도 조회하면 403 으로 구성이 실패할 수 있다.
        mavenCentral()
        if (androidSdkAvailable) google()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        if (androidSdkAvailable) {
            classpath(libs.android.gradle.plugin)
            classpath(libs.ksp.gradle.plugin)
            classpath(libs.room.gradle.plugin)
            classpath(libs.compose.gradle.plugin)
        }
    }
}

/** 플랫폼 의존이 금지된 순수 Kotlin 모듈. */
val pureKotlinModules = setOf("document", "core-layout")

subprojects {
    if (name !in pureKotlinModules) return@subprojects

    // 설정 캐시 호환: doLast 는 Project 나 스크립트 최상위 값을 참조하면 안 되므로
    // 필요한 값을 미리 평범한 타입(File, String, Provider)으로 꺼내 둔다.
    val srcDir = projectDir.resolve("src")
    val marker = layout.buildDirectory.file("reports/checkNoPlatformImports.txt")
    val moduleLabel = path

    /**
     * 플랫폼 의존 금지 규칙의 2차 강제 수단.
     *
     * settings.gradle.kts 가 SDK 없이도 JVM 코어를 빌드시키므로 android.* 는 이미
     * 컴파일이 안 되지만, 그 실패는 SDK가 깔린 개발 머신에서는 드러나지 않는다.
     * 이 태스크는 SDK 유무와 무관하게 소스를 직접 훑어 위반을 잡고 `check` 에
     * 물려 있으므로 PR 게이트에서 항상 돈다.
     *
     * 왜 중요한가: :document / :core-layout 이 순수한 동안에만 파싱·조판 로직을
     * 기기 없이 JVM 단위 테스트로 검증할 수 있다. 한 번 오염되면 되돌리는 비용이
     * 크므로 첫 커밋부터 막는다.
     */
    val checkNoPlatformImports = tasks.register("checkNoPlatformImports") {
        group = "verification"
        description = "순수 Kotlin 모듈에 android.* / androidx.* 임포트가 없는지 검사한다."

        inputs.files(fileTree(srcDir) { include("**/*.kt") })
            .withPropertyName("kotlinSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(marker)

        doLast {
            val forbidden = Regex(
                """^\s*import\s+(android\.|androidx\.|com\.android\.)""",
                RegexOption.MULTILINE,
            )
            val violations = srcDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    forbidden.findAll(file.readText())
                        .map { "${file.relativeTo(srcDir)}: ${it.value.trim()}" }
                }
                .toList()

            if (violations.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("[$moduleLabel] 순수 Kotlin 모듈에 플랫폼 임포트가 있습니다:")
                        violations.forEach { appendLine("  - $it") }
                        appendLine()
                        appendLine("android.*/androidx.* 가 필요하면 그 코드는 :text-platform 또는")
                        appendLine("해당 Android 모듈로 옮기고, 이 모듈에는 인터페이스만 남기세요.")
                    },
                )
            }
            marker.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
        }
    }

    tasks.matching { it.name == "check" }.configureEach { dependsOn(checkNoPlatformImports) }
}
