import de.aaschmid.gradle.plugins.cpd.Cpd

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.cpd)
}

// Copy-paste detector (PMD CPD): finds cross-file duplicated blocks that
// detekt can't — the re-implemented-parser / re-implemented-save-builder
// smell that AI-assisted edits produce. Advisory for now (ignoreFailures);
// run with `./gradlew cpdCheck`, report at build/reports/cpd/. Will go into
// CI once the current duplicates are cleaned up.
cpd {
    toolVersion = "7.8.0"
    language = "kotlin"
    // Tune up if too noisy / down to catch smaller clones. ~100 tokens ≈ a
    // small duplicated function.
    minimumTokenCount = 100
}

tasks.named<Cpd>("cpdCheck") {
    ignoreFailures = true
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
