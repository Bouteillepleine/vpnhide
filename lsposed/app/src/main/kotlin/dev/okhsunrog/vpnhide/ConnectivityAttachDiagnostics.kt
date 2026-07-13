package dev.okhsunrog.vpnhide

import dev.okhsunrog.vpnhide.generated.HookIds
import java.util.concurrent.CopyOnWriteArrayList

/** Stateful telemetry for the multi-path ConnectivityService hook attachment. */
internal class ConnectivityAttachDiagnostics {
    private val attempts = CopyOnWriteArrayList<String>()

    @Volatile private var attachedMeta: Map<String, String> = emptyMap()

    @Volatile private var attachedBits: Int = 0

    /** Compact classloader-chain fingerprint, e.g. PathClassLoader<BootClassLoader. */
    fun describeLoader(classLoader: ClassLoader?): String {
        if (classLoader == null) return "null"
        val description = StringBuilder()
        var current: ClassLoader? = classLoader
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) description.append('<')
            description.append(current.javaClass.simpleName.ifBlank { current.javaClass.name })
            current = current.parent
            depth++
        }
        return description.toString()
    }

    fun record(entry: String) {
        attempts.add(entry)
        publish()
    }

    fun report(
        path: String,
        serviceClass: Class<*>,
        classLoader: ClassLoader?,
        constructorCount: Int,
        resultCounts: Map<String, Int>,
        networkCounts: Map<String, Int>,
        callbackCounts: Map<String, Int>,
    ) {
        var bits = 0
        if (resultCounts.values.any { it > 0 }) bits = bits or hookBit(HookIds.Hook.LSPOSED_CONNECTIVITY_RESULT)
        if (networkCounts.values.any { it > 0 }) bits = bits or hookBit(HookIds.Hook.LSPOSED_CONNECTIVITY_NETWORK)
        if (callbackCounts.values.any { it > 0 }) bits = bits or hookBit(HookIds.Hook.LSPOSED_CONNECTIVITY_CALLBACK)
        attachedBits = bits

        attachedMeta =
            linkedMapOf(
                "cs_path" to path,
                "cs_class" to serviceClass.name,
                "cs_loader" to describeLoader(classLoader),
                "cs_ctor" to constructorCount.toString(),
                "cs_result" to formatCounts(resultCounts),
                "cs_network" to formatCounts(networkCounts),
                "cs_callback" to formatCounts(callbackCounts),
            )
        record(
            "$path:attached class=${serviceClass.simpleName} " +
                "net=[${formatCounts(networkCounts)}] cb=[${formatCounts(callbackCounts)}]",
        )
    }

    private fun publish() {
        val meta = LinkedHashMap<String, String>()
        meta["cs_attempts"] = attempts.joinToString(" | ").ifBlank { "(none)" }
        meta.putAll(attachedMeta)
        LsposedStats.setConnectivityDiagnostics(attachedBits, meta)
    }

    private fun formatCounts(counts: Map<String, Int>): String = counts.entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun hookBit(hook: HookIds.Hook): Int = 1 shl hook.id
}
