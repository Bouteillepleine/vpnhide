package dev.okhsunrog.vpnhide

internal enum class KpmRuntime {
    Activator,
    KpatchNext,
    Apatch,
    Conflict,
    Unknown,
}

internal enum class KpmFailureReason {
    Ok,
    ConflictingBackend,
    MissingKpm,
    AwaitingSuperkey,
    UnsupportedKernel,
    ActivationFailed,
    LoadFailed,
    Unknown,
}

internal data class KpmLoadStatus(
    val timestamp: Long?,
    val bootId: String?,
    val unameR: String?,
    val runtime: KpmRuntime,
    val loaded: Boolean?,
    val filesystemHiding: Boolean?,
    val reason: KpmFailureReason,
    val detail: String?,
) {
    fun isFreshFor(currentBootId: String): Boolean = !bootId.isNullOrEmpty() && bootId == currentBootId.trim()
}

private fun Map<String, String>.optionalBoolean(key: String): Boolean? =
    when (this[key]?.trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }

internal fun parseKpmLoadStatus(raw: String): KpmLoadStatus {
    val values = parseKeyValueLines(raw)
    return KpmLoadStatus(
        timestamp = values["timestamp"]?.trim()?.toLongOrNull(),
        bootId = values["boot_id"]?.trim()?.ifEmpty { null },
        unameR = values["uname_r"]?.trim()?.ifEmpty { null },
        runtime =
            when (values["runtime"]?.trim()) {
                "activator" -> KpmRuntime.Activator
                "kpatch-next" -> KpmRuntime.KpatchNext
                "apatch" -> KpmRuntime.Apatch
                "conflict" -> KpmRuntime.Conflict
                else -> KpmRuntime.Unknown
            },
        loaded = values.optionalBoolean("loaded"),
        filesystemHiding = values.optionalBoolean("filesystem_hiding"),
        reason =
            when (values["reason"]?.trim()) {
                "ok" -> KpmFailureReason.Ok
                "conflicting_backend" -> KpmFailureReason.ConflictingBackend
                "missing_kpm" -> KpmFailureReason.MissingKpm
                "awaiting_superkey" -> KpmFailureReason.AwaitingSuperkey
                "unsupported_kernel" -> KpmFailureReason.UnsupportedKernel
                "activation_failed" -> KpmFailureReason.ActivationFailed
                "load_failed" -> KpmFailureReason.LoadFailed
                else -> KpmFailureReason.Unknown
            },
        detail = values["detail"]?.trim()?.ifEmpty { null },
    )
}
