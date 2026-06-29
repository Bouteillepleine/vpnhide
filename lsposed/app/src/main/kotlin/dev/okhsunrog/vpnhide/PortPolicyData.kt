package dev.okhsunrog.vpnhide

private val VALID_PORT_RANGE = 1..65535

internal enum class PortPolicyMode(
    val jsonName: String,
) {
    Preset("preset"),
    Custom("custom"),
    ;

    companion object {
        fun fromJson(value: String): PortPolicyMode = entries.firstOrNull { it.jsonName == value } ?: Custom
    }
}

internal enum class PortProtocol(
    val jsonName: String,
) {
    Both("both"),
    Tcp("tcp"),
    Udp("udp"),
    ;

    companion object {
        fun fromJson(value: String): PortProtocol = entries.firstOrNull { it.jsonName == value } ?: Both
    }
}

internal data class PortRule(
    val protocol: PortProtocol = PortProtocol.Both,
    val start: Int,
    val end: Int = start,
) {
    init {
        require(start in VALID_PORT_RANGE) { "Port start must be 1..65535" }
        require(end in VALID_PORT_RANGE) { "Port end must be 1..65535" }
        require(start <= end) { "Port start must not be greater than end" }
    }
}

internal data class PortPolicy(
    val mode: PortPolicyMode = PortPolicyMode.Custom,
    val preset: String? = null,
    val rules: List<PortRule>,
) {
    init {
        require(rules.isNotEmpty()) { "Port policy must contain at least one rule" }
    }
}

internal data class PortPolicyPreset(
    val id: String,
    val rules: List<PortRule>,
)

internal enum class PortPolicyUiMode { All, Preset, Custom }

internal data class EditablePortRule(
    val protocol: PortProtocol = PortProtocol.Both,
    val start: String = "",
    val end: String = "",
)

internal const val PORT_PRESET_COMMON_PROXY = "common_proxy"

internal val PortPolicyPresets: List<PortPolicyPreset> =
    listOf(
        PortPolicyPreset(
            id = PORT_PRESET_COMMON_PROXY,
            rules =
                normalizedPortRules(
                    listOf(
                        PortRule(start = 1080),
                        PortRule(start = 7890, end = 7892),
                        PortRule(start = 8080),
                        PortRule(start = 8888),
                        PortRule(start = 9050),
                        PortRule(start = 9090),
                        PortRule(start = 9150),
                        PortRule(start = 10808),
                    ),
                ),
        ),
    )

internal fun portPreset(id: String?): PortPolicyPreset? = PortPolicyPresets.firstOrNull { it.id == id }

internal fun portPolicyForPreset(id: String): PortPolicy? =
    portPreset(id)?.let { preset ->
        PortPolicy(
            mode = PortPolicyMode.Preset,
            preset = preset.id,
            rules = preset.rules,
        )
    }

internal fun normalizePortPolicy(policy: PortPolicy?): PortPolicy? = policy?.copy(rules = normalizedPortRules(policy.rules))

internal fun normalizedPortRules(rules: List<PortRule>): List<PortRule> =
    rules
        .distinct()
        .sortedWith(compareBy<PortRule> { it.start }.thenBy { it.end }.thenBy { it.protocol.ordinal })

internal fun PortPolicy?.toUiMode(): PortPolicyUiMode =
    when {
        this == null -> PortPolicyUiMode.All
        mode == PortPolicyMode.Preset && portPreset(preset) != null -> PortPolicyUiMode.Preset
        else -> PortPolicyUiMode.Custom
    }

internal fun PortRule.toEditable(): EditablePortRule =
    EditablePortRule(
        protocol = protocol,
        start = start.toString(),
        end = if (end == start) "" else end.toString(),
    )

internal fun EditablePortRule.toPortRuleOrNull(): PortRule? {
    val startPort = start.toIntOrNull() ?: return null
    val endPort = end.takeIf { it.isNotBlank() }?.toIntOrNull() ?: startPort
    return runCatching {
        PortRule(
            protocol = protocol,
            start = startPort,
            end = endPort,
        )
    }.getOrNull()
}

internal fun PortProtocol.next(): PortProtocol =
    when (this) {
        PortProtocol.Both -> PortProtocol.Tcp
        PortProtocol.Tcp -> PortProtocol.Udp
        PortProtocol.Udp -> PortProtocol.Both
    }

internal fun protocolLabel(protocol: PortProtocol): String =
    when (protocol) {
        PortProtocol.Both -> "TCP/UDP"
        PortProtocol.Tcp -> "TCP"
        PortProtocol.Udp -> "UDP"
    }

internal fun portRulesSummary(rules: List<PortRule>): String =
    rules.joinToString(", ") { rule ->
        val ports = if (rule.start == rule.end) "${rule.start}" else "${rule.start}-${rule.end}"
        "${protocolLabel(rule.protocol)} $ports"
    }
