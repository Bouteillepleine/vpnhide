package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellCommandBuildersTest {
    @Test
    fun `system data file perms set mode group context and tolerate chcon failure`() {
        val parts = systemDataFilePermsParts("/data/system/vpnhide_config.json", "640")

        assertEquals(
            listOf(
                "chmod 640 /data/system/vpnhide_config.json 2>/dev/null",
                "chown root:system /data/system/vpnhide_config.json 2>/dev/null",
                "chcon $SYSTEM_DATA_FILE_CONTEXT /data/system/vpnhide_config.json 2>/dev/null || true",
            ),
            parts,
        )
    }

    @Test
    fun `superkey clear removes root only key file`() {
        val cmd = buildSuperkeyClearCommand()

        assertEquals("rm -f $SUPERKEY_FILE", cmd)
    }
}
