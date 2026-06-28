package dev.okhsunrog.vpnhide

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
    val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
    return "echo '$b64' | base64 -d > $path"
}

internal fun buildAtomicRawWriteCommand(
    path: String,
    content: String,
): String {
    val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
    return "TMP=$path.tmp.\$\$ ; echo '$b64' | base64 -d > \"\$TMP\" && mv \"\$TMP\" $path"
}

/**
 * The chmod/chown/chcon tail applied to a `/data/system` file so that
 * system_server (group=system) can read it while untrusted apps get EACCES,
 * and so anti-tamper SDKs can't read our marker files. Returns the steps as
 * separate parts meant to be joined with `" ; "` — matching how the save
 * commands assemble. `chcon` failure is tolerated (`|| true`) for devices/
 * filesystems without SELinux labelling support, and is the last step so the
 * overall `su -c` exit code stays 0 on such devices.
 *
 * Previously each save path hand-wrote this tail slightly differently (one
 * omitted `chcon` entirely on the empty branch, one lacked the `|| true`
 * fail-safe), risking a permission/SELinux drift. Single source now.
 */
internal fun systemDataFilePermsParts(
    path: String,
    mode: String,
): List<String> =
    listOf(
        "chmod $mode $path 2>/dev/null",
        "chown root:system $path 2>/dev/null",
        "chcon $SYSTEM_DATA_FILE_CONTEXT $path 2>/dev/null || true",
    )
