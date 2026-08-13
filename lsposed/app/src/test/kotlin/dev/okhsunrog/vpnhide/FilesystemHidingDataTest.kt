package dev.okhsunrog.vpnhide

import dev.okhsunrog.vpnhide.generated.HookIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FilesystemHidingDataTest {
    @Test
    fun `filesystem hiding remains opt in by default`() {
        assertFalse(
            KERNEL_BOOT_FEATURE_FILESYSTEM_IFACE_PATHS in
                CanonicalSettings().kernelBootFeatures,
        )
    }

    @Test
    fun `feature is unavailable without kmod`() {
        assertEquals(
            FilesystemHidingStatus.Unavailable,
            resolveFilesystemHidingState(desiredEnabled = false, sections = emptyMap()).status,
        )
    }

    @Test
    fun `desired and runtime states distinguish both reboot directions`() {
        assertEquals(
            FilesystemHidingStatus.PendingEnable,
            resolveFilesystemHidingState(desiredEnabled = true, sections = sections()).status,
        )
        assertEquals(
            FilesystemHidingStatus.PendingDisable,
            resolveFilesystemHidingState(
                desiredEnabled = false,
                sections = sections(hookInstalled = true),
            ).status,
        )
        assertEquals(
            FilesystemHidingStatus.Active,
            resolveFilesystemHidingState(
                desiredEnabled = true,
                sections = sections(hookInstalled = true),
            ).status,
        )
    }

    @Test
    fun `fresh boot config errors retain their detail`() {
        val state =
            resolveFilesystemHidingState(
                desiredEnabled = true,
                sections = sections(configExit = 2, configError = "invalid canonical config"),
            )

        assertEquals(FilesystemHidingStatus.BootConfigError, state.status)
        assertEquals("invalid canonical config", state.errorDetail)
    }

    @Test
    fun `requested hook missing after a successful module load is a setup error`() {
        assertEquals(
            FilesystemHidingStatus.HookSetupError,
            resolveFilesystemHidingState(
                desiredEnabled = true,
                sections = sections(filesystemRequested = true, moduleLoaded = true),
            ).status,
        )
    }

    @Test
    fun `stale boot diagnostics do not override current state`() {
        val stale =
            sections(configExit = 2, configError = "old failure") +
                ("current_boot_id" to "new-boot")

        assertEquals(
            FilesystemHidingStatus.Disabled,
            resolveFilesystemHidingState(desiredEnabled = false, sections = stale).status,
        )
    }

    private fun sections(
        hookInstalled: Boolean = false,
        filesystemRequested: Boolean = false,
        moduleLoaded: Boolean = false,
        configExit: Int = if (filesystemRequested) 0 else 1,
        configError: String = "",
    ): Map<String, String> {
        val hookMask =
            if (hookInstalled) {
                setOf(HookIds.Hook.FILESYSTEM_IFACE_PATHS).toHookMask()
            } else {
                0L
            }
        val status =
            Protocol.formatStatus(
                Protocol.Status(
                    backend =
                        HookIds.Backend.KMOD.id
                            .toLong(),
                    kver = 0,
                    hooks = hookMask,
                    error =
                        HookIds.StatusError.OK.code
                            .toLong(),
                ),
            )
        val loadStatus =
            """
            boot_id=test-boot
            filesystem_hiding=${if (filesystemRequested) 1 else 0}
            filesystem_config_exit=$configExit
            filesystem_config_error=$configError
            loaded=${if (moduleLoaded) 1 else 0}
            """.trimIndent()
        return mapOf(
            "kmod_module_dir" to "1",
            "kmod_state" to status,
            "current_boot_id" to "test-boot",
            "kmod_load_status" to loadStatus,
        )
    }
}
