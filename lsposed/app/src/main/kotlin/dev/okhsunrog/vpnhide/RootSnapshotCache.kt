package dev.okhsunrog.vpnhide

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class RootSnapshot(
    val sections: Map<String, String>,
)

internal data class PackageInventorySeed(
    val packages: String,
    val users: String,
)

internal class RootSnapshotException(
    message: String,
) : RuntimeException(message)

private const val ROOT_SNAPSHOT_BEGIN_PREFIX = "__VPNHIDE_ROOT_SECTION_BEGIN__:"
private const val ROOT_SNAPSHOT_END_PREFIX = "__VPNHIDE_ROOT_SECTION_END__:"
private const val ROOT_TIMING_PREFIX = "__VPNHIDE_ROOT_TIMING__:"
private const val ROOT_SNAPSHOT_TIMEOUT_SEC: Long = 10

internal val REQUIRED_ROOT_SNAPSHOT_SECTIONS =
    setOf(
        "kmod_prop",
        "zygisk_prop",
        "kpm_prop",
        "ports_prop",
        "kmod_module_dir",
        "zygisk_module_dir",
        "kpm_module_dir",
        "kmod_activator_state",
        "kpm_activator_state",
        "zygisk_activator_state",
        "ports_activator_state",
        "kmod_disabled",
        "kpm_disabled",
        "zygisk_disabled",
        "ports_disabled",
        "canonical_config",
        "kpm_load_status",
        "ports_load_status",
        "superkey_saved",
        "current_boot_id",
        "kmod_load_status",
        "kmod_load_dmesg",
        "zygisk_status",
        "kernel_release",
        "kmod_state",
        "kpm_state",
        "kpm_runtime_modules",
        "lsposed_state",
        "getenforce",
        "kpatch_runtime",
        "pm_packages",
        "pm_users",
        "snapshot_shell_uid",
        "proc_exists",
        "ports_chain",
        "lsposed_framework",
        "vpn_ifaces",
    )

/**
 * Single in-process source for root-owned/system state. Dashboard and
 * Protection derive different UI models from the same cached snapshot, so
 * their counts/statuses cannot drift because two independent shell snapshots
 * raced.
 */
internal object RootSnapshotCache {
    private val _snapshot = MutableStateFlow<RootSnapshot?>(null)
    val snapshot: StateFlow<RootSnapshot?> = _snapshot.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val mutex = Mutex()
    private var preloadedPackageInventory: PackageInventorySeed? = null
    private var runtimeProbeSource: String? = null

    fun setRuntimeProbeSource(path: String?) {
        runtimeProbeSource = path?.takeIf { it.matches(Regex("/[A-Za-z0-9_./-]+")) }
    }

    suspend fun getOrLoad(): RootSnapshot =
        withContext(Dispatchers.IO) {
            _snapshot.value?.let { return@withContext it }
            mutex.withLock {
                _snapshot.value ?: loadLocked()
            }
        }

    suspend fun refresh(): RootSnapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                preloadedPackageInventory = null
                loadLocked()
            }
        }

    fun invalidate() {
        _snapshot.value = null
        preloadedPackageInventory = null
    }

    fun seedPackageInventory(seed: PackageInventorySeed?) {
        preloadedPackageInventory = seed
    }

    private fun loadLocked(): RootSnapshot {
        _loading.value = true
        return try {
            StartupTrace.mark("root_snapshot_start")
            val inventory = preloadedPackageInventory
            preloadedPackageInventory = null
            val sections = loadRootShellSnapshot(inventoryOverride = inventory, runtimeProbeSource = runtimeProbeSource)
            val snapshot = RootSnapshot(sections)
            _snapshot.value = snapshot
            StartupTrace.mark("root_snapshot_done")
            snapshot
        } catch (e: Exception) {
            StartupTrace.mark("root_snapshot_failed")
            throw e
        } finally {
            _loading.value = false
        }
    }
}

private fun loadRootShellSnapshot(
    inventoryOverride: PackageInventorySeed?,
    runtimeProbeSource: String?,
): Map<String, String> {
    val (exitCode, raw) =
        suExec(
            buildRootShellSnapshotCommand(
                includePmPackages = inventoryOverride == null,
                runtimeProbeSource = runtimeProbeSource,
            ),
            timeoutSec = ROOT_SNAPSHOT_TIMEOUT_SEC,
        )
    if (exitCode != 0) {
        throw RootSnapshotException("root snapshot command failed with exit=$exitCode")
    }
    val sections = parseRootShellSnapshot(raw).toMutableMap()
    if (inventoryOverride != null) {
        sections["pm_packages"] = inventoryOverride.packages
        sections["pm_users"] = inventoryOverride.users
    }
    validateRootSnapshotSections(sections)
    return sections
}

internal fun validateRootSnapshotSections(sections: Map<String, String>) {
    val missing = REQUIRED_ROOT_SNAPSHOT_SECTIONS.filterNot(sections::containsKey)
    if (missing.isNotEmpty()) {
        throw RootSnapshotException("root snapshot incomplete, missing sections: ${missing.joinToString()}")
    }
}

// Long because it's a single embedded shell script (the batched root probe),
// not Kotlin control flow.
@Suppress("LongMethod")
internal fun buildRootShellSnapshotCommand(
    includePmPackages: Boolean = true,
    runtimeProbeSource: String? = null,
): String =
    """
    KPM_RUNTIME_PROBE_SOURCE=${runtimeProbeSource.orEmpty()}
    emit_cmd() {
      NAME="${'$'}1"
      shift
      echo "$ROOT_SNAPSHOT_BEGIN_PREFIX${'$'}NAME"
      "$@" 2>/dev/null || true
      echo "$ROOT_SNAPSHOT_END_PREFIX${'$'}NAME"
    }
    emit_file() {
      NAME="${'$'}1"
      PATH_TO_READ="${'$'}2"
      echo "$ROOT_SNAPSHOT_BEGIN_PREFIX${'$'}NAME"
      if [ -f "${'$'}PATH_TO_READ" ]; then
        cat "${'$'}PATH_TO_READ" 2>/dev/null || true
      fi
      echo "$ROOT_SNAPSHOT_END_PREFIX${'$'}NAME"
    }
    emit_eval() {
      NAME="${'$'}1"
      shift
      echo "$ROOT_SNAPSHOT_BEGIN_PREFIX${'$'}NAME"
      eval "${'$'}*" 2>/dev/null || true
      echo "$ROOT_SNAPSHOT_END_PREFIX${'$'}NAME"
    }
    activator_state() {
      if [ -x "${'$'}1" ]; then
        echo executable
      elif [ -e "${'$'}1" ]; then
        echo not_executable
      else
        echo missing
      fi
    }
    # 1 when a complete module is staged in modules_update/ awaiting the next
    # reboot: the root manager keeps the freshly-installed files (activator,
    # scripts, payload) there and only swaps them into modules/ on boot, so the
    # active dir legitimately has no activator yet. Distinguishes "just
    # installed, reboot needed" from a genuinely corrupt install.
    pending_update() {
      STAGED=/data/adb/modules_update/${'$'}{1##*/}
      if [ -x "${'$'}STAGED/activator" ]; then
        echo 1
      elif [ -f "${'$'}1/update" ] && [ -d "${'$'}STAGED" ]; then
        echo 1
      else
        echo 0
      fi
    }
    now_ms() {
      if [ -n "${'$'}{EPOCHREALTIME:-}" ]; then
        SEC="${'$'}{EPOCHREALTIME%.*}"
        FRAC="${'$'}{EPOCHREALTIME#*.}"
      else
        IFS=' .' read -r SEC FRAC _ < /proc/uptime
      fi
      FRAC="${'$'}{FRAC}000"
      FRAC="${'$'}{FRAC%${'$'}{FRAC#???}}"
      while [ -n "${'$'}FRAC" ] && [ "${'$'}{FRAC#0}" != "${'$'}FRAC" ]; do
        FRAC="${'$'}{FRAC#0}"
      done
      [ -n "${'$'}FRAC" ] || FRAC=0
      case "${'$'}SEC${'$'}FRAC" in
        ''|*[!0-9]*) echo 0 ;;
        *) echo ${'$'}((SEC * 1000 + FRAC)) ;;
      esac
    }
    phase_start() {
      PHASE_NAME="${'$'}1"
      PHASE_START="${'$'}(now_ms)"
    }
    phase_end() {
      END="${'$'}(now_ms)"
      echo "$ROOT_TIMING_PREFIX${'$'}PHASE_NAME=${'$'}((END - PHASE_START))"
    }
    phase_module_props() {
      phase_start module_props
      emit_file kmod_prop $KMOD_MODULE_DIR/module.prop
      emit_file zygisk_prop $ZYGISK_MODULE_DIR/module.prop
      emit_file kpm_prop $KPM_MODULE_DIR/module.prop
      emit_file ports_prop $PORTS_MODULE_DIR/module.prop
      emit_eval kmod_module_dir '[ -d $KMOD_MODULE_DIR ] && echo 1 || echo 0'
      emit_eval zygisk_module_dir '[ -d $ZYGISK_MODULE_DIR ] && echo 1 || echo 0'
      emit_eval kpm_module_dir '[ -d $KPM_MODULE_DIR ] && echo 1 || echo 0'
      emit_eval kmod_activator_state 'activator_state $KMOD_ACTIVATOR'
      emit_eval kpm_activator_state 'activator_state $KPM_ACTIVATOR'
      emit_eval zygisk_activator_state 'activator_state $ZYGISK_ACTIVATOR'
      emit_eval ports_activator_state 'activator_state $PORTS_ACTIVATOR'
      emit_eval kmod_disabled '[ -f $KMOD_MODULE_DIR/disable ] && echo 1 || echo 0'
      emit_eval kpm_disabled '[ -f $KPM_MODULE_DIR/disable ] && echo 1 || echo 0'
      emit_eval zygisk_disabled '[ -f $ZYGISK_MODULE_DIR/disable ] && echo 1 || echo 0'
      emit_eval ports_disabled '[ -f $PORTS_MODULE_DIR/disable ] && echo 1 || echo 0'
      emit_eval kmod_pending_update 'pending_update $KMOD_MODULE_DIR'
      emit_eval kpm_pending_update 'pending_update $KPM_MODULE_DIR'
      emit_eval zygisk_pending_update 'pending_update $ZYGISK_MODULE_DIR'
      emit_eval ports_pending_update 'pending_update $PORTS_MODULE_DIR'
      phase_end
    }
    phase_target_files() {
      phase_start target_files
      emit_file canonical_config $CANONICAL_CONFIG_FILE
      emit_eval superkey_saved '[ -s $SUPERKEY_FILE ] && echo 1 || echo 0'
      phase_end
    }
    # Pre-1.0 per-component lists. Nothing writes them today, so a non-empty
    # section means an install that skipped the 1.0.x migration window and can
    # still have its choices recovered (LegacyConfigImport).
    phase_legacy_config() {
      phase_start legacy_config
      ${
        LEGACY_CONFIG_SECTIONS.entries.joinToString("\n      ") { (section, path) ->
            "emit_file $section $path"
        }
    }
      phase_end
    }
    phase_kmod_status_files() {
      phase_start kmod_status_files
      emit_file current_boot_id /proc/sys/kernel/random/boot_id
      emit_file kmod_load_status $KMOD_LOAD_STATUS_FILE
      emit_file kmod_load_dmesg $KMOD_LOAD_DMESG_FILE
      emit_file zygisk_status $ZYGISK_STATUS_FILE
      emit_file kpm_load_status $KPM_LOAD_STATUS_FILE
      emit_file ports_load_status $PORTS_LOAD_STATUS_FILE
      phase_end
    }
    phase_runtime_status_files() {
      phase_start runtime_status_files
      emit_cmd kernel_release uname -r
      emit_eval kmod_state '[ -e $PROC_CTL ] && cat $PROC_CTL || true'
      emit_eval kpm_state 'if [ -x $KPM_ACTIVATOR ] && [ ! -f $KPM_MODULE_DIR/disable ]; then $KPM_ACTIVATOR state; fi'
      emit_eval kpm_runtime_modules '
        KPATCH=""
        for CANDIDATE in kpatch /data/adb/modules/KPatch-Next/bin/kpatch /data/adb/modules/kpatch-next/bin/kpatch; do
          if command -v "${'$'}CANDIDATE" >/dev/null 2>&1; then KPATCH="${'$'}CANDIDATE"; break; fi
          if [ -x "${'$'}CANDIDATE" ]; then KPATCH="${'$'}CANDIDATE"; break; fi
        done
        if [ -n "${'$'}KPATCH" ]; then
          KPM_LIST="${'$'}("${'$'}KPATCH" kpm list 2>/dev/null)"
          if [ ${'$'}? -eq 0 ]; then printf "available=1\\n%s\\n" "${'$'}KPM_LIST"; else echo available=0; fi
        elif [ -d /data/adb/ap ] && [ -f "${'$'}KPM_RUNTIME_PROBE_SOURCE" ]; then
          KPM_PROBE=/data/local/tmp/vpnhide_kpm_probe.${'$'}${'$'}
          if cp "${'$'}KPM_RUNTIME_PROBE_SOURCE" "${'$'}KPM_PROBE" && chmod 700 "${'$'}KPM_PROBE"; then
            "${'$'}KPM_PROBE" --apatch-kpm-list 2>/dev/null || echo available=0
          else
            echo available=0
          fi
          rm -f "${'$'}KPM_PROBE"
        else
          echo available=0
        fi'
      emit_file lsposed_state $LSPOSED_STATE_FILE
      emit_cmd getenforce getenforce
      # Is a *live* KPM runtime present (kernel actually patched, able to load
      # KPMs)? Two runtimes qualify:
      #   - APatch native KernelPatch: loads KPMs via supercall, detected by its
      #     /data/adb/ap dir (no kpatch CLI on disk — APatch keeps it in the
      #     manager app's private libs).
      #   - KPatch-Next-Module on any manager (Magisk / KSU / KSU-Next): ships
      #     the kpatch CLI at a fixed module path. Installing the module is not
      #     enough — the boot image must be patched from its UI first, so probe
      #     liveness the same way KPatch-Next's own status.sh does: `kpatch
      #     hello` succeeds only when the kernel is patched.
      # kpatchRuntimeAvailable() reads apatch_dir/hello_exit from this. Verified
      # on a Pixel 4a (APatch) and a Pixel 8 Pro (KSU-Next + KPatch-Next).
      emit_eval kpatch_runtime '
        [ -d /data/adb/ap ] && echo "apatch_dir=1" || echo "apatch_dir=0"
        KP=""
        for CAND in kpatch /data/adb/modules/KPatch-Next/bin/kpatch /data/adb/modules/kpatch-next/bin/kpatch; do
          if command -v "${'$'}CAND" >/dev/null 2>&1; then KP="${'$'}CAND"; break; fi
          [ -x "${'$'}CAND" ] && { KP="${'$'}CAND"; break; }
        done
        if [ -n "${'$'}KP" ]; then
          echo "kpatch_bin=${'$'}KP"
          "${'$'}KP" hello >/dev/null 2>&1
          echo "hello_exit=${'$'}?"
        else
          echo "kpatch_bin="
        fi'
      phase_end
    }
    __VPNHIDE_PM_PACKAGES_FUNCTION__
    phase_shell_identity() {
      phase_start shell_probe_identity
      # Who is the snapshot shell, really? The liveness probes below read
      # root-only runtime resources (0600 /proc/vpnhide_ctl; iptables needs
      # CAP_NET_ADMIN). If this shell is not uid 0 — e.g. a KernelSU grant that
      # raced or degraded — those probes read a false negative, and a "0" from
      # proc_exists/ports_chain must NOT be rendered as "inactive". The detectors
      # gate runtimeCheckable on this uid; errno_ctl distinguishes EACCES (no
      # access) from ENOENT (truly absent) for the bundle.
      emit_eval snapshot_shell_uid '
        echo "uid=${'$'}(id -u 2>/dev/null)"
        echo "id=${'$'}(id 2>/dev/null)"
        echo "context=${'$'}(cat /proc/self/attr/current 2>/dev/null | tr -d "\0")"
        if [ -e $PROC_CTL ]; then
          echo "errno_ctl=ok"
        else
          ERR=${'$'}(ls $PROC_CTL 2>&1 1>/dev/null)
          case "${'$'}ERR" in
            *[Pp]ermission*) echo "errno_ctl=eacces" ;;
            *o\ such*) echo "errno_ctl=enoent" ;;
            *) echo "errno_ctl=other:${'$'}ERR" ;;
          esac
        fi
      '
      phase_end
    }
    phase_proc_exists() {
      phase_start shell_probe_proc_exists
      emit_eval proc_exists '[ -e $PROC_CTL ] && echo 1 || echo 0'
      phase_end
    }
    phase_ports_chain() {
      phase_start shell_probe_ports_chain
      emit_eval ports_chain '
        iptables -L vpnhide_out -n >/dev/null 2>&1 &&
        iptables -C OUTPUT -j vpnhide_out >/dev/null 2>&1 &&
        ip6tables -L vpnhide_out6 -n >/dev/null 2>&1 &&
        ip6tables -C OUTPUT -j vpnhide_out6 >/dev/null 2>&1 &&
        echo 1 || echo 0
      '
      phase_end
    }
    phase_lsposed_framework() {
      phase_start shell_probe_lsposed_framework
      emit_eval lsposed_framework 'FOUND=0; for id in zygisk_vector zygisk_lsposed lsposed; do for base in /data/adb/modules /data/adb/modules_update; do dir="${'$'}base/${'$'}id"; if [ -f "${'$'}dir/module.prop" ]; then echo installed=1; if [ -f "${'$'}dir/disable" ]; then echo disabled=1; else echo disabled=0; fi; FOUND=1; break 2; fi; done; done; [ "${'$'}FOUND" = 1 ] || echo installed=0; echo probe_ok=1'
      phase_end
    }
    phase_vpn_ifaces() {
      phase_start shell_probe_vpn_ifaces
      emit_cmd vpn_ifaces grep -H . /sys/class/net/*/operstate
      phase_end
    }
    run_all_phases_sequential() {
      phase_module_props
      phase_target_files
      phase_legacy_config
      phase_kmod_status_files
      phase_runtime_status_files
      __VPNHIDE_PM_PACKAGES_PHASE__
      phase_shell_identity
      phase_proc_exists
      phase_ports_chain
      phase_lsposed_framework
      phase_vpn_ifaces
    }
    run_all_phases_sequential
    """.trimIndent()
        .replace(
            "__VPNHIDE_PM_PACKAGES_FUNCTION__",
            if (includePmPackages) {
                buildRootPackageInventoryPhase()
            } else {
                ""
            },
        ).replace(
            "__VPNHIDE_PM_PACKAGES_PHASE__",
            if (includePmPackages) "phase_pm_packages" else ":",
        )

private fun buildRootPackageInventoryPhase(): String =
    """
    phase_pm_packages() {
      phase_start pm_packages
      ${
        buildPerUserPackageInventoryShell(
            sectionBeginPrefix = ROOT_SNAPSHOT_BEGIN_PREFIX,
            sectionEndPrefix = ROOT_SNAPSHOT_END_PREFIX,
            stderrRedirect = "2>/dev/null",
        ).prependIndent("  ")
    }
      phase_end
    }
    """.trimIndent()

internal fun parseRootShellSnapshot(
    raw: String,
    recordMetric: (String, Long) -> Unit = StartupTrace::metric,
): Map<String, String> =
    parseFramedSections(
        raw = raw,
        beginPrefix = ROOT_SNAPSHOT_BEGIN_PREFIX,
        endPrefix = ROOT_SNAPSHOT_END_PREFIX,
        policy =
            FramedSectionParsePolicy(
                preserveIncomplete = false,
                discardOnMismatchedEnd = true,
                trimSectionEnd = false,
            ),
        consumeLine = { line ->
            if (!line.startsWith(ROOT_TIMING_PREFIX)) {
                false
            } else {
                val (name, duration) =
                    line.removePrefix(ROOT_TIMING_PREFIX).split("=", limit = 2).let {
                        it.firstOrNull()?.trim().orEmpty() to it.getOrNull(1)?.trim()?.toLongOrNull()
                    }
                if (name.isNotEmpty() && duration != null) recordMetric("root_shell_$name", duration)
                true
            }
        },
    ).complete
