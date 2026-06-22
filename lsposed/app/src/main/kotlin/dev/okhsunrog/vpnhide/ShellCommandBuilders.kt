package dev.okhsunrog.vpnhide

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
