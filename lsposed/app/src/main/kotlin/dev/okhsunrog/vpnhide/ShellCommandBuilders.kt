package dev.okhsunrog.vpnhide

import java.util.Base64

internal const val SYSTEM_DATA_FILE_CONTEXT = "u:object_r:system_data_file:s0"

/**
 * Shell fragment writing arbitrary [content] to [path] via a base64 round-trip:
 * `echo '<b64>' | base64 -d > path`. base64 sidesteps every quoting/escaping
 * hazard inside the single `su -c` string. Callers append their own `chmod` /
 * dir-guard / perms tail.
 */
internal fun buildRawWriteCommand(
    path: String,
    content: String,
): String {
    val b64 = base64NoWrap(content)
    return "echo '$b64' | base64 -d > $path"
}

internal fun buildAtomicRawWriteCommand(
    path: String,
    content: String,
): String {
    val b64 = base64NoWrap(content)
    return "TMP=$path.tmp.\$\$ ; echo '$b64' | base64 -d > \"\$TMP\" && mv \"\$TMP\" $path"
}

internal fun buildAtomicSystemDataRawWriteCommand(
    path: String,
    content: String,
    mode: String,
): String {
    val b64 = base64NoWrap(content)
    return listOf(
        "TMP=$path.tmp.\$\$",
        "echo '$b64' | base64 -d > \"\$TMP\"",
        "chmod $mode \"\$TMP\" 2>/dev/null",
        "chown root:system \"\$TMP\" 2>/dev/null",
        "(chcon $SYSTEM_DATA_FILE_CONTEXT \"\$TMP\" 2>/dev/null || true)",
        "mv \"\$TMP\" $path",
    ).joinToString(" && ")
}

private fun base64NoWrap(content: String): String = Base64.getEncoder().encodeToString(content.toByteArray())
