package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
