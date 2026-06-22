package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellUtilsParsersTest {
    @Test
    fun `parseKeyValueLines splits on first equals and keeps the rest of the value`() {
        val raw =
            """
            version=0.6.3
            updateJson=https://example.com/x?a=1&b=2
            not a kv line
            boot_id=abc
            """.trimIndent()
        val map = parseKeyValueLines(raw)
        assertEquals("0.6.3", map["version"])
        assertEquals("https://example.com/x?a=1&b=2", map["updateJson"])
        assertEquals("abc", map["boot_id"])
        assertEquals(3, map.size)
    }

    @Test
    fun `parsePackageUidMap reads single-profile uid`() {
        val map = parsePackageUidMap("package:com.example uid:10123")
        assertEquals(listOf(10123), map["com.example"])
    }

    @Test
    fun `parsePackageUidMap splits comma-separated multi-profile uids`() {
        val map = parsePackageUidMap("package:com.example uid:10123,1010123")
        assertEquals(listOf(10123, 1010123), map["com.example"])
    }

    @Test
    fun `parsePackageUidMap unions repeated lines for the same package`() {
        val raw =
            """
            package:com.example uid:10123
            package:com.example uid:1010123
            """.trimIndent()
        assertEquals(listOf(10123, 1010123), parsePackageUidMap(raw)["com.example"])
    }

    @Test
    fun `parsePackageUidMap drops non-integer uids and non-package lines`() {
        val raw =
            """
            package:com.example uid:10123,bogus
            random noise
            """.trimIndent()
        assertEquals(listOf(10123), parsePackageUidMap(raw)["com.example"])
        assertEquals(1, parsePackageUidMap(raw).size)
    }
}
