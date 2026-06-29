package dev.okhsunrog.vpnhide

import dev.okhsunrog.vpnhide.generated.HookIds

internal data class StatisticsState(
    val backends: List<BackendStatistics>,
) {
    val hasAnyData: Boolean = backends.any { it.hasData }
    val activeBackendCount: Int = backends.count { it.hasData }
    val totalRows: Int = backends.sumOf { it.rows.size }
    val totalCount: ULong = backends.fold(0uL) { acc, backend -> acc + backend.totalCount }
}

internal data class BackendStatistics(
    val backend: HookIds.Backend,
    val status: Protocol.Status?,
    val metadata: Map<String, String> = emptyMap(),
    val rows: List<StatisticsRow>,
) {
    val hasData: Boolean = status != null || rows.isNotEmpty()
    val totalCount: ULong = rows.fold(0uL) { acc, row -> acc + row.count.toULong() }
    val hookedCount: Int = status?.hooks?.let { java.lang.Long.bitCount(it) } ?: 0
}

internal data class StatisticsRow(
    val uid: Long,
    val packageNames: List<String>,
    val hookId: Long,
    val hook: HookIds.Hook?,
    val count: Long,
)

private val PROTOCOL_KINDS = setOf("config", "stats", "status")
private val HOOKS_BY_ID = HookIds.Hook.entries.associateBy { it.id.toLong() }

internal fun buildStatisticsState(snapshot: RootSnapshot): StatisticsState {
    val uidPackages = uidPackages(snapshot.sections["pm_packages"].orEmpty())
    val kmodRaw = snapshot.sections["kmod_state"].orEmpty()
    val kpmRaw = snapshot.sections["kpm_state"].orEmpty()
    val lsposedRaw = snapshot.sections["lsposed_state"].orEmpty()
    val nativeBackends =
        listOf(
            buildBackendStatistics(
                backend = HookIds.Backend.KMOD,
                status = parseProtocolStatusBlock(kmodRaw),
                stats = parseProtocolStatsBlock(kmodRaw),
                uidPackages = uidPackages,
            ),
            buildBackendStatistics(
                backend = HookIds.Backend.KPM,
                status = parseProtocolStatusBlock(kpmRaw),
                stats = parseProtocolStatsBlock(kpmRaw),
                uidPackages = uidPackages,
            ),
        )
    val lsposed =
        buildBackendStatistics(
            backend = HookIds.Backend.LSPOSED,
            status = parseProtocolStatusBlock(lsposedRaw),
            stats = parseProtocolStatsBlock(lsposedRaw),
            metadata = parseLsposedStateMetadata(lsposedRaw),
            uidPackages = uidPackages,
        )

    return StatisticsState(
        listOfNotNull(selectActiveNativeStatisticsBackend(nativeBackends), lsposed),
    )
}

internal fun parseProtocolStatusBlock(raw: String): Protocol.Status? =
    extractProtocolBlock(raw, Protocol.Kind.STATUS)?.let(Protocol::parseStatus)

internal fun parseProtocolStatsBlock(raw: String): List<Protocol.StatEntry> =
    extractProtocolBlock(raw, Protocol.Kind.STATS)?.let(Protocol::parseStats).orEmpty()

internal fun extractProtocolBlock(
    raw: String,
    kind: Protocol.Kind,
): String? {
    val lines = raw.split('\n').map { it.removeSuffix("\r") }
    val start = lines.indexOfFirst { protocolKindOfLine(it) == kind }
    if (start < 0) return null
    val end =
        lines
            .drop(start + 1)
            .indexOfFirst { protocolKindOfLine(it) != null }
            .let { if (it < 0) lines.size else start + 1 + it }
    return lines.subList(start, end).joinToString("\n").let { block ->
        if (block.endsWith("\n")) block else "$block\n"
    }
}

internal fun formatStatCount(count: ULong): String =
    count
        .toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

internal fun formatStatCount(count: Long): String = formatStatCount(count.toULong())

private fun buildBackendStatistics(
    backend: HookIds.Backend,
    status: Protocol.Status?,
    stats: List<Protocol.StatEntry>,
    uidPackages: Map<Long, List<String>>,
    metadata: Map<String, String> = emptyMap(),
): BackendStatistics =
    BackendStatistics(
        backend = backend,
        status = status,
        metadata = metadata,
        rows = buildStatisticsRows(stats, uidPackages),
    )

private val ACTIVE_NATIVE_STATUS_ERRORS =
    setOf(
        HookIds.StatusError.OK.code
            .toLong(),
        HookIds.StatusError.PARTIAL_HOOKS.code
            .toLong(),
    )

private fun selectActiveNativeStatisticsBackend(backends: List<BackendStatistics>): BackendStatistics? =
    backends.firstOrNull(BackendStatistics::isActiveNativeStatisticsBackend)

private fun BackendStatistics.isActiveNativeStatisticsBackend(): Boolean {
    if (rows.isNotEmpty()) return true
    val status = status ?: return false
    return status.backend == backend.id.toLong() && status.error in ACTIVE_NATIVE_STATUS_ERRORS
}

private fun buildStatisticsRows(
    stats: List<Protocol.StatEntry>,
    uidPackages: Map<Long, List<String>>,
): List<StatisticsRow> =
    stats
        .map { entry ->
            StatisticsRow(
                uid = entry.uid,
                packageNames = uidPackages[entry.uid].orEmpty(),
                hookId = entry.hookId,
                hook = HOOKS_BY_ID[entry.hookId],
                count = entry.count,
            )
        }.sortedWith(::compareStatisticsRows)

private fun compareStatisticsRows(
    left: StatisticsRow,
    right: StatisticsRow,
): Int {
    val byCount = java.lang.Long.compareUnsigned(right.count, left.count)
    if (byCount != 0) return byCount
    val byPackage = left.packageNames.joinToString().compareTo(right.packageNames.joinToString())
    if (byPackage != 0) return byPackage
    return left.hookId.compareTo(right.hookId)
}

private fun uidPackages(raw: String): Map<Long, List<String>> {
    val byUid = linkedMapOf<Long, MutableList<String>>()
    parsePackageUidMap(raw).forEach { (pkg, uids) ->
        uids.forEach { uid ->
            byUid.getOrPut(uid.toLong()) { mutableListOf() } += pkg
        }
    }
    return byUid.mapValues { (_, packages) -> packages.distinct().sorted() }
}

private fun protocolKindOfLine(line: String): Protocol.Kind? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    val tokens = trimmed.split(' ', '\t').filter { it.isNotEmpty() }
    if (tokens.size < 3 || tokens[0] != "vpnhide") return null
    if (tokens[1].toIntOrNull() == null || tokens[2] !in PROTOCOL_KINDS) return null
    return when (tokens[2]) {
        "config" -> Protocol.Kind.CONFIG
        "stats" -> Protocol.Kind.STATS
        "status" -> Protocol.Kind.STATUS
        else -> null
    }
}
