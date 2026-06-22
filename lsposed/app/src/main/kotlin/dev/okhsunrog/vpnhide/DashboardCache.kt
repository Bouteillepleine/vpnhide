package dev.okhsunrog.vpnhide

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-scoped cache for the Dashboard's computed state. Previously
 * `DashboardScreen` ran `loadDashboardState()` in its own
 * `LaunchedEffect(Unit)` on every composition — which means every
 * tab switch re-ran all the module-prop / target / kprobes / SELinux
 * checks via `suExec`. Cache them once at startup; refresh them
 * explicitly on user action or after a Save.
 *
 * The Dashboard screen reads [state] and shows the previous value
 * while a refresh is in flight so tab switches feel instant even when
 * data changes underneath.
 */
internal object DashboardCache {
    private val _state = MutableStateFlow<DashboardState?>(null)
    val state: StateFlow<DashboardState?> = _state.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var inflight: Job? = null

    fun ensureLoaded(
        scope: CoroutineScope,
        context: Context,
        selfNeedsRestart: Boolean,
    ) {
        if (_state.value != null || _error.value != null || inflight?.isActive == true) return
        inflight = scope.launch { reload(context, selfNeedsRestart) }
    }

    fun refresh(
        scope: CoroutineScope,
        context: Context,
        selfNeedsRestart: Boolean,
    ) {
        inflight?.cancel()
        RootSnapshotCache.invalidate()
        _error.value = null
        inflight = scope.launch { reload(context, selfNeedsRestart, forceRootRefresh = true) }
    }

    /** Invalidate so the next `ensureLoaded` call reloads. Used after
     * a Save on a Protection screen changes target-file contents so
     * that the next Dashboard open reflects the fresh counts without
     * the user having to tap Refresh.
     */
    fun invalidate() {
        _state.value = null
        _error.value = null
    }

    private suspend fun reload(
        context: Context,
        selfNeedsRestart: Boolean,
        forceRootRefresh: Boolean = false,
    ) {
        _loading.value = true
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val rootSnapshot =
                if (forceRootRefresh) {
                    RootSnapshotCache.refresh()
                } else {
                    RootSnapshotCache.getOrLoad()
                }
            val next =
                withContext(Dispatchers.IO) {
                    loadDashboardState(cm, context.applicationContext, selfNeedsRestart, rootSnapshot)
                }
            StartupTrace.mark("dashboard_state_loaded")
            _state.value = next
            _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StartupTrace.mark("dashboard_state_failed")
            _error.value = e.message ?: e.javaClass.simpleName
            VpnHideLog.w("VpnHide-Dashboard", "dashboard reload failed: ${e.message}", e)
        } finally {
            _loading.value = false
        }
    }
}
