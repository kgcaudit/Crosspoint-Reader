// 루트에는 플러그인을 선언하지 않는다.
//
// `plugins { alias(...) apply false }` 로 모아 두는 관례가 흔하지만, Gradle은 apply
// false 여도 설정 시점에 플러그인 아티팩트를 해석한다. 그러면 Android SDK가 없는
// 환경(CI의 빠른 JVM 게이트)에서 AGP 해석이 실패해 코어 모듈 테스트조차 돌지 않는다.
// 버전은 어차피 version catalog가 고정하므로, 각 모듈이 필요한 플러그인만 직접
// alias 로 선언한다.

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
