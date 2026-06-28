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
}
