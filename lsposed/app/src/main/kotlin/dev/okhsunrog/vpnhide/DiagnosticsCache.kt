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
 * Cache for `runAllChecks` results.
 *
 * Diagnostics answer one question: *do the hooks work for this app
 * process right now?* The hooks themselves are fixed at process
 * creation time — kmod loads at boot, LSPosed injects into
 * system_server at its boot, Zygisk hooks fire at zygote fork —
 * so a run's result is valid for the entire lifetime of this app
 * process. Re-running every tab switch is pure waste.
 *
 * State machine:
 * - [State.NotRun] — fresh, nothing attempted yet.
 * - [State.Running] — a run is in flight.
 * - [State.VpnOff] — last run aborted because no active VPN was
 *   detected. User gets a "turn on VPN, then retry" banner.
 * - [State.Ready] — results captured; exposed to both the Dashboard
 *   protection panel and the Diagnostics screen.
 *
 * Once [State.Ready] is reached, [run] becomes a no-op — results don't
 * change mid-process. The only path back to "please retry" is killing the
 * process (a new launch starts with a fresh cache).
 */
internal object DiagnosticsCache {
    sealed interface State {
        data object NotRun : State

        data object Running : State

        data object VpnOff : State

        data class Ready(
            val results: CheckResults,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.NotRun)
    val state: StateFlow<State> = _state.asStateFlow()

    private var inflight: Job? = null

    /** Start a run if one isn't already in flight and we don't have a
     * completed result yet. Idempotent — safe to call from both
     * Dashboard and Diagnostics screens on every composition.
     */
    fun run(
        scope: CoroutineScope,
        context: Context,
    ) {
        when (_state.value) {
            is State.Ready -> {
                return
            }

            State.Running -> {
                return
            }

            State.NotRun, State.VpnOff -> { /* proceed */ }
        }
        if (inflight?.isActive == true) return
        inflight = scope.launch { doRun(context.applicationContext) }
    }

    /** Used by the retry button in the "VPN off" banner — a readable alias
     * for [run] at the call site (the [run] guard already permits a re-run
     * from both NotRun and VpnOff).
     */
    fun retry(
        scope: CoroutineScope,
        context: Context,
    ) = run(scope, context)

    private suspend fun doRun(appContext: Context) {
        _state.value = State.Running
        try {
            StartupTrace.mark("diagnostics_cache_start")
            val vpnActive = withContext(Dispatchers.IO) { isVpnActive() }
            if (!vpnActive) {
                _state.value = State.VpnOff
                StartupTrace.mark("diagnostics_cache_vpn_off")
                return
            }
            val results =
                withContext(Dispatchers.IO) {
                    val cm = appContext.getSystemService(ConnectivityManager::class.java)
                    runAllChecks(cm, appContext)
                }
            _state.value = State.Ready(results)
            StartupTrace.mark("diagnostics_cache_done")
        } catch (e: CancellationException) {
            // A cancelled job (e.g. the screen left) must propagate so
            // structured concurrency unwinds — never get reinterpreted as a
            // VpnOff result.
            throw e
        } catch (e: Exception) {
            // Failures leave us in VpnOff so the user sees the retry UI
            // rather than a frozen spinner. Real-world causes here are
            // transient (root dropped, shell exec failure) and a retry
            // usually works.
            _state.value = State.VpnOff
            StartupTrace.mark("diagnostics_cache_failed")
            VpnHideLog.w("VpnHide-Diag", "runAllChecks failed: ${e.message}")
        }
    }
}
