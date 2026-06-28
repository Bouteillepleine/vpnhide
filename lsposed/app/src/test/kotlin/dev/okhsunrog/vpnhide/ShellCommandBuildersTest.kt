package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandBuildersTest {
    @Test
    fun `system data atomic write prepares tmp permissions before rename`() {
        val cmd = buildAtomicSystemDataRawWriteCommand("/data/system/vpnhide_config.json", "{}", "640")

        assertTrue(cmd.contains("base64 -d > \"\$TMP\" && chmod 640 \"\$TMP\" 2>/dev/null"))
        assertTrue(cmd.contains("&& chown root:system \"\$TMP\" 2>/dev/null"))
        assertTrue(
            cmd.contains(
                "&& (chcon $SYSTEM_DATA_FILE_CONTEXT \"\$TMP\" 2>/dev/null || true) " +
                    "&& mv \"\$TMP\" /data/system/vpnhide_config.json",
            ),
        )
        assertTrue(!cmd.contains("mv \"\$TMP\" /data/system/vpnhide_config.json && chmod"))
    }

    @Test
    fun `superkey clear removes root only key file`() {
        val cmd = buildSuperkeyClearCommand()

        assertEquals("rm -f $SUPERKEY_FILE", cmd)
    }
}
