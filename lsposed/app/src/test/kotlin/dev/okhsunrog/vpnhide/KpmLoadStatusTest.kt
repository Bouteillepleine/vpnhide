package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KpmLoadStatusTest {
    @Test
    fun `parser maps known wire values to typed status`() {
        val status =
            parseKpmLoadStatus(
                """
                timestamp=42
                boot_id=boot-1
                uname_r=6.1.0-android
                runtime=kpatch-next
                loaded=0
                filesystem_hiding=1
                reason=unsupported_kernel
                detail=unsupported kernel
                """.trimIndent(),
            )

        assertEquals(42L, status.timestamp)
        assertEquals("boot-1", status.bootId)
        assertEquals("6.1.0-android", status.unameR)
        assertEquals(KpmRuntime.KpatchNext, status.runtime)
        assertEquals(false, status.loaded)
        assertEquals(true, status.filesystemHiding)
        assertEquals(KpmFailureReason.UnsupportedKernel, status.reason)
        assertEquals("unsupported kernel", status.detail)
        assertTrue(status.isFreshFor("boot-1"))
    }

    @Test
    fun `parser keeps unknown and missing wire values non-actionable`() {
        val status = parseKpmLoadStatus("runtime=future-runtime\nloaded=maybe\nreason=future-reason\n")

        assertEquals(KpmRuntime.Unknown, status.runtime)
        assertEquals(null, status.loaded)
        assertEquals(null, status.filesystemHiding)
        assertEquals(KpmFailureReason.Unknown, status.reason)
        assertFalse(status.isFreshFor("boot-1"))
    }
}
