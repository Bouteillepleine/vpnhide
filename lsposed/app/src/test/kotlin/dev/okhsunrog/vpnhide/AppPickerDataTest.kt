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

    private fun snapshotWithCanonical(vararg apps: Pair<String, CanonicalApp>): TargetsSnapshot =
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
            canonicalConfig = CanonicalConfig(apps = apps.toMap()),
        )
}
