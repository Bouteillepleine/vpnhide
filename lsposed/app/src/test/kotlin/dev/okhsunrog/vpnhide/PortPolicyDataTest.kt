package dev.okhsunrog.vpnhide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortPolicyDataTest {
    @Test
    fun `port preset resolves known preset and rejects unknown ids`() {
        assertEquals(PORT_PRESET_COMMON_PROXY, portPreset(PORT_PRESET_COMMON_PROXY)?.id)
        assertNull(portPreset(null))
        assertNull(portPreset("unknown"))
    }

    @Test
    fun `port policy for preset materializes preset rules`() {
        val policy = portPolicyForPreset(PORT_PRESET_COMMON_PROXY)

        assertEquals(PortPolicyMode.Preset, policy?.mode)
        assertEquals(PORT_PRESET_COMMON_PROXY, policy?.preset)
        assertEquals(portPreset(PORT_PRESET_COMMON_PROXY)?.rules, policy?.rules)
        assertNull(portPolicyForPreset("unknown"))
    }

    @Test
    fun `normalized port rules deduplicate and sort`() {
        val udp = PortRule(protocol = PortProtocol.Udp, start = 8080)
        val tcp = PortRule(protocol = PortProtocol.Tcp, start = 8080)
        val lowRange = PortRule(start = 1080, end = 1081)
        val lowSingle = PortRule(start = 1080)

        assertEquals(
            listOf(lowSingle, lowRange, tcp, udp),
            normalizedPortRules(listOf(udp, lowRange, tcp, lowSingle, udp)),
        )
    }

    @Test
    fun `normalize port policy keeps null and normalizes rules`() {
        val policy =
            PortPolicy(
                mode = PortPolicyMode.Custom,
                rules =
                    listOf(
                        PortRule(start = 8080),
                        PortRule(start = 1080),
                        PortRule(start = 8080),
                    ),
            )

        assertNull(normalizePortPolicy(null))
        assertEquals(
            policy.copy(rules = listOf(PortRule(start = 1080), PortRule(start = 8080))),
            normalizePortPolicy(policy),
        )
    }

    @Test
    fun `policy ui mode follows null preset and custom state`() {
        assertEquals(PortPolicyUiMode.All, null.toUiMode())
        assertEquals(PortPolicyUiMode.Preset, portPolicyForPreset(PORT_PRESET_COMMON_PROXY).toUiMode())
        assertEquals(
            PortPolicyUiMode.Custom,
            PortPolicy(mode = PortPolicyMode.Preset, preset = "unknown", rules = listOf(PortRule(start = 1080))).toUiMode(),
        )
        assertEquals(
            PortPolicyUiMode.Custom,
            PortPolicy(mode = PortPolicyMode.Custom, rules = listOf(PortRule(start = 1080))).toUiMode(),
        )
    }

    @Test
    fun `port rule converts to editable fields`() {
        assertEquals(
            EditablePortRule(protocol = PortProtocol.Both, start = "1080", end = ""),
            PortRule(start = 1080).toEditable(),
        )
        assertEquals(
            EditablePortRule(protocol = PortProtocol.Tcp, start = "7890", end = "7892"),
            PortRule(protocol = PortProtocol.Tcp, start = 7890, end = 7892).toEditable(),
        )
    }

    @Test
    fun `editable port rule parses valid input and rejects invalid input`() {
        assertEquals(
            PortRule(protocol = PortProtocol.Tcp, start = 7890, end = 7892),
            EditablePortRule(protocol = PortProtocol.Tcp, start = "7890", end = "7892").toPortRuleOrNull(),
        )
        assertEquals(
            PortRule(start = 1080),
            EditablePortRule(start = "1080", end = "").toPortRuleOrNull(),
        )
        assertNull(EditablePortRule(start = "", end = "").toPortRuleOrNull())
        assertNull(EditablePortRule(start = "0", end = "").toPortRuleOrNull())
        assertNull(EditablePortRule(start = "65536", end = "").toPortRuleOrNull())
        assertNull(EditablePortRule(start = "9000", end = "8000").toPortRuleOrNull())
    }

    @Test
    fun `protocol cycles in editor order`() {
        assertEquals(PortProtocol.Tcp, PortProtocol.Both.next())
        assertEquals(PortProtocol.Udp, PortProtocol.Tcp.next())
        assertEquals(PortProtocol.Both, PortProtocol.Udp.next())
    }

    @Test
    fun `protocol labels and summaries are stable`() {
        assertEquals("TCP/UDP", protocolLabel(PortProtocol.Both))
        assertEquals("TCP", protocolLabel(PortProtocol.Tcp))
        assertEquals("UDP", protocolLabel(PortProtocol.Udp))
        assertEquals(
            "TCP/UDP 1080, TCP 7890-7892",
            portRulesSummary(
                listOf(
                    PortRule(start = 1080),
                    PortRule(protocol = PortProtocol.Tcp, start = 7890, end = 7892),
                ),
            ),
        )
    }
}
