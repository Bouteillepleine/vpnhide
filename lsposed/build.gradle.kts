import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import de.aaschmid.gradle.plugins.cpd.Cpd

plugins {
    alias(libs.plugins.android.application) apply false
    // Kotlin is built into AGP 9+; kotlin-android is no longer applied. KGP stays
    // at 2.4.0 transitively via the compose-compiler / atomicfu plugins.
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.cpd)
    // Dependency-update reporting (`dependencyUpdates`) + version-catalog
    // rewriting (`versionCatalogUpdate`). The latter reads the former's output,
    // so both must be applied to the root project.
    alias(libs.plugins.ben.manes.versions)
    alias(libs.plugins.version.catalog.update)
}

// A version is "non-stable" unless it looks like a plain release (or is tagged
// RELEASE/FINAL/GA). Used to keep the update report from suggesting alpha/beta/
// rc/nightly bumps for deps we track on stable — but still surface newer
// pre-releases for deps already on one (e.g. material3's Expressive alpha).
fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    return !stableKeyword && !regex.matches(version)
}

tasks.withType<DependencyUpdatesTask>().configureEach {
    rejectVersionIf { isNonStable(candidate.version) && !isNonStable(currentVersion) }
}

// Conservative rewrites: never reorder the catalog (the pins carry explanatory
// comments whose placement matters). Unused libraries/plugins are kept by
// default in 1.x; keepUnusedVersions preserves version refs not directly wired
// to a library (e.g. material3, pinned above the compose BOM).
versionCatalogUpdate {
    sortByKey = false
    keep {
        keepUnusedVersions = true
    }
}

// Copy-paste detector (PMD CPD): finds cross-file duplicated blocks that
// detekt can't — the re-implemented-parser / re-implemented-save-builder
// smell that AI-assisted edits produce. Enforced in CI (fails on a clone);
// run locally with `./gradlew cpdCheck`, report at build/reports/cpd/. For a
// genuinely-unavoidable clone, refactor it or raise minimumTokenCount.
cpd {
    toolVersion = "7.26.0"
    language = "kotlin"
    // Tune up if too noisy / down to catch smaller clones. ~100 tokens ≈ a
    // small duplicated function.
    minimumTokenCount = 100
}

tasks.named<Cpd>("cpdCheck") {
    ignoreFailures = false
    reports {
        text.required.set(true)
        xml.required.set(false)
    }
    // Hand-written Kotlin only — skip generated codegen output (IfaceLists, HookIds).
    source =
        fileTree("app/src/main/kotlin") {
            include("**/*.kt")
            exclude("**/generated/**")
        }
}
