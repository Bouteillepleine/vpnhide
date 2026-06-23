package dev.okhsunrog.vpnhide

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.okhsunrog.vpnhide.generated.IfaceLists
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.reflect.Array as JavaArray

/**
 * VpnHide — hide VPN presence from apps via system_server Binder hooks.
 *
 * Hooks writeToParcel() on NetworkCapabilities, NetworkInfo, and
 * LinkProperties inside system_server. When the Binder caller is a
 * target UID, VPN-related data is stripped before serialization —
 * the app receives clean data without any in-process hooks.
 *
 * This covers all Java API detection paths:
 *   - NetworkCapabilities: hasTransport(VPN), hasCapability(NOT_VPN),
 *     getTransportTypes(), getTransportInfo(), toString()
 *   - NetworkInfo: getType(), getTypeName()
 *   - ConnectivityManager: all methods that return NetworkCapabilities,
 *     NetworkInfo, or LinkProperties over Binder
 *   - LinkProperties: getInterfaceName(), getRoutes(), getDnsServers()
 *
 * Native detection paths (getifaddrs, ioctl, /proc/net) are covered
 * by vpnhide-kmod (kernel module) or vpnhide-zygisk (in-process hooks).
 *
 * Only "System Framework" needs to be in LSPosed scope.
 */
class HookEntry : IXposedHookLoadPackage {
    private val hookInstalled = AtomicBoolean(false)

    // Guards installing the ConnectivityService callback hook exactly once —
    // it can be triggered from either the direct lookup or the addService catch.
    private val connectivityHooked = AtomicBoolean(false)

    // During a push callback (registerNetworkCallback dispatch), the
    // writeToParcel hooks run under system_server's identity, so
    // Binder.getCallingUid() is 1000 — not the recipient app. hookConnectivity-
    // Service stashes the real recipient UID here so those hooks sanitize the
    // pushed data exactly like a synchronous call. See issue #70 (VTB and other
    // apps that detect VPN only via registerDefaultNetworkCallback).
    private val currentCallbackUid = ThreadLocal<Int>()
    private val bypassConnectivitySanitize = ThreadLocal<Boolean>()

    @Volatile private var connectivityServiceInstance: Any? = null

    private fun effectiveCallerUid(): Int {
        val uid = Binder.getCallingUid()
        return if (uid == SYSTEM_UID) currentCallbackUid.get() ?: uid else uid
    }

    private fun isTargetCallerOrUid(uid: Int? = null): Boolean {
        val targets = loadTargetUids()
        return targets.contains(effectiveCallerUid()) || (uid != null && targets.contains(uid))
    }

    private fun rememberConnectivityService(instance: Any?) {
        if (instance != null) connectivityServiceInstance = instance
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only hook system_server. handleLoadPackage fires multiple times
        // in system_server (once per hosted package / APEX), so we use
        // compareAndSet to install hooks exactly once.
        val inSystemServer =
            hookInstalled.get() ||
                lpparam.processName == "android" ||
                android.os.Process.myUid() == 1000

        if (!inSystemServer) return

        if (hookInstalled.compareAndSet(false, true)) {
            HookLog.install()
            HookLog.i("VpnHide: system_server detected, installing Binder hooks")
            val brokenFields = installSystemServerHooks()
            tryHook("PackageVisibility") { PackageVisibilityHooks.install(lpparam.classLoader) }
            tryHook("ConnectivityService") { installConnectivityServiceHook(lpparam.classLoader) }
            writeHookStatusFile(brokenFields)
        }
    }

    private inline fun tryHook(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            HookLog.e("VpnHide: $name hook failed: ${t::class.java.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun isVpnInterfaceName(name: String): Boolean = IfaceLists.isVpnIface(name)

    // Recursively sanitizes mIfaceName + mRoutes + nested mStackedLinks; the
    // length and nesting are inherent to walking that object graph by reflection.
    // Still private-field-based (unlike sanitizeNetworkCapabilities, which moved
    // to public mutators after Android 17 renamed NC's private fields). LP's
    // fields are stable so far; if a future Android renames mIfaceName/mRoutes/
    // mStackedLinks, migrate this to the public LinkProperties API
    // (setInterfaceName(null) / setLinkAddresses / setRoutes / setDnsServers)
    // the same way NC was done, and drop LP from the install-time smoke-check.
    @Suppress("LongMethod", "NestedBlockDepth")
    private fun sanitizeLinkProperties(copy: LinkProperties): Boolean {
        var modified = false

        val ifaceName = XposedHelpers.getObjectField(copy, "mIfaceName") as? String
        if (ifaceName != null && isVpnInterfaceName(ifaceName)) {
            XposedHelpers.setObjectField(copy, "mIfaceName", null)
            modified = true
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val routesField = XposedHelpers.getObjectField(copy, "mRoutes") as? MutableList<RouteInfo>
            if (routesField != null) {
                val filtered =
                    routesField.filterNot { route ->
                        val routeIface = route.`interface`
                        routeIface != null && isVpnInterfaceName(routeIface)
                    }
                if (filtered.size != routesField.size) {
                    routesField.clear()
                    routesField.addAll(filtered)
                    modified = true
                }
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to sanitize mRoutes: ${t.message}")
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val stacked = XposedHelpers.getObjectField(copy, "mStackedLinks") as? MutableMap<String, LinkProperties>
            if (stacked != null && stacked.isNotEmpty()) {
                val filtered = LinkedHashMap<String, LinkProperties>()
                for ((key, value) in stacked) {
                    val stackedCopy =
                        try {
                            val ctor = LinkProperties::class.java.getDeclaredConstructor(LinkProperties::class.java)
                            ctor.isAccessible = true
                            ctor.newInstance(value) as LinkProperties
                        } catch (_: Throwable) {
                            value
                        }
                    val stackedModified = sanitizeLinkProperties(stackedCopy)
                    val stackedIface = XposedHelpers.getObjectField(stackedCopy, "mIfaceName") as? String
                    if (stackedIface == null && stackedCopy.routes.isEmpty()) {
                        if (stackedModified || isVpnInterfaceName(key)) {
                            modified = true
                        } else {
                            filtered[key] = stackedCopy
                        }
                    } else {
                        // Only mark `modified` if sanitization actually
                        // changed something. The previous condition also
                        // tripped on `stackedCopy !== value`, which is
                        // true after every successful clone — so any
                        // non-empty stacked map forced a clear+putAll
                        // even when no VPN data was present.
                        if (stackedModified) modified = true
                        filtered[key] = stackedCopy
                    }
                }
                if (filtered.size != stacked.size || modified) {
                    stacked.clear()
                    stacked.putAll(filtered)
                }
            }
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to sanitize mStackedLinks: ${t.message}")
        }

        return modified
    }

    private fun sanitizeNetworkCapabilities(copy: NetworkCapabilities): Boolean {
        val hasVpnTransport = copy.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val hasVpnInfo = copy.transportInfo?.javaClass?.name == "android.net.VpnTransportInfo"

        if (!hasVpnTransport && !hasVpnInfo) return false

        if (hasVpnTransport) {
            XposedHelpers.callMethod(copy, "removeTransportType", NetworkCapabilities.TRANSPORT_VPN)
        }
        XposedHelpers.callMethod(copy, "addCapability", NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (hasVpnInfo) clearTransportInfo(copy)

        return true
    }

    private fun clearTransportInfo(copy: NetworkCapabilities) {
        val transportInfoClass = Class.forName("android.net.TransportInfo")
        XposedHelpers.callMethod(
            copy,
            "setTransportInfo",
            arrayOf(transportInfoClass),
            *arrayOfNulls<Any>(1),
        )
    }

    private inline fun <T> withConnectivitySanitizeBypassed(block: () -> T): T {
        val wasBypassed = bypassConnectivitySanitize.get() == true
        bypassConnectivitySanitize.set(true)
        return try {
            block()
        } finally {
            if (wasBypassed) {
                bypassConnectivitySanitize.set(true)
            } else {
                bypassConnectivitySanitize.remove()
            }
        }
    }

    private inline fun <T> withClearedCallingIdentity(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try {
            block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun rawNetworkCapabilities(
        cs: Any,
        network: Network,
    ): NetworkCapabilities? =
        withClearedCallingIdentity {
            withConnectivitySanitizeBypassed {
                callNetworkCapabilities(cs, network)
            }
        }

    private fun callNetworkCapabilities(
        cs: Any,
        network: Network,
    ): NetworkCapabilities? {
        val typedArgs = arrayOf<Any?>(network, "android", null)
        try {
            return XposedHelpers.callMethod(
                cs,
                "getNetworkCapabilities",
                arrayOf(Network::class.java, String::class.java, String::class.java),
                *typedArgs,
            ) as? NetworkCapabilities
        } catch (_: Throwable) {
        }
        return try {
            XposedHelpers.callMethod(cs, "getNetworkCapabilities", network) as? NetworkCapabilities
        } catch (_: Throwable) {
            null
        }
    }

    private fun rawAllNetworks(cs: Any): List<Network> =
        withClearedCallingIdentity {
            withConnectivitySanitizeBypassed {
                ((XposedHelpers.callMethod(cs, "getAllNetworks") as? Array<*>) ?: emptyArray<Any>())
                    .filterIsInstance<Network>()
            }
        }

    private fun isVpnNetwork(
        cs: Any,
        network: Network,
    ): Boolean = rawNetworkCapabilities(cs, network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun hasPhysicalTransport(caps: NetworkCapabilities): Boolean =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)

    private fun physicalNetworkScore(caps: NetworkCapabilities): Int {
        var score = 0
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score += 40
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 30
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 20
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) score += 10
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) score += 4
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 8
        return score
    }

    // Physical replacement follows the recommended split-tunnel model: target
    // apps see a non-VPN Network and may bind sockets to it. That is right for
    // apps kept outside the VPN, but it can bypass the VPN for apps that must
    // keep traffic inside the tunnel while hiding VPN state.
    // TODO: add a VPN-preserving concealment mode for that use case
    // (tracked by GitHub issue 130).
    private fun findPhysicalNetwork(cs: Any): Network? {
        val scored =
            rawAllNetworks(cs).mapNotNull { network ->
                val caps = rawNetworkCapabilities(cs, network) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !hasPhysicalTransport(caps)) {
                    null
                } else {
                    network to physicalNetworkScore(caps)
                }
            }
        return scored.maxByOrNull { it.second }?.first
    }

    private fun sanitizedNetworkCapabilities(nc: NetworkCapabilities): NetworkCapabilities {
        val copy = NetworkCapabilities(nc)
        return if (sanitizeNetworkCapabilities(copy)) copy else nc
    }

    private fun sanitizedLinkProperties(lp: LinkProperties): LinkProperties {
        val ctor = LinkProperties::class.java.getDeclaredConstructor(LinkProperties::class.java)
        ctor.isAccessible = true
        val copy = ctor.newInstance(lp) as LinkProperties
        return if (sanitizeLinkProperties(copy)) copy else lp
    }

    // NetworkInfo is legacy/deprecated and its fields have been stable, so this
    // still copies mNetworkType/mState/mDetailedState/mIsAvailable by reflection
    // (guarded by the install-time smoke-check). If a future Android renames
    // them — as Android 17 did for NetworkCapabilities — migrate to the public
    // NetworkInfo API (setDetailedState(...) sets state+detailedState; there's
    // no public type setter, so reconstruct via the public ctor like here) the
    // same way NC was moved to public mutators, and drop NI from the smoke-check.
    @Suppress("DEPRECATION")
    private fun sanitizedNetworkInfo(ni: NetworkInfo): NetworkInfo {
        val type = XposedHelpers.getIntField(ni, "mNetworkType")
        if (type != ConnectivityManager.TYPE_VPN) return ni

        val ctor =
            NetworkInfo::class.java.getDeclaredConstructor(
                Integer.TYPE,
                Integer.TYPE,
                String::class.java,
                String::class.java,
            )
        ctor.isAccessible = true
        val copy = ctor.newInstance(ConnectivityManager.TYPE_WIFI, 0, "WIFI", "") as NetworkInfo
        XposedHelpers.setObjectField(copy, "mState", XposedHelpers.getObjectField(ni, "mState"))
        XposedHelpers.setObjectField(copy, "mDetailedState", XposedHelpers.getObjectField(ni, "mDetailedState"))
        XposedHelpers.setBooleanField(copy, "mIsAvailable", XposedHelpers.getBooleanField(ni, "mIsAvailable"))
        return copy
    }

    private fun sanitizedValue(value: Any?): Any? =
        when (value) {
            is NetworkCapabilities -> sanitizedNetworkCapabilities(value)
            is LinkProperties -> sanitizedLinkProperties(value)
            is NetworkInfo -> sanitizedNetworkInfo(value)
            is Array<*> -> sanitizedArray(value)
            else -> value
        }

    private fun sanitizedArray(values: Array<*>): Any {
        val componentType = values.javaClass.componentType ?: return values
        if (
            componentType != NetworkCapabilities::class.java &&
            componentType != NetworkInfo::class.java
        ) {
            return values
        }

        val copy = JavaArray.newInstance(componentType, values.size)
        for (i in values.indices) {
            JavaArray.set(copy, i, sanitizedValue(values[i]))
        }
        return copy
    }

    private fun sanitizeMethodResult(
        param: XC_MethodHook.MethodHookParam,
        explicitUid: Int? = null,
    ) {
        if (bypassConnectivitySanitize.get() == true) return
        if (!isTargetCallerOrUid(explicitUid)) return
        try {
            param.result = sanitizedValue(param.result)
        } catch (t: Throwable) {
            HookLog.e("VpnHide: ConnectivityService result sanitize error: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun sanitizeCallbackBundle(bundle: Bundle) {
        try {
            val nc = bundle.getParcelable(NetworkCapabilities::class.java.simpleName) as? NetworkCapabilities
            if (nc != null) bundle.putParcelable(NetworkCapabilities::class.java.simpleName, sanitizedNetworkCapabilities(nc))
            val lp = bundle.getParcelable(LinkProperties::class.java.simpleName) as? LinkProperties
            if (lp != null) bundle.putParcelable(LinkProperties::class.java.simpleName, sanitizedLinkProperties(lp))
        } catch (t: Throwable) {
            HookLog.e("VpnHide: callback bundle sanitize error: ${t.message}")
        }
    }

    // ==================================================================
    //  system_server hooks — per-UID Binder filtering
    // ==================================================================

    @Volatile private var systemServerTargetUids: Set<Int>? = null

    @Volatile private var targetUidsFileObserver: android.os.FileObserver? = null
    private val uidLock = Any()

    private fun loadTargetUids(): Set<Int> {
        // Fast path: already cached (volatile read)
        systemServerTargetUids?.let { return it }

        // Slow path: only one thread reads the file
        synchronized(uidLock) {
            systemServerTargetUids?.let { return it }

            val uids = mutableSetOf<Int>()

            // Read pre-resolved numeric UIDs written by vpnhide-kmod's
            // service.sh into /data/system/vpnhide_uids.txt.
            // system_server can read /data/system/ (SELinux: system_data_file).
            try {
                val file = File("/data/system/vpnhide_uids.txt")
                if (file.exists()) {
                    file.readLines().forEach { line ->
                        line.trim().toIntOrNull()?.let { uids.add(it) }
                    }
                }
            } catch (t: Throwable) {
                HookLog.e("VpnHide: failed to read UIDs: ${t.message}")
            }

            val result: Set<Int> = uids.toSet()
            if (result.isNotEmpty()) {
                HookLog.i("VpnHide: system_server loaded ${result.size} target UIDs: $result")
            }
            // Always cache (even if empty) to avoid re-reading until invalidated
            systemServerTargetUids = result
            return result
        }
    }

    // Smoke-check at install time: every private AOSP field/ctor we touch
    // by reflection in the writeToParcel hooks. Returns the keys that
    // failed (missing or wrong-typed). Empty list = all good.
    //
    // Per-hook gates below skip installing a hook entirely when its
    // critical reflection broke — silent fail-open is preferable to
    // throwing NoSuchFieldError on every writeToParcel call (system_server
    // gets that on every NetworkCapabilities IPC, target or not). The
    // dashboard surfaces the broken_fields list as a red error so the
    // user can see and report the AOSP drift.
    private fun installSystemServerHooks(): List<String> {
        val brokenFields = runReflectionSmokeCheck()
        if (brokenFields.isNotEmpty()) {
            HookLog.e("VpnHide: reflection smoke-check found broken keys: $brokenFields")
        }

        // Match a probe key against either an exact entry in `broken` or
        // an entry with a `:type=...` suffix (wrong-typed field).
        fun anyBroken(critical: Set<String>): Boolean = brokenFields.any { it.substringBefore(':') in critical }

        // LP: mIfaceName + copy ctor are critical. mRoutes / mStackedLinks
        // are non-critical — the existing inner try/catch in
        // sanitizeLinkProperties already lets the rest of the sanitizer
        // proceed when those are absent.
        if (anyBroken(LP_CRITICAL_KEYS)) {
            HookLog.e("VpnHide: LP.writeToParcel hook SKIPPED — critical reflection broken")
        } else {
            tryHook("LP.writeToParcel") { hookLPWriteToParcel() }
        }

        // NC uses public NetworkCapabilities mutators now, so private AOSP
        // field drift must not disable this hook.
        tryHook("NC.writeToParcel") { hookNCWriteToParcel() }

        // NI: every field + ctor is critical — the hook body has no
        // inner try/catch around the per-field setIntField/setBooleanField
        // calls, so any rename would fail-open per call with logcat spam.
        if (anyBroken(NI_CRITICAL_KEYS)) {
            HookLog.e("VpnHide: NI.writeToParcel hook SKIPPED — critical reflection broken")
        } else {
            tryHook("NI.writeToParcel") { hookNIWriteToParcel() }
        }
        tryHook("Network.writeToParcel") { hookNetworkWriteToParcel() }

        tryHook("FileObserver") { watchTargetUidsFile() }
        return brokenFields
    }

    private data class FieldProbe(
        val key: String,
        val clazz: Class<*>,
        val name: String,
        // If the device's SDK is below this, the probe is skipped entirely
        // (not "found", not "broken" — not applicable). Used for fields
        // introduced after our minSdk floor (e.g. mTransportInfo at API 29).
        // Listed before `typeCheck` so the latter stays the last parameter
        // — that lets call sites use trailing-lambda syntax for the probe
        // without having to name `typeCheck =` every time.
        val minSdk: Int = 0,
        // Field-type compatibility predicate. For collections we use
        // isAssignableFrom() so AOSP swapping ArrayList → LinkedList stays OK.
        val typeCheck: (Class<*>) -> Boolean,
    )

    private data class CtorProbe(
        val key: String,
        val clazz: Class<*>,
        val params: Array<Class<*>>,
    )

    private fun runReflectionSmokeCheck(): List<String> {
        val broken = mutableListOf<String>()
        for (probe in FIELD_PROBES) {
            if (Build.VERSION.SDK_INT < probe.minSdk) continue
            val field =
                try {
                    XposedHelpers.findField(probe.clazz, probe.name)
                } catch (_: NoSuchFieldError) {
                    broken += probe.key
                    continue
                }
            if (!probe.typeCheck(field.type)) {
                // Suffix carries the actual type to help debug AOSP-drift
                // bug reports without rebuilding/instrumenting the device.
                broken += "${probe.key}:type=${field.type.name}"
            }
        }
        for (probe in CTOR_PROBES) {
            try {
                probe.clazz.getDeclaredConstructor(*probe.params)
            } catch (_: NoSuchMethodException) {
                broken += probe.key
            }
        }
        return broken
    }

    /**
     * Write a status file so the VPN Hide app can verify hooks are active.
     * Includes boot_id to distinguish stale files from previous boots,
     * aosp_sdk for diagnostic context in bug reports, and (only when
     * non-empty) broken_fields listing the reflection probes that the
     * smoke-check rejected this boot.
     */
    private fun writeHookStatusFile(brokenFields: List<String>) {
        try {
            val bootId = File("/proc/sys/kernel/random/boot_id").readText().trim()
            val timestamp = System.currentTimeMillis() / 1000
            val version = BuildConfig.VERSION_NAME
            val sdk = Build.VERSION.SDK_INT
            val sb = StringBuilder()
            sb.append("version=").append(version).append('\n')
            sb.append("boot_id=").append(bootId).append('\n')
            sb.append("timestamp=").append(timestamp).append('\n')
            sb.append("aosp_sdk=").append(sdk).append('\n')
            if (brokenFields.isNotEmpty()) {
                sb.append("broken_fields=").append(brokenFields.joinToString(",")).append('\n')
            }
            val statusFile = File(HOOK_STATUS_FILE)
            statusFile.writeText(sb.toString())
            // Don't expose this file to untrusted apps — anti-tamper SDKs
            // scan /data/system/ for known marker filenames. The VPN Hide
            // app reads it via root (`suExec("cat ...")`), see
            // DashboardData.kt — same pattern as vpnhide_uids.txt.
            HookLog.i(
                "VpnHide: wrote hook status file (version=$version, boot_id=$bootId, " +
                    "sdk=$sdk, broken=${brokenFields.size})",
            )
        } catch (t: Throwable) {
            HookLog.e("VpnHide: failed to write hook status: ${t.message}")
        }
    }

    /**
     * Watch /data/system/vpnhide_uids.txt for changes via inotify.
     * When modified (e.g. by the VPN Hide app), invalidate the
     * cached UID set so the next writeToParcel call re-reads it.
     */
    private fun watchTargetUidsFile() {
        val filename = "vpnhide_uids.txt"
        targetUidsFileObserver =
            watchSystemDataDir { path ->
                if (path == filename) {
                    HookLog.i("VpnHide: $filename changed, invalidating UID cache")
                    systemServerTargetUids = null
                }
            }
        HookLog.i("VpnHide: watching /data/system for $filename changes (inotify)")
    }

    /**
     * Hook NetworkCapabilities.writeToParcel in system_server.
     * For target UIDs, creates a copy with VPN stripped and writes
     * the copy to the Parcel instead of the original. The original
     * object is never mutated, avoiding race conditions with
     * ConnectivityService threads.
     */
    private fun hookNCWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            NetworkCapabilities::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (writingCopy.get() == true) return
                    val callerUid = effectiveCallerUid()
                    val targets = loadTargetUids()
                    val isTarget = targets.contains(callerUid)
                    val nc = param.thisObject as NetworkCapabilities
                    val hasVpn = nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    // Per-request diagnostic line. Gated by the debug-logging
                    // toggle: these fire on every NC.writeToParcel inside
                    // system_server and directly name the target UIDs we hook,
                    // which is exactly what users hiding their setup want
                    // kept out of logcat.
                    HookLog.i(
                        "VpnHide-NC: uid=$callerUid target=$isTarget hasVpn=$hasVpn",
                    )
                    if (!isTarget) return

                    try {
                        val copy = NetworkCapabilities(nc)
                        if (!sanitizeNetworkCapabilities(copy)) return

                        val parcel = param.args[0] as android.os.Parcel
                        val flags = param.args[1] as Int
                        writingCopy.set(true)
                        try {
                            copy.writeToParcel(parcel, flags)
                        } finally {
                            writingCopy.set(false)
                        }
                        param.result = null
                        HookLog.i("VpnHide-NC: uid=$callerUid STRIPPED VPN")
                    } catch (t: Throwable) {
                        HookLog.e("VpnHide: NC.writeToParcel error: ${t.message}")
                    }
                }
            },
        )
        HookLog.i("VpnHide: hooked NetworkCapabilities.writeToParcel")
    }

    private fun hookNetworkWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            Network::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (writingCopy.get() == true || !isTargetCallerOrUid()) return
                    val cs = connectivityServiceInstance ?: return
                    val network = param.thisObject as Network
                    try {
                        if (!isVpnNetwork(cs, network)) return
                        val replacement = findPhysicalNetwork(cs) ?: return
                        val parcel = param.args[0] as android.os.Parcel
                        val flags = param.args[1] as Int
                        writingCopy.set(true)
                        try {
                            replacement.writeToParcel(parcel, flags)
                        } finally {
                            writingCopy.set(false)
                        }
                        param.result = null
                        HookLog.i("VpnHide: replaced VPN Network parcel for uid=${effectiveCallerUid()}")
                    } catch (t: Throwable) {
                        HookLog.e("VpnHide: Network.writeToParcel error: ${t.message}")
                    }
                }
            },
        )
        HookLog.i("VpnHide: hooked Network.writeToParcel")
    }

    /**
     * Hook NetworkInfo.writeToParcel — disguise VPN NetworkInfo for target callers.
     * Creates a copy with type changed from VPN to WIFI, writes the copy.
     */
    @Suppress("DEPRECATION")
    private fun hookNIWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            NetworkInfo::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (writingCopy.get() == true) return
                    val callerUid = effectiveCallerUid()
                    val isTarget = loadTargetUids().contains(callerUid)
                    val ni = param.thisObject as NetworkInfo
                    val type = XposedHelpers.getIntField(ni, "mNetworkType")
                    val isVpn = type == ConnectivityManager.TYPE_VPN
                    HookLog.i(
                        "VpnHide-NI: uid=$callerUid target=$isTarget isVpn=$isVpn type=$type",
                    )
                    if (!isTarget) return
                    try {
                        if (!isVpn) return
                        val copy = sanitizedNetworkInfo(ni)

                        val parcel = param.args[0] as android.os.Parcel
                        val flags = param.args[1] as Int
                        writingCopy.set(true)
                        try {
                            copy.writeToParcel(parcel, flags)
                        } finally {
                            writingCopy.set(false)
                        }
                        param.result = null
                        HookLog.i("VpnHide-NI: uid=$callerUid STRIPPED VPN (disguised as WIFI)")
                    } catch (t: Throwable) {
                        HookLog.e("VpnHide: NI.writeToParcel error: ${t.message}")
                    }
                }
            },
        )
        HookLog.i("VpnHide: hooked NetworkInfo.writeToParcel")
    }

    /**
     * Hook LinkProperties.writeToParcel — clear VPN interface name and
     * routes for target callers. Creates a copy to avoid mutating the
     * original object shared by ConnectivityService threads.
     */
    private fun hookLPWriteToParcel() {
        val writingCopy = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod(
            LinkProperties::class.java,
            "writeToParcel",
            android.os.Parcel::class.java,
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (writingCopy.get() == true) return
                    val callerUid = effectiveCallerUid()
                    val isTarget = loadTargetUids().contains(callerUid)
                    val lp = param.thisObject as LinkProperties
                    val ifname = XposedHelpers.getObjectField(lp, "mIfaceName") as? String
                    HookLog.i("VpnHide-LP: uid=$callerUid target=$isTarget ifname=$ifname")
                    if (!isTarget) return
                    try {
                        val ctor = LinkProperties::class.java.getDeclaredConstructor(LinkProperties::class.java)
                        ctor.isAccessible = true
                        val copy = ctor.newInstance(lp) as LinkProperties
                        if (!sanitizeLinkProperties(copy)) return

                        val parcel = param.args[0] as android.os.Parcel
                        val flags = param.args[1] as Int
                        writingCopy.set(true)
                        try {
                            copy.writeToParcel(parcel, flags)
                        } finally {
                            writingCopy.set(false)
                        }
                        param.result = null
                        HookLog.i("VpnHide-LP: uid=$callerUid STRIPPED VPN (ifname was $ifname)")
                    } catch (t: Throwable) {
                        HookLog.e("VpnHide: LP.writeToParcel error: ${t.message}")
                    }
                }
            },
        )
        HookLog.i("VpnHide: hooked LinkProperties.writeToParcel")
    }

    /**
     * Install the ConnectivityService callback hook once the service is up.
     *
     * On Android 13+ ConnectivityService ships in the Connectivity APEX and is
     * loaded by a classloader the system_server boot classloader can't resolve —
     * findClass(...) on [bootClassLoader] throws ClassNotFound, so the hook never
     * installs and push callbacks leak (issue #70). The reliable classloader is
     * the one that loaded the registered "connectivity" binder, so we take it
     * from there. The binder isn't registered yet when hooks install at early
     * boot, so we catch ServiceManager.addService("connectivity", ...) — and also
     * try a direct lookup first to cover a late module load. Works on both the
     * APEX (A13+) and in-boot-classpath (A12-) layouts.
     */
    private fun installConnectivityServiceHook(bootClassLoader: ClassLoader) {
        hookConnectivityServiceIfPossible(bootClassLoader)

        val serviceManager = XposedHelpers.findClass("android.os.ServiceManager", bootClassLoader)
        (XposedHelpers.callStaticMethod(serviceManager, "getService", "connectivity") as? android.os.IBinder)
            ?.let { hookConnectivityFromBinder(it) }
        XposedBridge.hookAllMethods(
            serviceManager,
            "addService",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.getOrNull(0) != "connectivity") return
                    (param.args.getOrNull(1) as? android.os.IBinder)?.let { hookConnectivityFromBinder(it) }
                }
            },
        )
    }

    private fun hookConnectivityFromBinder(binder: android.os.IBinder) {
        val classLoader =
            binder.javaClass.classLoader ?: run {
                HookLog.i("VpnHide: connectivity binder has no classloader; waiting for direct classloader")
                return
            }
        hookConnectivityServiceIfPossible(classLoader)
    }

    private fun hookConnectivityServiceIfPossible(classLoader: ClassLoader) {
        if (!connectivityHooked.compareAndSet(false, true)) return
        try {
            hookConnectivityService(classLoader)
        } catch (t: Throwable) {
            connectivityHooked.set(false)
            HookLog.i("VpnHide: ConnectivityService class not ready on ${classLoader.javaClass.name}: ${t.message}")
        }
    }

    private fun findConnectivityServiceClass(classLoader: ClassLoader): Class<*> =
        try {
            // Android 14+ ships ConnectivityService in the repackaged
            // Connectivity APEX namespace; older releases use the original.
            XposedHelpers.findClass(
                "android.net.connectivity.com.android.server.ConnectivityService",
                classLoader,
            )
        } catch (_: Throwable) {
            XposedHelpers.findClass("com.android.server.ConnectivityService", classLoader)
        }

    /**
     * Hook the two ConnectivityService dispatch points that *push* network state
     * to apps: callCallbackForRequest (registerNetworkCallback with a callback
     * object) and sendPendingIntentForRequest (registerNetworkCallback with a
     * PendingIntent). On both, the writeToParcel hooks would see
     * getCallingUid()==1000 instead of the recipient app and skip sanitizing, so
     * we stash the recipient UID in currentCallbackUid for the dispatch's
     * duration. If the app explicitly requested a VPN network, drop the dispatch
     * entirely — don't reveal a VPN exists. Fixes apps (e.g. VTB, issue #70) that
     * detect VPN only via callbacks.
     */
    private fun hookConnectivityService(classLoader: ClassLoader) {
        val csClass = findConnectivityServiceClass(classLoader)
        installConnectivityServiceResultHooks(csClass)
        installConnectivityServiceNetworkHooks(csClass)

        // Both methods take the NetworkRequestInfo as their first arg, so the
        // same handler covers the callback-object and PendingIntent paths.
        val dispatchHook =
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val nri = param.args.firstOrNull() ?: return
                    rememberConnectivityService(param.thisObject)
                    val uid = extractRecipientUid(nri)
                    if (uid < 0 || !loadTargetUids().contains(uid)) return

                    val request = extractNetworkRequest(nri)
                    if (request != null && request.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        // App is specifically listening for a VPN network —
                        // suppress so it never learns one exists.
                        param.result = null
                        HookLog.i("VpnHide-CB: uid=$uid suppressed VPN-request dispatch")
                        return
                    }
                    (param.args.getOrNull(CALLBACK_BUNDLE_ARG_INDEX) as? Bundle)?.let { sanitizeCallbackBundle(it) }
                    currentCallbackUid.set(uid)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    currentCallbackUid.remove()
                }
            }

        for (method in CALLBACK_DISPATCH_METHODS) {
            val hooked = XposedBridge.hookAllMethods(csClass, method, dispatchHook)
            if (hooked.isEmpty()) {
                HookLog.e("VpnHide: no $method on ${csClass.name}")
            } else {
                HookLog.i("VpnHide: hooked ConnectivityService.$method (${hooked.size})")
            }
        }
    }

    private fun installConnectivityServiceResultHooks(csClass: Class<*>) {
        for ((method, uidArgIndex) in CONNECTIVITY_RESULT_METHODS) {
            val hooked =
                XposedBridge.hookAllMethods(
                    csClass,
                    method,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            rememberConnectivityService(param.thisObject)
                            val explicitUid = uidArgIndex?.let { param.args.getOrNull(it) as? Int }
                            sanitizeMethodResult(param, explicitUid)
                        }
                    },
                )
            if (hooked.isEmpty()) {
                HookLog.e("VpnHide: no ConnectivityService.$method result hook target on ${csClass.name}")
            } else {
                HookLog.i("VpnHide: hooked ConnectivityService.$method result (${hooked.size})")
            }
        }
    }

    private fun installConnectivityServiceNetworkHooks(csClass: Class<*>) {
        hookConnectivityNetworkMethod(csClass, "getActiveNetwork", ::sanitizeActiveNetworkResult)
        hookConnectivityNetworkMethod(csClass, "getAllNetworks", ::sanitizeAllNetworksResult)
        hookConnectivityNetworkMethod(csClass, "getNetworkForType", ::sanitizeNetworkForTypeResult)
    }

    private fun hookConnectivityNetworkMethod(
        csClass: Class<*>,
        method: String,
        sanitizer: (XC_MethodHook.MethodHookParam) -> Unit,
    ) {
        val hooked =
            XposedBridge.hookAllMethods(
                csClass,
                method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        rememberConnectivityService(param.thisObject)
                        if (bypassConnectivitySanitize.get() == true) return
                        if (!isTargetCallerOrUid()) return
                        sanitizer(param)
                    }
                },
            )
        if (hooked.isEmpty()) {
            HookLog.e("VpnHide: no ConnectivityService.$method network hook target on ${csClass.name}")
        } else {
            HookLog.i("VpnHide: hooked ConnectivityService.$method network result (${hooked.size})")
        }
    }

    private fun sanitizeActiveNetworkResult(param: XC_MethodHook.MethodHookParam) {
        val network = param.result as? Network ?: return
        val cs = param.thisObject ?: return
        if (!isVpnNetwork(cs, network)) return
        param.result = findPhysicalNetwork(cs)
        HookLog.i("VpnHide: replaced active VPN Network handle for uid=${effectiveCallerUid()}")
    }

    private fun sanitizeAllNetworksResult(param: XC_MethodHook.MethodHookParam) {
        val networks = (param.result as? Array<*>)?.filterIsInstance<Network>() ?: return
        val cs = param.thisObject ?: return
        val filtered = networks.filterNot { isVpnNetwork(cs, it) }
        if (filtered.size == networks.size) return
        param.result = filtered.toTypedArray()
        HookLog.i(
            "VpnHide: filtered ${networks.size - filtered.size} VPN Network handle(s) " +
                "for uid=${effectiveCallerUid()}",
        )
    }

    private fun sanitizeNetworkForTypeResult(param: XC_MethodHook.MethodHookParam) {
        val type = param.args.getOrNull(0) as? Int ?: return
        if (type != ConnectivityManager.TYPE_VPN || param.result == null) return
        param.result = null
        HookLog.i("VpnHide: suppressed getNetworkForType(TYPE_VPN) for uid=${effectiveCallerUid()}")
    }

    // The callback recipient UID lives on the NetworkRequestInfo arg under
    // different field names across AOSP versions (mAsUid is the UID the callback
    // is delivered as). Returns -1 if none found.
    private fun extractRecipientUid(nri: Any): Int {
        for (field in RECIPIENT_UID_FIELDS) {
            try {
                return XposedHelpers.getIntField(nri, field)
            } catch (_: Throwable) {
            }
        }
        return -1
    }

    // Find the NetworkRequest on the NRI by type — field name varies, and some
    // versions hold a list of requests (take the first). Flattened to a field
    // sequence over the class hierarchy to keep nesting shallow.
    private fun extractNetworkRequest(nri: Any): android.net.NetworkRequest? =
        generateSequence(nri.javaClass as Class<*>?) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .firstNotNullOfOrNull { field ->
                asNetworkRequest(
                    runCatching {
                        field.isAccessible = true
                        field.get(nri)
                    }.getOrNull(),
                )
            }

    private fun asNetworkRequest(value: Any?): android.net.NetworkRequest? =
        when (value) {
            is android.net.NetworkRequest -> value
            is List<*> -> value.firstOrNull { it is android.net.NetworkRequest } as? android.net.NetworkRequest
            else -> null
        }

    companion object {
        private const val SYSTEM_UID = 1000
        private const val CALLBACK_BUNDLE_ARG_INDEX = 2
        private val RECIPIENT_UID_FIELDS = listOf("mAsUid", "mUid", "uid")
        private val CALLBACK_DISPATCH_METHODS = listOf("callCallbackForRequest", "sendPendingIntentForRequest")
        private val CONNECTIVITY_RESULT_METHODS =
            listOf(
                "getActiveLinkProperties" to null,
                "getLinkProperties" to null,
                "getLinkPropertiesForType" to null,
                "getRedactedLinkPropertiesForPackage" to 1,
                "getNetworkCapabilities" to null,
                "getDefaultNetworkCapabilitiesForUser" to null,
                "getRedactedNetworkCapabilitiesForPackage" to 1,
                "getActiveNetworkInfo" to null,
                "getActiveNetworkInfoForUid" to 0,
                "getNetworkInfo" to null,
                "getNetworkInfoForUid" to 1,
                "getAllNetworkInfo" to null,
            )
        const val HOOK_STATUS_FILE = "/data/system/vpnhide_hook_active"

        private val FIELD_PROBES =
            listOf(
                FieldProbe(
                    "LinkProperties.mIfaceName",
                    LinkProperties::class.java,
                    "mIfaceName",
                ) { it == String::class.java },
                FieldProbe(
                    "LinkProperties.mRoutes",
                    LinkProperties::class.java,
                    "mRoutes",
                ) { MutableList::class.java.isAssignableFrom(it) },
                FieldProbe(
                    "LinkProperties.mStackedLinks",
                    LinkProperties::class.java,
                    "mStackedLinks",
                ) { MutableMap::class.java.isAssignableFrom(it) },
                FieldProbe(
                    "NetworkInfo.mNetworkType",
                    NetworkInfo::class.java,
                    "mNetworkType",
                ) { it == Integer.TYPE },
                FieldProbe(
                    "NetworkInfo.mState",
                    NetworkInfo::class.java,
                    "mState",
                ) { it == NetworkInfo.State::class.java },
                FieldProbe(
                    "NetworkInfo.mDetailedState",
                    NetworkInfo::class.java,
                    "mDetailedState",
                ) { it == NetworkInfo.DetailedState::class.java },
                FieldProbe(
                    "NetworkInfo.mIsAvailable",
                    NetworkInfo::class.java,
                    "mIsAvailable",
                ) { it == java.lang.Boolean.TYPE },
            )

        private val CTOR_PROBES =
            listOf(
                CtorProbe(
                    "LinkProperties.<init>(LinkProperties)",
                    LinkProperties::class.java,
                    arrayOf(LinkProperties::class.java),
                ),
                CtorProbe(
                    "NetworkInfo.<init>(int,int,String,String)",
                    NetworkInfo::class.java,
                    arrayOf(Integer.TYPE, Integer.TYPE, String::class.java, String::class.java),
                ),
            )

        // Per-hook critical-probe sets. A hook is skipped if any key in
        // its set is in the broken list. mRoutes / mStackedLinks are
        // intentionally NOT critical — graceful
        // degradation lives in the existing inner try/catch blocks.
        private val LP_CRITICAL_KEYS =
            setOf(
                "LinkProperties.mIfaceName",
                "LinkProperties.<init>(LinkProperties)",
            )
        private val NI_CRITICAL_KEYS =
            setOf(
                "NetworkInfo.mNetworkType",
                "NetworkInfo.mState",
                "NetworkInfo.mDetailedState",
                "NetworkInfo.mIsAvailable",
                "NetworkInfo.<init>(int,int,String,String)",
            )
    }
}
