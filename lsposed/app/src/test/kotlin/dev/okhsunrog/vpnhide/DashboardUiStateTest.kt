package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUiStateTest {
    @Test
    fun `computeHeroStatus returns protected when checks pass and there are no issues`() {
        assertEquals(
            HeroStatus.Protected,
            computeHeroStatus(
                state =
                    dashboardState(
                        protection = ProtectionCheck.Checked(NativeResult.Ok, JavaResult.Ok),
                    ),
                errorCount = 0,
                warningCount = 0,
            ),
        )
    }

    @Test
    fun `computeHeroStatus ignores info messages`() {
        val state =
            dashboardState(
                protection = ProtectionCheck.Checked(NativeResult.Ok, JavaResult.Ok),
                messages = listOf(DashboardMessage(DashboardMessageSeverity.INFO, "note")),
            )

        assertEquals(
            HeroStatus.Protected,
            computeHeroStatus(
                state = state,
                errorCount = 0,
                warningCount = 0,
            ),
        )
    }

    @Test
    fun `computeHeroStatus returns vpn off before issue ranking`() {
        assertEquals(
            HeroStatus.VpnOff,
            computeHeroStatus(
                state = dashboardState(protection = ProtectionCheck.NoVpn),
                errorCount = 1,
                warningCount = 1,
            ),
        )
    }

    @Test
    fun `computeHeroStatus returns attention for restart partial native or warning`() {
        assertEquals(
            HeroStatus.Attention,
            computeHeroStatus(
                state = dashboardState(protection = ProtectionCheck.NeedsRestart),
                errorCount = 0,
                warningCount = 0,
            ),
        )
        assertEquals(
            HeroStatus.Attention,
            computeHeroStatus(
                state =
                    dashboardState(
                        protection = ProtectionCheck.Checked(NativeResult.Fail(passed = 2, failed = 1), JavaResult.Ok),
                    ),
                errorCount = 0,
                warningCount = 0,
            ),
        )
        assertEquals(
            HeroStatus.Attention,
            computeHeroStatus(
                state =
                    dashboardState(
                        protection = ProtectionCheck.Checked(NativeResult.Ok, JavaResult.Ok),
                    ),
                errorCount = 0,
                warningCount = 1,
            ),
        )
    }

    @Test
    fun `computeHeroStatus returns unprotected for hard failures or errors`() {
        assertEquals(
            HeroStatus.Unprotected,
            computeHeroStatus(
                state =
                    dashboardState(
                        protection = ProtectionCheck.Checked(NativeResult.Fail(passed = 0, failed = 3), JavaResult.Ok),
                    ),
                errorCount = 0,
                warningCount = 0,
            ),
        )
        assertEquals(
            HeroStatus.Unprotected,
            computeHeroStatus(
                state =
                    dashboardState(
                        protection = ProtectionCheck.Checked(NativeResult.Ok, JavaResult.Fail(failedChecks = 1)),
                    ),
                errorCount = 0,
                warningCount = 0,
            ),
        )
        assertEquals(
            HeroStatus.Unprotected,
            computeHeroStatus(
                state =
                    dashboardState(
                        protection = ProtectionCheck.Checked(NativeResult.Ok, JavaResult.Ok),
                    ),
                errorCount = 1,
                warningCount = 0,
            ),
        )
    }

    @Test
    fun `moduleActive is true only for active installed module`() {
        assertTrue(moduleActive(ModuleState.Installed(version = "1.0", active = true, targetCount = 3)))
        assertFalse(moduleActive(ModuleState.Installed(version = "1.0", active = false, targetCount = 3)))
        assertFalse(moduleActive(ModuleState.NotInstalled))
    }

    @Test
    fun `activeModuleCount and moduleSummaryText count active runtime modules`() {
        val state =
            dashboardState(
                kmod = ModuleState.Installed(version = "1.0", active = true, targetCount = 3),
                zygisk = ModuleState.Installed(version = "1.0", active = false, targetCount = 3),
                lsposed = LsposedState.Active(version = "1.0", targetCount = 3),
                ports = ModuleState.NotInstalled,
            )

        // Native layer counts once (kmod active); +LSPosed = 2 of the 3 layers.
        assertEquals(2, activeModuleCount(state))
        assertEquals("2/3", moduleSummaryText(state))
    }

    private fun dashboardState(
        kmod: ModuleState = ModuleState.NotInstalled,
        kpm: ModuleState = ModuleState.NotInstalled,
        zygisk: ModuleState = ModuleState.NotInstalled,
        lsposed: LsposedState = LsposedState.NotInstalled,
        ports: ModuleState = ModuleState.NotInstalled,
        protection: ProtectionCheck = ProtectionCheck.Checked(NativeResult.Ok, JavaResult.Ok),
        messages: List<DashboardMessage> = emptyList(),
    ): DashboardState =
        DashboardState(
            kmod = kmod,
            kpm = kpm,
            zygisk = zygisk,
            lsposed = lsposed,
            ports = ports,
            nativeBackend = displayNativeBackend(NativeBackendStates(kmod = kmod, kpm = kpm, zygisk = zygisk)),
            nativeInstallRecommendation = null,
            kmodLoadStatus = null,
            protection = protection,
            messages = messages,
        )
}
