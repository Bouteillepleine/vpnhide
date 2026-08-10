package dev.okhsunrog.vpnhide

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The debug-bundle renderers for the canonical [DiagnosticReport]. Both outputs
 * are pure functions of the report, so the bundle carries exactly what the UI
 * computes — the root-differential outcome and its ground-truth basis, the
 * per-layer verdict, and the gate — instead of the old flattened PASS/FAIL badge.
 */

private fun LayerStatus.presenceToken(): String =
    when (this) {
        LayerStatus.Absent -> "absent"
        LayerStatus.Inactive -> "inactive"
        is LayerStatus.Active -> "active"
    }

/** One-line verdict/presence summary for a layer, e.g. `broken (hidden 0, leaks 2, unowned 1)`. */
internal fun LayerReport.verdictLabel(): String {
    val active = status as? LayerStatus.Active ?: return status.presenceToken()
    return "${active.verdict.name.lowercase()} (hidden ${active.hidden}, leaks ${active.leaks}, unowned $unownedLeaks)"
}

/** Count of every check by outcome token across both layers — the honest headline
 * that replaces the misleading "N/total passed" score. */
internal fun DiagnosticReport.outcomeTally(): String =
    (native.checks + java.checks)
        .groupingBy { it.outcome.token() }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(", ") { "${it.key}=${it.value}" }
        .ifEmpty { "(no checks run)" }

// ── human-readable diagnostics.txt ─────────────────────────────────────────

internal fun DiagnosticReport.toDiagnosticsText(): String =
    buildString {
        appendLine("=== Diagnostics report (schema $schema) ===")
        appendLine("gate: ${gate.name.lowercase()}")
        appendLine("complete: $complete")
        appendLine("outcomes: ${outcomeTally()}")
        appendLine()
        appendLayer(native, "Native", native.backend?.name?.lowercase() ?: "none")
        appendLine()
        appendLayer(java, "Java", "lsposed")
    }.trimEnd()

private fun StringBuilder.appendLayer(
    layer: LayerReport,
    title: String,
    backendLabel: String,
) {
    appendLine("--- $title layer ($backendLabel) ---")
    appendLine(layer.verdictLabel())
    if (layer.checks.isEmpty()) {
        appendLine("(no checks — gated run)")
        return
    }
    for (c in layer.checks) {
        val ownedMark = if (c.layer == CheckLayer.NATIVE && !c.owned) "  (unowned)" else ""
        appendLine("[${c.outcome.token()}] ${c.label}$ownedMark")
        appendLine("  app:  ${c.appDetail}")
        c.groundTruthDetail?.let { appendLine("  root: $it") }
        if (c.expectedHooks.isNotEmpty()) {
            appendLine("  hooks: ${c.expectedHooks.joinToString { it.hookName }}")
        }
    }
}

// ── machine-readable diagnostics.json ──────────────────────────────────────

private val reportJson = Json { prettyPrint = true }

internal fun DiagnosticReport.toJson(): String = reportJson.encodeToString(ReportJson.serializer(), toReportJson())

@Serializable
private data class ReportJson(
    val schema: Int,
    val gate: String,
    val complete: Boolean,
    val native: LayerJson,
    val java: LayerJson,
)

@Serializable
private data class LayerJson(
    val layer: String,
    val backend: String?,
    val presence: String,
    val verdict: String?,
    val hidden: Int?,
    val leaks: Int?,
    val unownedLeaks: Int,
    val checks: List<CheckJson>,
)

@Serializable
private data class CheckJson(
    val id: String,
    val label: String,
    val outcome: String,
    val appDetail: String,
    val groundTruthDetail: String?,
    val expectedHooks: List<String>,
    val owned: Boolean,
)

private fun DiagnosticReport.toReportJson(): ReportJson =
    ReportJson(
        schema = schema,
        gate = gate.name.lowercase(),
        complete = complete,
        native = native.toLayerJson(),
        java = java.toLayerJson(),
    )

private fun LayerReport.toLayerJson(): LayerJson {
    val active = status as? LayerStatus.Active
    return LayerJson(
        layer = layer.name.lowercase(),
        backend = backend?.name?.lowercase(),
        presence = status.presenceToken(),
        verdict = active?.verdict?.name?.lowercase(),
        hidden = active?.hidden,
        leaks = active?.leaks,
        unownedLeaks = unownedLeaks,
        checks = checks.map { it.toCheckJson() },
    )
}

private fun DiagnosticCheck.toCheckJson(): CheckJson =
    CheckJson(
        id = id,
        label = label,
        outcome = outcome.token(),
        appDetail = appDetail,
        groundTruthDetail = groundTruthDetail,
        expectedHooks = expectedHooks.map { it.hookName },
        owned = owned,
    )
