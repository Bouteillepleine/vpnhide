pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Gobley fork with AGP 9 support (not yet released upstream — see PR
        // gobley/gobley#282 plus our onVariants-timing fix). Source:
        // github.com/okhsunrog/gobley @ agp9-pr282.
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
