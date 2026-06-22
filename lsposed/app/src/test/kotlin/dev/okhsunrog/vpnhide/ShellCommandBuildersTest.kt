package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandBuildersTest {
    @Test
    fun `package UID expression uses literal awk field comparison`() {
        val expr = buildPackageUidsExpression("com.example.app", "U")

        assertTrue(expr.contains("awk -v p=\"package:com.example.app\""))
        assertTrue(expr.contains("\$1 == p"))
        assertFalse(expr.contains("grep"))
    }

    @Test
    fun `UID resolver preserves multi-profile UID splitting`() {
        val cmd = buildUidResolverCommand(listOf("com.example.app"), "/tmp/out")

        assertTrue(cmd.contains("n = split(\$2, ids, \",\")"))
        assertTrue(cmd.contains("for (i = 1; i <= n; i++) print ids[i]"))
        assertTrue(cmd.contains("echo \"\$UIDS\" > /tmp/out"))
    }

    @Test
    fun `package UID expression does not treat dots as regex wildcards`() {
        val allPkgs =
            """
            package:comXexampleXapp uid:99999
            package:com.example.app uid:10123
            """.trimIndent()

        assertEquals("10123", runPackageUidExpression(allPkgs, "com.example.app"))
    }

    @Test
    fun `package UID expression expands comma-separated profile UIDs`() {
        val allPkgs = "package:com.example.app uid:10123,1010123"

        assertEquals("10123\n1010123", runPackageUidExpression(allPkgs, "com.example.app"))
    }

    @Test
    fun `package UID expression keeps repeated package lines`() {
        val allPkgs =
            """
            package:com.example.app uid:10123
            package:com.example.app uid:1010123
            """.trimIndent()

        assertEquals("10123\n1010123", runPackageUidExpression(allPkgs, "com.example.app"))
    }

    @Test
    fun `package UID expression returns empty output for unknown packages`() {
        val allPkgs = "package:com.other.app uid:10123"

        assertEquals("", runPackageUidExpression(allPkgs, "com.example.app"))
    }

    @Test
    fun `startup self target command batches root work and reports restart flag`() {
        val cmd = buildEnsureSelfInTargetsCommand("dev.okhsunrog.vpnhide")

        assertTrue(cmd.contains("ensure_line $KMOD_TARGETS"))
        assertTrue(cmd.contains("ensure_line $ZYGISK_TARGETS"))
        assertTrue(cmd.contains("ensure_line $LSPOSED_TARGETS"))
        assertTrue(cmd.contains("ensure_line $SS_HIDDEN_PKGS_FILE"))
        assertTrue(cmd.contains("pm list packages -U --user all"))
        assertTrue(cmd.contains(SELF_PM_PACKAGES_BEGIN))
        assertTrue(cmd.contains(SELF_PM_PACKAGES_END))
        assertTrue(cmd.contains("echo ADDED=\$ADDED"))
    }

    @Test
    fun `self target command is syntactically valid shell`() {
        // Guards against the flatten bug: collapsing the ensure_line() shell
        // function onto one line (`replace("\n", " ")`) dropped the separators
        // before `fi`, producing `then return fi` — a syntax error that broke
        // startup on-device. The other tests only do substring checks and
        // never parse the script, so this one runs `sh -n`.
        val cmd = buildEnsureSelfInTargetsCommand("dev.okhsunrog.vpnhide")
        val proc =
            ProcessBuilder("sh", "-n", "-c", cmd)
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        assertEquals("sh -n reported a syntax error: $out", 0, exitCode)
    }

    @Test
    fun `system data file perms set mode group context and tolerate chcon failure`() {
        val parts = systemDataFilePermsParts("/data/system/vpnhide_uids.txt", "640")

        assertEquals(
            listOf(
                "chmod 640 /data/system/vpnhide_uids.txt 2>/dev/null",
                "chown root:system /data/system/vpnhide_uids.txt 2>/dev/null",
                "chcon $SYSTEM_DATA_FILE_CONTEXT /data/system/vpnhide_uids.txt 2>/dev/null || true",
            ),
            parts,
        )
    }

    @Test
    fun `self target output extracts seeded package list`() {
        val output =
            """
            added:/data/adb/vpnhide_kmod/targets.txt
            $SELF_PM_PACKAGES_BEGIN
            package:dev.okhsunrog.vpnhide uid:10123,1010123
            package:com.example.app uid:10234
            $SELF_PM_PACKAGES_END
            BOOT_ID=boot
            ADDED=1
            """.trimIndent()

        assertEquals(
            "package:dev.okhsunrog.vpnhide uid:10123,1010123\npackage:com.example.app uid:10234",
            extractSelfTargetPmPackages(output),
        )
    }

    private fun runPackageUidExpression(
        allPkgs: String,
        packageName: String,
    ): String {
        val script =
            "ALL_PKGS=\$(cat <<'EOF'\n" +
                allPkgs +
                "\nEOF\n" +
                "); " +
                buildPackageUidsExpression(packageName, "U") +
                "; printf '%s' \"\$U\""
        val proc =
            ProcessBuilder("sh", "-c", script)
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        assertEquals("shell exited with output: $out", 0, exitCode)
        return out
    }
}
