import de.aaschmid.gradle.plugins.cpd.Cpd

plugins {
    alias(libs.plugins.android.application) apply false
    // Kotlin is built into AGP 9+; kotlin-android is no longer applied. KGP stays
    // at 2.4.0 transitively via the compose-compiler / atomicfu plugins.
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.cpd)
}

// Copy-paste detector (PMD CPD): finds cross-file duplicated blocks that
// detekt can't — the re-implemented-parser / re-implemented-save-builder
// smell that AI-assisted edits produce. Enforced in CI (fails on a clone);
// run locally with `./gradlew cpdCheck`, report at build/reports/cpd/. For a
// genuinely-unavoidable clone, refactor it or raise minimumTokenCount.
cpd {
    toolVersion = "7.25.0"
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
    // Hand-written Kotlin only — skip codegen (IfaceLists) and UniFFI bindings.
    source =
        fileTree("app/src/main/kotlin") {
            include("**/*.kt")
            exclude("**/generated/**")
        }
}
