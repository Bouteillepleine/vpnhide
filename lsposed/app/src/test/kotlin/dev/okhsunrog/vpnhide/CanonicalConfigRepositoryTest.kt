package dev.okhsunrog.vpnhide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalConfigRepositoryTest {
    @Test
    fun `persistence command orders write coupled state and activators`() {
        val command =
            buildCanonicalPersistenceCommand(
                CanonicalConfig(debug = true),
                coupledCommands = listOf("write-secret"),
                activation = CanonicalActivation(native = true, ports = true),
            )

        val canonical = command.indexOf(CANONICAL_CONFIG_FILE)
        val secret = command.indexOf("write-secret")
        val native = command.indexOf(ConfigChannels.nativeActivatorCommand())
        val ports = command.indexOf(ConfigChannels.portsActivatorCommand())
        assertTrue(canonical >= 0)
        assertTrue(secret > canonical)
        assertTrue(native > secret)
        assertTrue(ports > native)
        assertFalse(command.contains(" ; "))
    }

    @Test
    fun `activation can be disabled for settings-only writes`() {
        val command =
            buildCanonicalPersistenceCommand(
                CanonicalConfig(),
                activation = CanonicalActivation(native = false, ports = false),
            )

        assertFalse(command.contains("activator"))
    }
}
