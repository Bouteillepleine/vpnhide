package dev.okhsunrog.vpnhide

internal const val SELF_PM_PACKAGES_BEGIN = "__VPNHIDE_SELF_PM_PACKAGES_BEGIN__"
internal const val SELF_PM_PACKAGES_END = "__VPNHIDE_SELF_PM_PACKAGES_END__"

internal const val SYSTEM_DATA_FILE_CONTEXT = "u:object_r:system_data_file:s0"

/**
 * Canonical on-disk body for a managed vpnhide config file: the `# Managed …`
 * header comment followed by [lines], newline-terminated. This is the format
 * [parseConfigLines] reads back (the header comment and blank lines are
 * dropped on read), so every Save path produces an identical file shape.
 */
internal fun managedConfigBody(
    header: String,
    lines: List<String>,
): String = (listOf(header) + lines).joinToString(separator = "\n", postfix = "\n")

/**
 * Shell fragment writing a managed config file via a base64 round-trip:
 * `echo '<b64>' | base64 -d > path`. base64 sidesteps every quoting/escaping
 * hazard from package names or the header text inside the single `su -c`
 * string. Callers append their own `chmod` / dir-guard / perms tail — those
 * differ per file (644 vs system_data_file 640), see [systemDataFilePermsParts].
 */
internal fun buildConfigWriteCommand(
    path: String,
    header: String,
    lines: List<String>,
): String {
    val b64 =
        android.util.Base64.encodeToString(
            managedConfigBody(header, lines).toByteArray(),
            android.util.Base64.NO_WRAP,
        )
    return "echo '$b64' | base64 -d > $path"
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

internal fun buildPackageUidsExpression(
    packageName: String,
    outputVariable: String,
): String =
    "$outputVariable=\$(echo \"\$ALL_PKGS\" | awk -v p=\"package:$packageName\" " +
        "'\$1 == p { sub(/uid:/, \"\", \$2); n = split(\$2, ids, \",\"); " +
        "for (i = 1; i <= n; i++) print ids[i] }')"

internal fun buildUidResolverCommand(
    packages: List<String>,
    outputFile: String,
): String =
    buildString {
        append("ALL_PKGS=\"\$(pm list packages -U --user all 2>/dev/null)\"")
        append("; UIDS=\"\"")
        for (pkg in packages) {
            append("; ")
            append(buildPackageUidsExpression(pkg, "U"))
            append("; if [ -n \"\$U\" ]; then if [ -z \"\$UIDS\" ]; then UIDS=\"\$U\"; else UIDS=\"\$UIDS")
            append("\n")
            append("\$U\"; fi; fi")
        }
        append("; if [ -n \"\$UIDS\" ]; then echo \"\$UIDS\" > $outputFile 2>/dev/null")
        append("; else echo > $outputFile 2>/dev/null; fi")
    }

// Long because it's a single embedded shell script (the startup self-target
// batch), not Kotlin control flow.
@Suppress("LongMethod")
internal fun buildEnsureSelfInTargetsCommand(selfPkg: String): String =
    buildString {
        append("SELF_PKG=\"")
        append(selfPkg)
        append("\"")
        append("; ADDED=0")
        append(
            """
            ; ensure_line() {
              PATH_TO_UPDATE="${'$'}1"
              REQUIRED_DIR="${'$'}2"
              MODE="${'$'}3"
              SYSTEM_FILE="${'$'}4"
              COUNTS_RESTART="${'$'}5"
              if [ -n "${'$'}REQUIRED_DIR" ] && [ ! -d "${'$'}REQUIRED_DIR" ]; then
                return
              fi
              EXISTING="${'$'}(cat "${'$'}PATH_TO_UPDATE" 2>/dev/null | sed 's/\r${'$'}//' | awk 'NF && ${'$'}1 !~ /^#/ { print }')"
              if printf '%s\n' "${'$'}EXISTING" | awk -v p="${'$'}SELF_PKG" '${'$'}0 == p { found=1 } END { exit found ? 0 : 1 }'; then
                return
              fi
              BODY="${'$'}(printf '%s\n%s\n' "${'$'}EXISTING" "${'$'}SELF_PKG" | awk 'NF { print }' | sort -u)"
              { printf '# Managed by VPN Hide app\n'; printf '%s\n' "${'$'}BODY"; } > "${'$'}PATH_TO_UPDATE"
              chmod "${'$'}MODE" "${'$'}PATH_TO_UPDATE"
              if [ "${'$'}SYSTEM_FILE" = 1 ]; then
                chown root:system "${'$'}PATH_TO_UPDATE"
                chcon u:object_r:system_data_file:s0 "${'$'}PATH_TO_UPDATE" 2>/dev/null || true
              fi
              if [ "${'$'}COUNTS_RESTART" = 1 ]; then
                ADDED=1
              fi
              echo "added:${'$'}PATH_TO_UPDATE"
            }
            """.trimIndent(),
        )
        append("; ensure_line $KMOD_TARGETS /data/adb/vpnhide_kmod 644 0 1")
        append("; ensure_line $ZYGISK_TARGETS /data/adb/vpnhide_zygisk 644 0 1")
        append("; if [ -d $ZYGISK_MODULE_DIR ]; then cp $ZYGISK_TARGETS $ZYGISK_MODULE_TARGETS 2>&1 || echo zygisk_cp_failed; fi")
        append("; mkdir -p /data/adb/vpnhide_lsposed")
        append("; ensure_line $LSPOSED_TARGETS '' 644 0 1")
        append("; ensure_line $SS_HIDDEN_PKGS_FILE '' 640 1 0")
        append("; ALL_PKGS=\"\$(pm list packages -U --user all 2>/dev/null)\"")
        append("; echo $SELF_PM_PACKAGES_BEGIN")
        append("; printf '%s\\n' \"\$ALL_PKGS\"")
        append("; echo $SELF_PM_PACKAGES_END")
        append("; ")
        append(buildPackageUidsExpression(selfPkg, "SELF_UIDS"))
        append("; if [ -n \"\$SELF_UIDS\" ]; then")
        append(" for U in \$SELF_UIDS; do")
        append("   case \"\$U\" in ''|*[!0-9]*) continue;; esac")
        append(" ; if [ -f $PROC_TARGETS ]; then")
        append("     grep -qx \"\$U\" $PROC_TARGETS 2>/dev/null || echo \"\$U\" >> $PROC_TARGETS")
        append("   ; fi")
        append(" ; grep -qx \"\$U\" $SS_UIDS_FILE 2>/dev/null || {")
        append("     echo \"\$U\" >> $SS_UIDS_FILE")
        append("   ; chmod 640 $SS_UIDS_FILE")
        append("   ; chown root:system $SS_UIDS_FILE")
        append("   ; chcon u:object_r:system_data_file:s0 $SS_UIDS_FILE 2>/dev/null || true")
        append("   ; }")
        append(" ; done")
        append("; fi")
        append("; echo BOOT_ID=\$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)")
        append("; echo ADDED=\$ADDED")
    }
