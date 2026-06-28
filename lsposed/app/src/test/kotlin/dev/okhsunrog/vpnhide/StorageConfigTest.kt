package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageConfigTest {
    @Test
    fun `canonical config parses roles settings and native hook lists`() {
        val cfg =
            requireNotNull(
                parseCanonicalConfig(
                    """
                    {
                      "version": 1,
                      "debug": true,
                      "apps": {
                        "com.bank": { "java": true, "native": ["sock_ioctl"], "appHiding": false, "ports": true },
                        "dev.okhsunrog.vpnhide": { "hidden": true }
                      },
                      "settings": { "rememberSuperkey": true }
                    }
                    """.trimIndent(),
                ),
            )

        assertTrue(cfg.debug)
        assertEquals(CanonicalSettings(rememberSuperkey = true), cfg.settings)
        assertEquals(NativeRole(enabled = true, hooks = listOf("sock_ioctl")), cfg.apps.getValue("com.bank").native)
        assertTrue(cfg.apps.getValue("dev.okhsunrog.vpnhide").hidden)
    }

    @Test
    fun `parses shared storage fixture`() {
        val cfg = requireNotNull(parseCanonicalConfig(sharedStorageFixture()))

        assertTrue(cfg.debug)
        assertEquals(CanonicalSettings(rememberSuperkey = true), cfg.settings)
        assertEquals(NativeRole.All, cfg.apps.getValue("com.example.bank").native)
        assertEquals(
            NativeRole(enabled = true, hooks = listOf("fib_route_seq_show", "sock_ioctl")),
            cfg.apps.getValue("org.example.proxy").native,
        )
        assertTrue(cfg.apps.getValue("dev.okhsunrog.vpnhide").hidden)
    }

    @Test
    fun `builder preserves an existing native hook list when role remains enabled`() {
        val existing =
            CanonicalConfig(
                apps =
                    mapOf(
                        "com.bank" to CanonicalApp(native = NativeRole(enabled = true, hooks = listOf("sock_ioctl"))),
                    ),
            )

        val cfg =
            buildCanonicalConfig(
                debug = false,
                javaPkgs = emptySet(),
                nativePkgs = setOf("com.bank", "com.new"),
                hiddenPkgs = emptySet(),
                observerPkgs = emptySet(),
                portsPkgs = emptySet(),
                existing = existing,
            )

        assertEquals(NativeRole(enabled = true, hooks = listOf("sock_ioctl")), cfg.apps.getValue("com.bank").native)
        assertEquals(NativeRole.All, cfg.apps.getValue("com.new").native)
    }

    @Test
    fun `canonical JSON is deterministic and round trips through parser`() {
        val cfg =
            buildCanonicalConfig(
                debug = true,
                javaPkgs = setOf("com.java"),
                nativePkgs = setOf("com.native"),
                hiddenPkgs = setOf("dev.okhsunrog.vpnhide"),
                observerPkgs = setOf("com.observer"),
                portsPkgs = setOf("com.ports"),
            )

        val reparsed = requireNotNull(parseCanonicalConfig(canonicalConfigJson(cfg)))

        assertEquals(cfg, reparsed)
    }

    @Test
    fun `self target merge adds java native and hidden without dropping settings`() {
        val cfg =
            CanonicalConfig(
                settings = CanonicalSettings(rememberSuperkey = true),
                apps = mapOf("com.bank" to CanonicalApp(java = true)),
            )

        val updated = canonicalConfigWithSelfTarget(cfg, "dev.okhsunrog.vpnhide")

        assertEquals(CanonicalSettings(rememberSuperkey = true), updated.settings)
        assertEquals(
            CanonicalApp(java = true, native = NativeRole.All, hidden = true),
            updated.apps.getValue("dev.okhsunrog.vpnhide"),
        )
    }

    @Test
    fun `snapshot builder folds legacy roles into canonical config`() {
        val snapshot =
            TargetsSnapshot(
                kmodModuleInstalled = true,
                kpmModuleInstalled = false,
                zygiskModuleInstalled = false,
                portsModuleInstalled = true,
                kmodTargets = setOf("com.native"),
                kpmTargets = emptySet(),
                zygiskTargets = emptySet(),
                lsposedTargets = setOf("com.java"),
                hiddenPkgs = setOf("com.hidden"),
                observerUids = setOf(10123),
                portsObservers = setOf("com.ports"),
                uidToPkg = mapOf(10123 to "com.observer"),
                canonicalConfig = null,
            )

        val cfg = buildCanonicalConfigFromTargetsSnapshot(snapshot, debug = true)

        assertTrue(cfg.debug)
        assertTrue(cfg.apps.getValue("com.java").java)
        assertEquals(NativeRole.All, cfg.apps.getValue("com.native").native)
        assertTrue(cfg.apps.getValue("com.hidden").hidden)
        assertTrue(cfg.apps.getValue("com.observer").appHiding)
        assertTrue(cfg.apps.getValue("com.ports").ports)
    }

    private fun sharedStorageFixture(): String =
        listOf(
            File("../../testdata/storage_config_v1.json"),
            File("../testdata/storage_config_v1.json"),
            File("testdata/storage_config_v1.json"),
        ).first(File::isFile).readText()
}
