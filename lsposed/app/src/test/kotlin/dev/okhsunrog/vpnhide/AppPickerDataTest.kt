package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPickerDataTest {
    private val self = "dev.okhsunrog.vpnhide"

    @Test
    fun `preserves existing hidden and always includes self`() {
        assertEquals(
            listOf("com.a", "com.b", self).sorted(),
            resolveHiddenPackages(existing = setOf("com.b", "com.a"), observers = emptySet(), selfPkg = self),
        )
    }

    @Test
    fun `empty existing still hides self`() {
        assertEquals(listOf(self), resolveHiddenPackages(emptySet(), emptySet(), self))
    }

    @Test
    fun `observer wins - a package marked observer is dropped from hidden`() {
        // com.x was previously hidden; now it's also an observer -> must NOT be
        // written as hidden (the H+O self-lookup crash).
        assertEquals(
            listOf("com.keep", self).sorted(),
            resolveHiddenPackages(existing = setOf("com.x", "com.keep"), observers = setOf("com.x"), selfPkg = self),
        )
    }

    @Test
    fun `self stays hidden even if it somehow appears as an observer`() {
        assertEquals(
            listOf(self),
            resolveHiddenPackages(existing = setOf(self), observers = setOf(self), selfPkg = self),
        )
    }

    @Test
    fun `result is deduplicated and sorted`() {
        assertEquals(
            listOf("com.a", "com.z", self).sorted(),
            resolveHiddenPackages(existing = setOf("com.z", "com.a", self), observers = emptySet(), selfPkg = self),
        )
    }

    @Test
    fun `save preserves selected apps missing from current picker list`() {
        val snapshot =
            snapshotWithCanonical(
                "com.frozen" to CanonicalApp(java = true, native = NativeRole.All, appHiding = true, ports = true),
            )

        val cfg =
            buildCanonicalConfigForAppPickerSave(
                debug = false,
                selfPkg = self,
                selections =
                    listOf(
                        AppRoleSelection(packageName = "com.visible", java = true),
                    ),
                snapshot = snapshot,
            )

        val frozen = cfg.apps.getValue("com.frozen")
        assertEquals(true, frozen.java)
        assertEquals(NativeRole.All, frozen.native)
        assertEquals(true, frozen.appHiding)
        assertEquals(true, frozen.ports)
    }

    @Test
    fun `save can remove a visible app without dropping missing apps`() {
        val snapshot =
            snapshotWithCanonical(
                "com.visible" to CanonicalApp(java = true, native = NativeRole.All, appHiding = true, ports = true),
                "com.frozen" to CanonicalApp(java = true, native = NativeRole.All),
            )

        val cfg =
            buildCanonicalConfigForAppPickerSave(
                debug = false,
                selfPkg = self,
                selections =
                    listOf(
                        AppRoleSelection(packageName = "com.visible"),
                    ),
                snapshot = snapshot,
            )

        assertEquals(false, cfg.apps.containsKey("com.visible"))
        assertEquals(true, cfg.apps.getValue("com.frozen").java)
        assertEquals(NativeRole.All, cfg.apps.getValue("com.frozen").native)
    }

    @Test
    fun `save preserves custom native hooks for missing and still selected apps`() {
        val customNative = NativeRole(enabled = true, hooks = listOf("sock_ioctl"))
        val snapshot =
            snapshotWithCanonical(
                "com.visible" to CanonicalApp(native = customNative),
                "com.frozen" to CanonicalApp(native = customNative),
            )

        val cfg =
            buildCanonicalConfigForAppPickerSave(
                debug = false,
                selfPkg = self,
                selections =
                    listOf(
                        AppRoleSelection(packageName = "com.visible", native = true),
                    ),
                snapshot = snapshot,
            )

        assertEquals(customNative, cfg.apps.getValue("com.visible").native)
        assertEquals(customNative, cfg.apps.getValue("com.frozen").native)
    }

    @Test
    fun `observer role still wins over hidden for visible packages`() {
        val snapshot =
            snapshotWithCanonical(
                "com.vpn" to CanonicalApp(hidden = true),
            )

        val cfg =
            buildCanonicalConfigForAppPickerSave(
                debug = false,
                selfPkg = self,
                selections =
                    listOf(
                        AppRoleSelection(packageName = "com.vpn", appHiding = true),
                    ),
                snapshot = snapshot,
            )

        assertEquals(true, cfg.apps.getValue("com.vpn").appHiding)
        assertEquals(false, cfg.apps.getValue("com.vpn").hidden)
        assertEquals(true, cfg.apps.getValue(self).hidden)
    }

    @Test
    fun `save auto hides apps declaring vpn services by default`() {
        val cfg =
            buildCanonicalConfigForAppPickerSave(
                debug = false,
                selfPkg = self,
                selections = emptyList(),
                snapshot = snapshotWithCanonical(),
                autoHideSignals =
                    listOf(
                        AppAutoHideSignal(packageName = "com.vpn.client", declaresVpnService = true),
                    ),
            )

        assertEquals(true, cfg.apps.getValue("com.vpn.client").hidden)
        assertEquals(setOf("com.vpn.client"), cfg.settings.autoHiddenPackages)
    }

    @Test
    fun `vpn name heuristic is disabled by default and can be enabled`() {
        val signal = AppAutoHideSignal(packageName = "com.clone", nameContainsVpn = true)

        val disabled =
            buildCanonicalConfigForAppPickerSave(
                debug = false,
                selfPkg = self,
                selections = emptyList(),
                snapshot = snapshotWithCanonical(),
                autoHideSignals = listOf(signal),
            )
        val enabled =
            buildCanonicalConfigForAppPickerSave(
                debug = false,
                selfPkg = self,
                selections = emptyList(),
                snapshot =
                    snapshotWithCanonical(
                        settings = CanonicalSettings(autoHideVpnName = true),
                    ),
                autoHideSignals = listOf(signal),
            )

        assertEquals(false, disabled.apps.containsKey("com.clone"))
        assertEquals(true, enabled.apps.getValue("com.clone").hidden)
    }

    @Test
    fun `app hiding observer wins over auto hidden vpn app`() {
        val cfg =
            buildCanonicalConfigForAppPickerSave(
                debug = false,
                selfPkg = self,
                selections =
                    listOf(
                        AppRoleSelection(packageName = "com.vpn.client", appHiding = true),
                    ),
                snapshot = snapshotWithCanonical(),
                autoHideSignals =
                    listOf(
                        AppAutoHideSignal(packageName = "com.vpn.client", declaresVpnService = true),
                    ),
            )

        assertEquals(true, cfg.apps.getValue("com.vpn.client").appHiding)
        assertEquals(false, cfg.apps.getValue("com.vpn.client").hidden)
        assertEquals(emptySet<String>(), cfg.settings.autoHiddenPackages)
    }

    @Test
    fun `disabling auto hide removes previous auto hidden apps but keeps manual hidden apps`() {
        val snapshot =
            snapshotWithCanonical(
                "com.manual.hidden" to CanonicalApp(hidden = true),
                "com.auto.hidden" to CanonicalApp(hidden = true),
                settings =
                    CanonicalSettings(
                        autoHideVpnServices = false,
                        autoHiddenPackages = setOf("com.auto.hidden"),
                    ),
            )

        val cfg =
            buildCanonicalConfigForAppPickerSave(
                debug = false,
                selfPkg = self,
                selections = emptyList(),
                snapshot = snapshot,
                autoHideSignals =
                    listOf(
                        AppAutoHideSignal(packageName = "com.auto.hidden", declaresVpnService = true),
                    ),
            )

        assertEquals(true, cfg.apps.getValue("com.manual.hidden").hidden)
        assertEquals(false, cfg.apps.containsKey("com.auto.hidden"))
        assertEquals(emptySet<String>(), cfg.settings.autoHiddenPackages)
    }

    @Test
    fun `vpn name matcher is case insensitive`() {
        assertEquals(true, looksLikeVpnAppName("Fast VPN"))
        assertEquals(true, looksLikeVpnAppName("fast vpn"))
        assertEquals(false, looksLikeVpnAppName("Proxy Client"))
    }

    @Test
    fun `manual hidden update replaces visible manual apps and preserves missing ones`() {
        val config =
            CanonicalConfig(
                apps =
                    mapOf(
                        "com.visible.old" to CanonicalApp(hidden = true),
                        "com.missing.manual" to CanonicalApp(hidden = true),
                        "com.keep.role" to CanonicalApp(java = true),
                    ),
            )

        val updated =
            updateManualHiddenPackages(
                config = config,
                selfPkg = self,
                visiblePackages = setOf("com.visible.old", "com.visible.new", "com.keep.role"),
                selectedManualHiddenPackages = setOf("com.visible.new"),
                signals = emptyList(),
            )

        assertEquals(false, updated.apps.containsKey("com.visible.old"))
        assertEquals(true, updated.apps.getValue("com.visible.new").hidden)
        assertEquals(true, updated.apps.getValue("com.missing.manual").hidden)
        assertEquals(true, updated.apps.getValue("com.keep.role").java)
    }

    @Test
    fun `manual hidden update does not keep previous auto hidden as manual`() {
        val config =
            CanonicalConfig(
                apps =
                    mapOf(
                        "com.auto.hidden" to CanonicalApp(hidden = true),
                    ),
                settings = CanonicalSettings(autoHiddenPackages = setOf("com.auto.hidden")),
            )

        val updated =
            updateManualHiddenPackages(
                config = config,
                selfPkg = self,
                visiblePackages = setOf("com.auto.hidden"),
                selectedManualHiddenPackages = emptySet(),
                signals = emptyList(),
            )

        assertEquals(false, updated.apps.containsKey("com.auto.hidden"))
        assertEquals(emptySet<String>(), manualHiddenPackages(updated, self))
    }

    @Test
    fun `manual hidden update keeps current auto hidden matches`() {
        val config =
            CanonicalConfig(
                settings = CanonicalSettings(autoHiddenPackages = setOf("com.auto.hidden")),
            )

        val updated =
            updateManualHiddenPackages(
                config = config,
                selfPkg = self,
                visiblePackages = setOf("com.auto.hidden"),
                selectedManualHiddenPackages = emptySet(),
                signals = listOf(AppAutoHideSignal(packageName = "com.auto.hidden", declaresVpnService = true)),
            )

        assertEquals(true, updated.apps.getValue("com.auto.hidden").hidden)
        assertEquals(setOf("com.auto.hidden"), updated.settings.autoHiddenPackages)
        assertEquals(emptySet<String>(), manualHiddenPackages(updated, self))
    }

    private fun snapshotWithCanonical(
        vararg apps: Pair<String, CanonicalApp>,
        settings: CanonicalSettings = CanonicalSettings(),
    ): TargetsSnapshot =
        TargetsSnapshot(
            kmodModuleInstalled = true,
            kpmModuleInstalled = false,
            zygiskModuleInstalled = false,
            portsModuleInstalled = true,
            kmodTargets = emptySet(),
            kpmTargets = emptySet(),
            zygiskTargets = emptySet(),
            lsposedTargets = emptySet(),
            hiddenPkgs = emptySet(),
            observerUids = emptySet(),
            portsObservers = emptySet(),
            uidToPkg = emptyMap(),
            canonicalConfig = CanonicalConfig(apps = apps.toMap(), settings = settings),
        )
}
