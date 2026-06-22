package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetsCacheTest {
    @Test
    fun `targets snapshot parses module flags target files and observer names`() {
        val rootSnapshot =
            RootSnapshot(
                generation = 1,
                sections =
                    mapOf(
                        "kmod_module_dir" to "1",
                        "zygisk_module_dir" to "0",
                        "ports_prop" to "version=1.2.3",
                        "kmod_targets" to "# comment\ndev.okhsunrog.vpnhide\ncom.bank.app\n",
                        "zygisk_targets" to "com.chat.app\n\n",
                        "lsposed_targets" to "system\n# ignored\n",
                        "hidden_pkgs" to "com.hidden.one\ncom.hidden.two\n",
                        "observer_uids" to "10123\n1010123\nnot-a-uid\n",
                        "ports_observers" to "com.browser\n",
                        "pm_packages" to
                            "package:com.observer uid:10123,1010123\n" +
                            "package:com.other uid:20222\n",
                    ),
            )

        val targets = parseTargetsSnapshot(rootSnapshot)

        assertTrue(targets.kmodModuleInstalled)
        assertFalse(targets.zygiskModuleInstalled)
        assertTrue(targets.portsModuleInstalled)
        assertEquals(setOf("dev.okhsunrog.vpnhide", "com.bank.app"), targets.kmodTargets)
        assertEquals(setOf("com.chat.app"), targets.zygiskTargets)
        assertEquals(setOf("system"), targets.lsposedTargets)
        assertEquals(setOf("com.hidden.one", "com.hidden.two"), targets.hiddenPkgs)
        assertEquals(setOf(10123, 1010123), targets.observerUids)
        assertEquals(setOf("com.observer"), targets.observerNames)
        assertEquals(setOf("com.browser"), targets.portsObservers)
    }
}
