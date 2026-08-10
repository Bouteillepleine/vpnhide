package dev.okhsunrog.vpnhide

import dev.okhsunrog.vpnhide.generated.HookIds

/**
 * Schema version for the serialized report (the debug bundle's `diagnostics.json`
 * and the summary header). Bump when the wire shape changes.
 */
internal const val DIAGNOSTIC_REPORT_SCHEMA: Int = 1

internal enum class CheckLayer { NATIVE, JAVA }

/**
 * Why the check suite did or did not run this session — the terminal gate from
 * [DiagnosticsCache]. Only [ROUTED] yields measured per-layer verdicts; the other
 * states mean "we deliberately did not measure" (VPN off, this app split-tunnelled
 * out of the VPN, or a pending self-restart), which a consumer must surface
 * instead of a misleading clean/Ok result.
 */
internal enum class DiagnosticGate { VPN_OFF, SELF_NOT_ROUTED, NEEDS_RESTART, ROUTED }

/**
 * One fully-classified check: the root-differential [outcome] plus the evidence
 * behind it — the app-view [appDetail], the root [groundTruthDetail] (native
 * probes run as root), and, for native probes, the hooks that should cover the
 * vector and whether the active backend [owned] it.
 */
internal data class DiagnosticCheck(
    val id: String,
    val label: String,
    val layer: CheckLayer,
    val outcome: CheckOutcome,
    val appDetail: String,
    val groundTruthDetail: String?,
    val expectedHooks: List<HookIds.Hook>,
    val owned: Boolean,
)

/** Per-layer rollup: presence/verdict plus the classified checks that produced it. */
internal data class LayerReport(
    val layer: CheckLayer,
    val backend: NativeBackendId?,
    val status: LayerStatus,
    // Leaks on vectors the active backend does NOT own (native only) — surfaced as
    // a warning, never counted against the tile verdict. Always 0 for the Java layer.
    val unownedLeaks: Int,
    val checks: List<DiagnosticCheck>,
) {
    /** Ok/Partial/Broken when the layer is [LayerStatus.Active]; null otherwise.
     * Only meaningful when the report's [DiagnosticReport.gate] is
     * [DiagnosticGate.ROUTED] — a gated report carries presence only. */
    val verdict: Verdict? get() = (status as? LayerStatus.Active)?.verdict
}

/**
 * The single canonical diagnostic snapshot.
 *
 * The app used to re-derive the diagnostic verdict independently in four places
 * (dashboard tiles, the Diagnostics screen, the agent bridge, and the debug-ZIP
 * text renderers), each flattening the rich per-check attribution back down to a
 * PASS/FAIL badge. [buildDiagnosticReport] computes the whole thing **once**;
 * every consumer is a pure render of this object, so no two views can disagree
 * and the debug bundle carries exactly what the UI shows.
 */
internal data class DiagnosticReport(
    val gate: DiagnosticGate,
    val native: LayerReport,
    val java: LayerReport,
    // False after the fast core phase, true once the slow Java probes have filled in.
    val complete: Boolean,
    val schema: Int = DIAGNOSTIC_REPORT_SCHEMA,
)

/**
 * Fold the raw check run into the canonical [DiagnosticReport]. Pure: same inputs
 * → same report, no Android or IO dependency, so it is unit-tested directly.
 *
 * [results] is null when the gate blocked the run ([DiagnosticGate.ROUTED] is the
 * only gate that carries measurements); the layers then report presence only and
 * their [LayerReport.verdict] must not be consulted.
 */
internal fun buildDiagnosticReport(
    gate: DiagnosticGate,
    results: CheckResults?,
    backend: DisplayNativeBackend,
    lsposedActive: Boolean,
    complete: Boolean,
): DiagnosticReport {
    val nativeOutcomes = results?.nativeOutcomes ?: emptyMap()
    val unowned =
        if (results == null) {
            0
        } else {
            unownedNativeLeaks(backend, nativeOutcomes) + results.nativeExtra.count { it.passed == false }
        }
    return DiagnosticReport(
        gate = gate,
        native =
            LayerReport(
                layer = CheckLayer.NATIVE,
                backend = backend.id,
                status = summarizeNativeLayer(backend, nativeOutcomes),
                unownedLeaks = unowned,
                checks = nativeDiagnosticChecks(results, backend),
            ),
        java =
            LayerReport(
                layer = CheckLayer.JAVA,
                backend = null,
                status = summarizeJavaLayer(lsposedActive, results?.java ?: emptyList()),
                unownedLeaks = 0,
                checks = javaDiagnosticChecks(results),
            ),
        complete = complete,
    )
}

private fun nativeDiagnosticChecks(
    results: CheckResults?,
    backend: DisplayNativeBackend,
): List<DiagnosticCheck> {
    if (results == null) return emptyList()
    val ownMask = nativeOwnMask(backend.id)
    // native and nativeExtra are built in NATIVE_CHECKS order, so a positional zip
    // is stable by construction — the spec carries the stable id + hook coverage,
    // the result carries the localized label, outcome, and root ground-truth detail.
    val owned =
        NATIVE_CHECKS.zip(results.native) { spec, cr ->
            DiagnosticCheck(
                id = spec.id,
                label = cr.name,
                layer = CheckLayer.NATIVE,
                outcome = cr.outcome ?: CheckOutcome.NotMeasured(NotMeasuredReason.NoGroundTruth),
                appDetail = cr.detail,
                groundTruthDetail = cr.groundTruthDetail,
                expectedHooks = spec.expectedHooks.toList(),
                owned = spec.hasHookIn(ownMask),
            )
        }
    // Java-implemented native-level probes (NetworkInterface enum): no hook
    // ownership and no root differential, so the outcome comes off the tri-state.
    val extra =
        results.nativeExtra.map { cr ->
            DiagnosticCheck(
                id = "",
                label = cr.name,
                layer = CheckLayer.NATIVE,
                outcome = cr.outcome ?: classifyJavaOutcome(cr.passed),
                appDetail = cr.detail,
                groundTruthDetail = null,
                expectedHooks = emptyList(),
                owned = false,
            )
        }
    return owned + extra
}

private fun javaDiagnosticChecks(results: CheckResults?): List<DiagnosticCheck> =
    results
        ?.java
        ?.map { cr ->
            DiagnosticCheck(
                id = "",
                label = cr.name,
                layer = CheckLayer.JAVA,
                outcome = cr.outcome ?: classifyJavaOutcome(cr.passed),
                appDetail = cr.detail,
                groundTruthDetail = cr.groundTruthDetail,
                expectedHooks = emptyList(),
                owned = true,
            )
        }.orEmpty()
