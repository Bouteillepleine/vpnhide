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

    // ── native verdict folds off the owned outcomes ────────────────────────

    @Test
    fun `native verdict is Broken when an owned vector leaks and nothing hid`() {
        val r = report(results = CheckResults(native = emptyList(), nativeOutcomes = mapOf("ioctl_flags" to CheckOutcome.Leak)))
        assertEquals(Verdict.Broken, r.native.verdict)
    }

    @Test
    fun `native verdict is Partial when it hides some but an owned vector leaks`() {
        val outcomes = mapOf("ioctl_flags" to CheckOutcome.Leak, "getifaddrs" to CheckOutcome.HiddenByBackend)
        assertEquals(Verdict.Partial, report(results = CheckResults(native = emptyList(), nativeOutcomes = outcomes)).native.verdict)
    }

    @Test
    fun `native verdict is Ok when nothing owned leaks`() {
        val outcomes = mapOf("ioctl_flags" to CheckOutcome.HiddenByBackend)
        assertEquals(Verdict.Ok, report(results = CheckResults(native = emptyList(), nativeOutcomes = outcomes)).native.verdict)
    }

    // ── unowned leaks are surfaced separately, never against the verdict ────

    @Test
    fun `a kernel-only leak under zygisk is unowned, not a verdict leak`() {
        // netlink_getrule (fib_nl_fill_rule) has no zygisk hook → out of scope for
        // the zygisk tile, so the verdict stays Ok and the leak is counted as unowned.
        val r = report(results = CheckResults(native = emptyList(), nativeOutcomes = mapOf("netlink_getrule" to CheckOutcome.Leak)))
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

    @Test
    fun `native check carries id, outcome, ground truth and owned flag`() {
        val results =
            CheckResults(
                native =
                    listOf(
                        CheckResult(
                            name = "ioctl SIOCGIFFLAGS tun0",
                            passed = false,
                            detail = "tun0 is visible!",
                            outcome = CheckOutcome.Leak,
                            groundTruthDetail = "root: tun0 up",
                        ),
                    ),
                nativeOutcomes = mapOf("ioctl_flags" to CheckOutcome.Leak),
            )
        val check = report(results = results).native.checks.single { it.layer == CheckLayer.NATIVE }
        assertEquals("ioctl_flags", check.id) // stable id joined from NATIVE_CHECKS, not an index
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

    private fun leakReport() =
        report(
            results =
                CheckResults(
                    native =
                        listOf(
                            CheckResult(
                                name = "ioctl SIOCGIFFLAGS tun0",
                                passed = false,
                                detail = "tun0 is visible!",
                                outcome = CheckOutcome.Leak,
                                groundTruthDetail = "root: tun0 up",
                            ),
                        ),
                    nativeOutcomes = mapOf("ioctl_flags" to CheckOutcome.Leak),
                ),
        )

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
