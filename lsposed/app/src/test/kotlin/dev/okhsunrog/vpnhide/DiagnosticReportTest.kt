package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canonical report is the single computed diagnostic snapshot: one build,
 * every consumer a pure render. These tests pin the folding — verdicts, the
 * owned/unowned split, per-check attribution carry-through, and the gate — so no
 * renderer has to re-derive (and risk disagreeing with) any of it.
 */
class DiagnosticReportTest {
    private fun installed(active: Boolean) = ModuleState.Installed(version = "1.0", active = active, targetCount = 1)

    private fun zygisk(state: ModuleState = installed(active = true)) =
        displayNativeBackend(
            NativeBackendStates(kmod = ModuleState.NotInstalled, kpm = ModuleState.NotInstalled, zygisk = state),
        )

    private fun report(
        gate: DiagnosticGate = DiagnosticGate.ROUTED,
        results: CheckResults?,
        backend: DisplayNativeBackend = zygisk(),
        lsposedActive: Boolean = true,
        complete: Boolean = true,
    ) = buildDiagnosticReport(gate, results, backend, lsposedActive, complete)

    /** Native results in the real NATIVE_CHECKS order, carrying the given per-id
     * outcomes; every other probe is left unmeasured. The builder derives the
     * by-id outcome map from this list — there is no separate map to pass. */
    private fun nativeResults(vararg outcomes: Pair<String, CheckOutcome>): List<CheckResult> {
        val byId = outcomes.toMap()
        return NATIVE_CHECKS.map { spec -> CheckResult(spec.id, passed = null, detail = "", outcome = byId[spec.id]) }
    }

    // ── native verdict folds off the owned outcomes ────────────────────────

    @Test
    fun `native verdict is Broken when an owned vector leaks and nothing hid`() {
        val r = report(results = CheckResults(native = nativeResults("ioctl_flags" to CheckOutcome.Leak)))
        assertEquals(Verdict.Broken, r.native.verdict)
    }

    @Test
    fun `native verdict is Partial when it hides some but an owned vector leaks`() {
        val native = nativeResults("ioctl_flags" to CheckOutcome.Leak, "getifaddrs" to CheckOutcome.HiddenByBackend)
        assertEquals(Verdict.Partial, report(results = CheckResults(native = native)).native.verdict)
    }

    @Test
    fun `native verdict is Ok when nothing owned leaks`() {
        val native = nativeResults("ioctl_flags" to CheckOutcome.HiddenByBackend)
        assertEquals(Verdict.Ok, report(results = CheckResults(native = native)).native.verdict)
    }

    // ── unowned leaks are surfaced separately, never against the verdict ────

    @Test
    fun `a kernel-only leak under zygisk is unowned, not a verdict leak`() {
        // netlink_getrule (fib_nl_fill_rule) has no zygisk hook → out of scope for
        // the zygisk tile, so the verdict stays Ok and the leak is counted as unowned.
        val r = report(results = CheckResults(native = nativeResults("netlink_getrule" to CheckOutcome.Leak)))
        assertEquals(Verdict.Ok, r.native.verdict)
        assertEquals(1, r.native.unownedLeaks)
    }

    @Test
    fun `a leaking java-implemented native probe folds into the unowned count`() {
        val results =
            CheckResults(
                native = emptyList(),
                nativeExtra = listOf(CheckResult("NetworkInterface enum", passed = false, detail = "tun0 in list")),
            )
        assertEquals(1, report(results = results).native.unownedLeaks)
    }

    // ── per-check attribution carries through verbatim ─────────────────────

    /** Full native list with the first probe (ioctl_flags) carrying a rich leak +
     * root ground-truth, the rest unmeasured — the shape a real leaking run has. */
    private fun ioctlFlagsLeakNative(): List<CheckResult> =
        NATIVE_CHECKS.map { spec ->
            if (spec.id == "ioctl_flags") {
                CheckResult(
                    name = "ioctl SIOCGIFFLAGS tun0",
                    passed = false,
                    detail = "tun0 is visible!",
                    outcome = CheckOutcome.Leak,
                    groundTruthDetail = "root: tun0 up",
                )
            } else {
                CheckResult(spec.id, passed = null, detail = "", outcome = null)
            }
        }

    @Test
    fun `native check carries id, outcome, ground truth and owned flag`() {
        val checks = report(results = CheckResults(native = ioctlFlagsLeakNative())).native.checks
        val check = checks.first { it.id == "ioctl_flags" }
        assertEquals("ioctl SIOCGIFFLAGS tun0", check.label) // localized label off the result
        assertEquals(CheckOutcome.Leak, check.outcome)
        assertEquals("root: tun0 up", check.groundTruthDetail)
        assertTrue("ioctl_flags is zygisk-owned via zygisk_ioctl", check.owned)
    }

    @Test
    fun `java check carries its gate-derived outcome`() {
        val results =
            CheckResults(
                native = emptyList(),
                coreJava = listOf(CheckResult("hasTransport(VPN)", passed = false, detail = "VPN!", outcome = CheckOutcome.Leak)),
            )
        val r = report(results = results)
        val javaCheck = r.java.checks.single()
        assertEquals(CheckOutcome.Leak, javaCheck.outcome)
        assertEquals(Verdict.Broken, r.java.verdict)
    }

    // ── gate ───────────────────────────────────────────────────────────────

    @Test
    fun `a blocked gate carries no checks and no measured verdict`() {
        val r = report(gate = DiagnosticGate.VPN_OFF, results = null)
        assertEquals(DiagnosticGate.VPN_OFF, r.gate)
        assertTrue(r.native.checks.isEmpty())
        assertTrue(r.java.checks.isEmpty())
    }

    @Test
    fun `gate folds the three signals worst-first`() {
        assertEquals(DiagnosticGate.VPN_OFF, resolveDiagnosticGate(vpnActive = false, selfRouted = true, selfNeedsRestart = false))
        assertEquals(DiagnosticGate.NEEDS_RESTART, resolveDiagnosticGate(vpnActive = true, selfRouted = true, selfNeedsRestart = true))
        assertEquals(
            DiagnosticGate.SELF_NOT_ROUTED,
            resolveDiagnosticGate(vpnActive = true, selfRouted = false, selfNeedsRestart = false),
        )
        assertEquals(DiagnosticGate.ROUTED, resolveDiagnosticGate(vpnActive = true, selfRouted = true, selfNeedsRestart = false))
        // A null self-routed answer (no root) does not block.
        assertEquals(DiagnosticGate.ROUTED, resolveDiagnosticGate(vpnActive = true, selfRouted = null, selfNeedsRestart = false))
    }

    // ── renderers carry the attribution the old bundle dropped ─────────────

    private fun leakReport() = report(results = CheckResults(native = ioctlFlagsLeakNative()))

    @Test
    fun `diagnostics text carries the outcome, the ground truth and the verdict`() {
        val text = leakReport().toDiagnosticsText()
        assertTrue("gate is recorded", text.contains("gate: routed"))
        assertTrue("outcome token, not a PASS/FAIL badge", text.contains("[leak] ioctl SIOCGIFFLAGS tun0"))
        assertTrue("the root ground truth is included", text.contains("root: tun0 up"))
        assertTrue("the native verdict is Broken", text.contains("broken"))
    }

    @Test
    fun `diagnostics json serializes outcome and verdict`() {
        val json = leakReport().toJson()
        assertTrue(json.contains("\"outcome\": \"leak\""))
        assertTrue(json.contains("\"verdict\": \"broken\""))
        assertTrue(json.contains("\"groundTruthDetail\": \"root: tun0 up\""))
        assertTrue(json.contains("\"id\": \"ioctl_flags\""))
    }
}
