pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Gobley fork with AGP 9 support (not yet released upstream — see PR
        // gobley/gobley#282 plus our onVariants-timing fix). Published here as
        // dev.gobley.* 0.3.8-agp9.1.okhsunrog1. Source (public):
        //   https://github.com/okhsunrog/gobley/tree/agp9-pr282
        // Drop this repo + revert the gobley version once upstream ships 0.3.8.
        maven { url = uri("https://maven.okhsunrog.dev") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API (public mirror — api.xposed.info is sometimes flaky)
        maven { url = uri("https://api.xposed.info/") }
        maven { url = uri("https://jitpack.io") }
        // Gobley fork plugin runtime artifacts (see pluginManagement above).
        maven { url = uri("https://maven.okhsunrog.dev") }
    }
}

rootProject.name = "VpnHide"
include(":app")
