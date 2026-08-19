package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleIntegrityDataTest {
    private val installed = ModuleState.Installed(version = "1.0.0", active = false)

    @Test
    fun `activator state parser keeps integrity outcomes typed`() {
        assertEquals(ActivatorState.Executable, parseActivatorState("executable\n"))
        assertEquals(ActivatorState.Missing, parseActivatorState("missing"))
        assertEquals(ActivatorState.NotExecutable, parseActivatorState("not_executable"))
        assertEquals(ActivatorState.Unknown, parseActivatorState("future"))
    }

    @Test
    fun `enabled installed module reports missing activator`() {
        val problem =
            classifyModuleIntegrity(
                kind = FlashableModuleKind.Zygisk,
                module = installed,
                activatorState = ActivatorState.Missing,
                activatorPath = ZYGISK_ACTIVATOR,
                disabled = false,
            )

        assertEquals(ModuleBrokenReason.ActivatorMissing, problem?.reason)
        assertEquals(ZYGISK_ACTIVATOR, problem?.activatorPath)
    }

    @Test
    fun `enabled installed module reports non executable activator`() {
        val problem =
            classifyModuleIntegrity(
                kind = FlashableModuleKind.Ports,
                module = installed,
                activatorState = ActivatorState.NotExecutable,
                activatorPath = PORTS_ACTIVATOR,
                disabled = false,
            )

        assertEquals(ModuleBrokenReason.ActivatorNotExecutable, problem?.reason)
    }

    @Test
    fun `disabled absent healthy and unknown modules do not report corruption`() {
        assertNull(classify(ActivatorState.Missing, disabled = true, module = installed))
        assertNull(classify(ActivatorState.Missing, disabled = false, module = ModuleState.NotInstalled))
        assertNull(classify(ActivatorState.Executable, disabled = false, module = installed))
        assertNull(classify(ActivatorState.Unknown, disabled = false, module = installed))
    }

    @Test
    fun `missing activator with a staged install is pending reboot, not corruption`() {
        val sections =
            sectionsOf(activatorState = "missing", disabled = "0", pendingUpdate = "1")

        assertTrue(modulePendingReboot(FlashableModuleKind.Kpm, installed, sections))
        // The integrity error is suppressed so the UI shows a reboot warning instead.
        assertNull(
            moduleIntegrityProblem(
                kind = FlashableModuleKind.Kpm,
                module = installed,
                sections = sections,
                activatorPath = KPM_ACTIVATOR,
            ),
        )
    }

    @Test
    fun `missing activator without a staged install stays a corruption error`() {
        val sections =
            sectionsOf(activatorState = "missing", disabled = "0", pendingUpdate = "0")

        assertFalse(modulePendingReboot(FlashableModuleKind.Kpm, installed, sections))
        assertEquals(
            ModuleBrokenReason.ActivatorMissing,
            moduleIntegrityProblem(
                kind = FlashableModuleKind.Kpm,
                module = installed,
                sections = sections,
                activatorPath = KPM_ACTIVATOR,
            )?.reason,
        )
    }

    @Test
    fun `pending reboot needs a real activator failure and an enabled installed module`() {
        // A healthy activator is not pending-reboot even with the staged marker set.
        assertFalse(
            modulePendingReboot(
                FlashableModuleKind.Kpm,
                installed,
                sectionsOf(activatorState = "executable", disabled = "0", pendingUpdate = "1"),
            ),
        )
        // A disabled module is not pending-reboot.
        assertFalse(
            modulePendingReboot(
                FlashableModuleKind.Kpm,
                installed,
                sectionsOf(activatorState = "missing", disabled = "1", pendingUpdate = "1"),
            ),
        )
        // A module that is not installed is not pending-reboot.
        assertFalse(
            modulePendingReboot(
                FlashableModuleKind.Kpm,
                ModuleState.NotInstalled,
                sectionsOf(activatorState = "missing", disabled = "0", pendingUpdate = "1"),
            ),
        )
        // Absent snapshot field defaults to not-pending.
        assertFalse(
            modulePendingReboot(
                FlashableModuleKind.Kpm,
                installed,
                mapOf("kpm_activator_state" to "missing", "kpm_disabled" to "0"),
            ),
        )
    }

    private fun sectionsOf(
        activatorState: String,
        disabled: String,
        pendingUpdate: String,
    ): Map<String, String> =
        mapOf(
            "kpm_activator_state" to activatorState,
            "kpm_disabled" to disabled,
            "kpm_pending_update" to pendingUpdate,
        )

    private fun classify(
        state: ActivatorState,
        disabled: Boolean,
        module: ModuleState,
    ): ModuleIntegrityProblem? =
        classifyModuleIntegrity(
            kind = FlashableModuleKind.Kpm,
            module = module,
            activatorState = state,
            activatorPath = KPM_ACTIVATOR,
            disabled = disabled,
        )
}
