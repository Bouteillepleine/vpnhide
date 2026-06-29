package dev.okhsunrog.vpnhide

import dev.okhsunrog.vpnhide.generated.HookIds
import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeStatsTest {
    private fun row(
        uid: Long,
        hook: HookIds.Hook,
        count: Long,
        pkg: String = "pkg$uid",
    ) = StatisticsRow(uid = uid, packageNames = listOf(pkg), hookId = hook.id.toLong(), hook = hook, count = count)

    private fun backend(
        id: HookIds.Backend,
        rows: List<StatisticsRow>,
    ) = BackendStatistics(backend = id, status = null, rows = rows)

    @Test
    fun `every hook maps to a method`() {
        // Exhaustive-when is enforced at compile time; this also pins surfaces.
        HookIds.Hook.entries.forEach { DetectionMethod.of(it) }
        assertEquals(MethodSurface.Java, DetectionMethod.of(HookIds.Hook.LSPOSED_NETWORK_CAPABILITIES).surface)
        assertEquals(MethodSurface.Native, DetectionMethod.of(HookIds.Hook.DEV_IOCTL).surface)
        assertEquals(MethodSurface.Native, DetectionMethod.of(HookIds.Hook.ZYGISK_GETIFADDRS).surface)
        assertEquals(MethodSurface.Package, DetectionMethod.of(HookIds.Hook.LSPOSED_PACKAGE_VISIBILITY).surface)
        // Hooks fold into a shared method.
        assertEquals(DetectionMethod.Routes, DetectionMethod.of(HookIds.Hook.FIB_ROUTE_SEQ_SHOW))
        assertEquals(DetectionMethod.Routes, DetectionMethod.of(HookIds.Hook.FIB_DUMP_INFO))
    }

    @Test
    fun `aggregates per app across backends, folds hooks into methods, sorts by total`() {
        val state =
            StatisticsState(
                backends =
                    listOf(
                        backend(
                            HookIds.Backend.LSPOSED,
                            listOf(
                                row(10100, HookIds.Hook.LSPOSED_NETWORK_CAPABILITIES, 5),
                                row(10100, HookIds.Hook.LSPOSED_LINK_PROPERTIES, 3),
                            ),
                        ),
                        backend(
                            HookIds.Backend.KMOD,
                            listOf(
                                row(10100, HookIds.Hook.DEV_IOCTL, 2),
                                row(10200, HookIds.Hook.FIB_ROUTE_SEQ_SHOW, 7),
                                row(10200, HookIds.Hook.FIB_DUMP_INFO, 1),
                            ),
                        ),
                    ),
            )

        val apps = buildAppProbeStats(state)

        assertEquals(listOf(10100L, 10200L), apps.map { it.uid }) // total 10 before total 8
        val first = apps[0]
        assertEquals(10uL, first.total)
        assertEquals(
            mapOf(
                DetectionMethod.NetworkCapabilities to 5L,
                DetectionMethod.LinkProperties to 3L,
                DetectionMethod.InterfaceIoctl to 2L,
            ),
            first.byMethod,
        )
        assertEquals(setOf(MethodSurface.Java, MethodSurface.Native), first.surfaces)

        val second = apps[1]
        assertEquals(8uL, second.total)
        // The two route hooks folded into one Routes method.
        assertEquals(mapOf(DetectionMethod.Routes to 8L), second.byMethod)
        assertEquals(setOf(MethodSurface.Native), second.surfaces)
    }

    @Test
    fun `empty state yields no apps`() {
        assertEquals(emptyList<AppProbeStats>(), buildAppProbeStats(StatisticsState(backends = emptyList())))
    }
}
