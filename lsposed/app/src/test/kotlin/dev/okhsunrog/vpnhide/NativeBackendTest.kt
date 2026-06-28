package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeBackendTest {
    private fun installed(active: Boolean) = ModuleState.Installed(version = "1.0", active = active, targetCount = 1)

    // ── selectNativeBackend ──────────────────────────────────────────────

    @Test
    fun `no native installed selects none`() {
        val sel = selectNativeBackend(ModuleState.NotInstalled, ModuleState.NotInstalled, ModuleState.NotInstalled)
        assertNull(sel.id)
        assertEquals(ModuleState.NotInstalled, sel.state)
    }

    @Test
    fun `single installed backend is selected`() {
        assertEquals(
            NativeBackendId.Kpm,
            selectNativeBackend(ModuleState.NotInstalled, installed(active = false), ModuleState.NotInstalled).id,
        )
        assertEquals(
            NativeBackendId.Zygisk,
            selectNativeBackend(ModuleState.NotInstalled, ModuleState.NotInstalled, installed(active = true)).id,
        )
    }

    @Test
    fun `active backend wins over higher-priority inactive one`() {
        // kmod installed but inactive, zygisk active -> show the active zygisk.
        val sel = selectNativeBackend(installed(active = false), ModuleState.NotInstalled, installed(active = true))
        assertEquals(NativeBackendId.Zygisk, sel.id)
        assertEquals(true, moduleActive(sel.state))
    }

    @Test
    fun `with none active, priority order kmod over kpm over zygisk wins`() {
        assertEquals(
            NativeBackendId.Kmod,
            selectNativeBackend(installed(active = false), installed(active = false), installed(active = false)).id,
        )
        assertEquals(
            NativeBackendId.Kpm,
            selectNativeBackend(ModuleState.NotInstalled, installed(active = false), installed(active = false)).id,
        )
    }

    @Test
    fun `among multiple active, priority order decides`() {
        // both kmod and zygisk active -> kmod (higher priority).
        assertEquals(
            NativeBackendId.Kmod,
            selectNativeBackend(installed(active = true), ModuleState.NotInstalled, installed(active = true)).id,
        )
    }

    // ── classifyMultiNative ──────────────────────────────────────────────

    @Test
    fun `zero or one native installed is not an issue`() {
        assertEquals(MultiNativeSeverity.None, classifyMultiNative(false, false, false))
        assertEquals(MultiNativeSeverity.None, classifyMultiNative(true, false, false))
        assertEquals(MultiNativeSeverity.None, classifyMultiNative(false, true, false))
    }

    @Test
    fun `ko plus kpm is an error (freeze pair)`() {
        assertEquals(MultiNativeSeverity.Error, classifyMultiNative(kmodInstalled = true, kpmInstalled = true, zygiskInstalled = false))
        // all three installed -> still Error because the kmod+kpm pair is present.
        assertEquals(MultiNativeSeverity.Error, classifyMultiNative(kmodInstalled = true, kpmInstalled = true, zygiskInstalled = true))
    }

    @Test
    fun `other multi-native combos are warnings`() {
        assertEquals(MultiNativeSeverity.Warning, classifyMultiNative(kmodInstalled = true, kpmInstalled = false, zygiskInstalled = true))
        assertEquals(MultiNativeSeverity.Warning, classifyMultiNative(kmodInstalled = false, kpmInstalled = true, zygiskInstalled = true))
    }

    // ── detectKpmModule ──────────────────────────────────────────────────

    @Test
    fun `kpm not installed when no module prop`() {
        val state = detectKpmModule(emptyMap(), selfPkg = "self", currentBootId = "boot-1")
        assertEquals(ModuleState.NotInstalled, state)
    }

    @Test
    fun `kpm active when loaded for the current boot`() {
        val sections =
            mapOf(
                "kpm_prop" to "id=vpnhide_kpm\nversion=v1.0\n",
                "kpm_load_status" to "loaded=1\nboot_id=boot-1\n",
                "kpm_targets" to "com.example.a\ncom.example.b\n",
            )
        val state = detectKpmModule(sections, selfPkg = "self", currentBootId = "boot-1") as ModuleState.Installed
        assertEquals(true, state.active)
        assertEquals("1.0", state.version)
        assertEquals(2, state.targetCount)
    }

    @Test
    fun `kpm inactive when load status is from a previous boot`() {
        val sections =
            mapOf(
                "kpm_prop" to "id=vpnhide_kpm\nversion=v1.0\n",
                "kpm_load_status" to "loaded=1\nboot_id=old-boot\n",
            )
        val state = detectKpmModule(sections, selfPkg = "self", currentBootId = "boot-1") as ModuleState.Installed
        assertEquals(false, state.active)
    }

    @Test
    fun `kpm inactive when load status has no boot id`() {
        val sections =
            mapOf(
                "kpm_prop" to "id=vpnhide_kpm\nversion=v1.0\n",
                "kpm_load_status" to "loaded=1\ndetail=configured\n",
            )
        val state = detectKpmModule(sections, selfPkg = "self", currentBootId = "boot-1") as ModuleState.Installed
        assertEquals(false, state.active)
    }
}
