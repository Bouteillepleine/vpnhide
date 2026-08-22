package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Parses the generated root/debug probes with a real shell (`sh -n`).
 *
 * These scripts are one big `su -c` string: a single quoting slip anywhere kills
 * the *whole* command, and every section with it — which is invisible to a
 * `contains("emit_file …")` assertion. The regression that prompted this test
 * was an apostrophe in a comment inside a single-quoted `emit_eval` block
 * (`#` starts no comment in there, so the quote just ended early).
 *
 * Host `sh` is dash/bash and the device runs mksh, but the POSIX quoting and
 * compound-command grammar this catches are the same.
 */
class ShellSnapshotSyntaxTest {
    @Test
    fun `root snapshot command parses`() {
        assertParses("root_snapshot", buildRootShellSnapshotCommand())
    }

    @Test
    fun `root snapshot command parses without the package inventory`() {
        assertParses("root_snapshot_no_pm", buildRootShellSnapshotCommand(includePmPackages = false))
    }

    @Test
    fun `debug snapshot command parses`() {
        assertParses("debug_snapshot", buildDebugShellSnapshotCommand())
    }

    @Test
    fun `hook counter snapshot command parses`() {
        assertParses("counter_snapshot", buildHookCounterSnapshotCommand())
    }

    private fun assertParses(
        name: String,
        script: String,
    ) {
        val sh = listOf("/bin/sh", "/system/bin/sh").firstOrNull { File(it).canExecute() }
        assumeTrue("no POSIX shell to check against", sh != null)
        val file = File.createTempFile("vpnhide_$name", ".sh")
        try {
            file.writeText(script)
            val process =
                ProcessBuilder(sh, "-n", file.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            assertEquals("$name is not valid shell:\n$output", 0, process.exitValue())
        } finally {
            file.delete()
        }
    }
}
