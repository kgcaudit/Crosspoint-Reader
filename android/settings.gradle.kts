pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "reader"

// ── 순수 Kotlin(JVM) 모듈 ────────────────────────────────────────────────
// Android SDK 없이 빌드·테스트된다. 이것이 모듈 의존 규칙의 1차 강제 수단이다:
// :document / :core-layout 에 android.* 가 들어오면 여기서부터 빌드가 깨진다.
include(":document")
include(":core-layout")

// ── Android 모듈 ────────────────────────────────────────────────────────
// SDK가 있을 때만 포함한다. 없으면 JVM 코어만 빌드하는 모드로 동작하므로,
// CI의 빠른 게이트(단위 테스트·골든)는 SDK 설치 없이 돌릴 수 있다.
val androidSdkAvailable: Boolean =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkAvailable) {
    include(":app")
    include(":ui")
    include(":ui-design")
    include(":reader-reflow")
    include(":reader-pdf")
    include(":text-platform")
    include(":data")
} else {
    logger.lifecycle(
        "[reader] Android SDK 없음 → JVM 코어 모듈(:document, :core-layout)만 구성합니다. " +
            "전체 빌드는 ANDROID_HOME 설정 또는 local.properties(sdk.dir) 후 실행하세요.",
    )
}
