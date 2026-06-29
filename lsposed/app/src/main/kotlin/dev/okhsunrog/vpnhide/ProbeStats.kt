package dev.okhsunrog.vpnhide

import dev.okhsunrog.vpnhide.generated.HookIds

// Where a detection method lives — used to group methods on the per-app card and
// to explain (native syscall/libc vs Java API vs package enumeration) at a
// glance. "Native" covers both the kernel backends and Zygisk's libc hooks.
internal enum class MethodSurface { Java, Native, Package }

// User-facing detection method: a small taxonomy over the 18 raw hooks so the
// Statistics screen reads as "what the app tried" instead of kernel symbol
// names. Several hooks fold into one method (e.g. the four route hooks).
internal enum class DetectionMethod(
    val surface: MethodSurface,
    val labelRes: Int,
) {
    Routes(MethodSurface.Native, R.string.method_routes),
    Interfaces(MethodSurface.Native, R.string.method_interfaces),
    InterfaceIoctl(MethodSurface.Native, R.string.method_interface_ioctl),
    PolicyRules(MethodSurface.Native, R.string.method_policy_rules),
    NetworkCapabilities(MethodSurface.Java, R.string.method_network_capabilities),
    LinkProperties(MethodSurface.Java, R.string.method_link_properties),
    NetworkInfo(MethodSurface.Java, R.string.method_network_info),
    NetworkHandle(MethodSurface.Java, R.string.method_network_handle),
    ConnectivityService(MethodSurface.Java, R.string.method_connectivity_service),
    PackageEnumeration(MethodSurface.Package, R.string.method_package_enumeration),
    ;

    companion object {
        fun of(hook: HookIds.Hook): DetectionMethod =
            when (hook) {
                HookIds.Hook.FIB_ROUTE_SEQ_SHOW,
                HookIds.Hook.IPV6_ROUTE_SEQ_SHOW,
                HookIds.Hook.FIB_DUMP_INFO,
                HookIds.Hook.RT6_FILL_NODE,
                -> Routes

                HookIds.Hook.RTNL_FILL_IFINFO,
                HookIds.Hook.INET_FILL_IFADDR,
                HookIds.Hook.INET6_FILL_IFADDR,
                -> Interfaces

                HookIds.Hook.DEV_IOCTL,
                HookIds.Hook.SOCK_IOCTL,
                -> InterfaceIoctl

                HookIds.Hook.FIB_NL_FILL_RULE -> PolicyRules

                HookIds.Hook.LSPOSED_NETWORK_CAPABILITIES -> NetworkCapabilities

                HookIds.Hook.LSPOSED_LINK_PROPERTIES -> LinkProperties

                HookIds.Hook.LSPOSED_NETWORK_INFO -> NetworkInfo

                HookIds.Hook.LSPOSED_NETWORK,
                HookIds.Hook.LSPOSED_CONNECTIVITY_NETWORK,
                -> NetworkHandle

                HookIds.Hook.LSPOSED_CONNECTIVITY_RESULT,
                HookIds.Hook.LSPOSED_CONNECTIVITY_CALLBACK,
                -> ConnectivityService

                HookIds.Hook.LSPOSED_PACKAGE_VISIBILITY -> PackageEnumeration

                // Zygisk libc hooks — native-level probes from inside the app.
                HookIds.Hook.ZYGISK_IOCTL -> InterfaceIoctl

                HookIds.Hook.ZYGISK_GETIFADDRS -> Interfaces

                HookIds.Hook.ZYGISK_OPENAT -> Routes

                HookIds.Hook.ZYGISK_RECVMSG,
                HookIds.Hook.ZYGISK_RECV,
                HookIds.Hook.ZYGISK_RECVFROM,
                HookIds.Hook.ZYGISK_RECVFROM_CHK,
                -> Interfaces
            }
    }
}

// Per-app rollup of probe counts, aggregated across every active backend (the
// one native backend + Java). Counts are cumulative since each backend started.
internal data class AppProbeStats(
    val uid: Long,
    val packageNames: List<String>,
    val total: ULong,
    val byMethod: Map<DetectionMethod, Long>,
) {
    val surfaces: Set<MethodSurface> = byMethod.keys.map { it.surface }.toSet()
}

// Collapse the per-backend / per-(uid×hook) rows into one entry per app, with a
// per-method breakdown. Hooks map to methods; an unknown hook id still counts
// toward the app total but has no method bucket. Sorted by total, descending.
//
// [selfPackage] (VPN Hide's own package) is excluded: the app's cold-start
// diagnostic check suite probes every vector against itself, which would
// otherwise dominate the list as self-noise rather than a real prober.
internal fun buildAppProbeStats(
    state: StatisticsState,
    selfPackage: String? = null,
): List<AppProbeStats> {
    class Acc {
        var total: ULong = 0uL
        var packages: List<String> = emptyList()
        val byMethod = linkedMapOf<DetectionMethod, Long>()
    }

    val byUid = linkedMapOf<Long, Acc>()
    state.backends
        .asSequence()
        .flatMap { it.rows.asSequence() }
        .forEach { row ->
            val acc = byUid.getOrPut(row.uid) { Acc() }
            acc.total += row.count.toULong()
            if (row.packageNames.isNotEmpty()) acc.packages = row.packageNames
            val method = row.hook?.let(DetectionMethod::of) ?: return@forEach
            acc.byMethod[method] = (acc.byMethod[method] ?: 0L) + row.count
        }

    return byUid
        .map { (uid, acc) ->
            AppProbeStats(
                uid = uid,
                packageNames = acc.packages,
                total = acc.total,
                byMethod = acc.byMethod.toMap(),
            )
        }.filterNot { selfPackage != null && selfPackage in it.packageNames }
        .sortedWith(
            compareByDescending<AppProbeStats> { it.total }
                .thenBy { it.packageNames.joinToString() }
                .thenBy { it.uid },
        )
}
