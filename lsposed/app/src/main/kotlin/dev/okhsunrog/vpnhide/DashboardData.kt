package dev.okhsunrog.vpnhide

import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.okhsunrog.vpnhide.checks.CheckStatus
import java.io.File

// ── Domain types — invalid states are unrepresentable ────────────────────

sealed interface ModuleState {
    data object NotInstalled : ModuleState

    data class Installed(
        val version: String?,
        val active: Boolean,
        val targetCount: Int,
        // Only populated for kmod builds that carry the stamped `gkiVariant=` field
        // in module.prop (CI-built zips from v0.6.3+). Older builds report null.
        val gkiVariant: String? = null,
        // Non-null when the module is installed but the installation itself
        // is permanently broken (distinct from "active=false" which usually
        // just means a reboot is pending). UI colors the card red.
        val brokenReason: KmodBrokenReason? = null,
    ) : ModuleState
}

enum class KmodBrokenReason {
    WrongVariant,
    UnsupportedKernel,
    MissingKprobes,
    UnknownVariantInactive,
    AmbiguousLoadFailed,
}

/**
 * The single kmod problem to surface. [reason] drives the red module-card
 * color; [text] drives the dashboard error banner. Computed once (see
 * `loadDashboardState`) so the card and the banner can never disagree —
 * previously the priority order was hand-mirrored in two separate `when`
 * blocks. [reason] is null for a generic insmod failure where we only have
 * raw stderr to show, not a named diagnosis.
 */
internal data class KmodProblem(
    val reason: KmodBrokenReason?,
    val text: String,
)

sealed interface LsposedState {
    data object NotInstalled : LsposedState

    data class InstalledInactive(
        val version: String?,
    ) : LsposedState

    data class NeedsReboot(
        val version: String?,
    ) : LsposedState

    data class Active(
        val version: String?,
        val targetCount: Int,
    ) : LsposedState
}

sealed interface ProtectionCheck {
    data object NoVpn : ProtectionCheck

    data object NeedsRestart : ProtectionCheck

    data class Checked(
        val native: NativeResult,
        val java: JavaResult,
    ) : ProtectionCheck
}

sealed interface NativeResult {
    data object Ok : NativeResult

    data class Fail(
        val passed: Int,
        val failed: Int,
    ) : NativeResult

    data object NoModule : NativeResult
}

sealed interface JavaResult {
    data object Ok : JavaResult

    data class Fail(
        val failedChecks: Int,
    ) : JavaResult

    data object HooksInactive : JavaResult
}

internal enum class NativeModuleKind { Kmod, Zygisk, Ports }

internal data class ModuleMismatch(
    val kind: NativeModuleKind,
    val moduleVersion: String,
    val appVersion: String,
)

// Pure: given a list of (state, kind) pairs and the app version, returns
// the subset whose base version disagrees with the app. Extracted so the
// three kmod / zygisk / ports callsites in loadDashboardState share one
// code path instead of three near-identical if-blocks.
internal fun detectModuleMismatches(
    modules: List<Pair<ModuleState, NativeModuleKind>>,
    appVersion: String,
): List<ModuleMismatch> =
    modules.mapNotNull { (state, kind) ->
        val installed = state as? ModuleState.Installed ?: return@mapNotNull null
        val moduleVersion = installed.version ?: return@mapNotNull null
        if (versionsMismatch(moduleVersion, appVersion)) {
            ModuleMismatch(kind, moduleVersion, appVersion)
        } else {
            null
        }
    }

internal sealed interface LsposedFramework {
    data object NotInstalled : LsposedFramework

    data class Installed(
        val disabled: Boolean,
    ) : LsposedFramework
}

internal sealed interface LsposedConfig {
    data object ModuleNotConfigured : LsposedConfig

    data object Disabled : LsposedConfig

    data class Enabled(
        val entries: List<String>,
        val hasSystemFramework: Boolean,
        val extraEntries: List<String>,
    ) : LsposedConfig
}

internal enum class IssueSeverity { ERROR, WARNING }

internal data class Issue(
    val severity: IssueSeverity,
    val text: String,
)

internal data class DashboardState(
    val kmod: ModuleState,
    val zygisk: ModuleState,
    val lsposed: LsposedState,
    val ports: ModuleState,
    val nativeInstallRecommendation: NativeInstallRecommendation?,
    val kmodLoadStatus: KmodLoadStatus?,
    val protection: ProtectionCheck,
    val issues: List<Issue>,
)

internal data class NativeInstallRecommendation(
    val androidVersion: String,
    val kernelVersion: String,
    val kernelBranch: String?,
    val recommendedArtifact: String,
    val recommendedGkiVariant: String?,
    val preferKmod: Boolean,
    // Set when the kernel's GKI KMI couldn't be parsed from uname -r but the
    // kernel series ships with multiple KMI variants (5.10: android12 / 13;
    // 5.15: android13 / 14). Both candidates are valid picks — the UI shows
    // the primary plus "if it doesn't load, try the alternative". Series with
    // a single shipping variant (6.1 / 6.6 / 6.12) stay unambiguous even
    // without a KMI tag.
    val variantAmbiguous: Boolean = false,
    val alternativeArtifact: String? = null,
    val alternativeGkiVariant: String? = null,
)

// Boot-time diagnostics written by kmod/module/post-fs-data.sh into
// /data/adb/vpnhide_kmod/load_status. Stays valid across reboots,
// so bootId is compared against the current boot to know if the
// record is fresh.
internal data class KmodLoadStatus(
    val timestamp: Long?,
    val bootId: String?,
    val unameR: String?,
    val gkiVariant: String?,
    val kmodVersion: String?,
    val rootManager: String?,
    val kprobes: String?,
    val kretprobes: String?,
    val insmodExit: Int?,
    val loaded: Boolean,
    val insmodStderr: String?,
    val dmesgTail: String?,
    val freshForCurrentBoot: Boolean,
)

private const val TAG = "VpnHide-Dashboard"

internal fun parseKernelSeries(raw: String): String? = Regex("""\b(\d+\.\d+)""").find(raw)?.groupValues?.get(1)

internal fun parseKernelAndroidBranch(raw: String): String? =
    Regex("""android(\d+)""")
        .find(raw)
        ?.groupValues
        ?.get(1)
        ?.let { "Android $it" }

/**
 * Pick the right native-module artifact for the device based on its
 * kernel version (from `uname -r`) and Android OS label (from
 * `Build.VERSION.RELEASE`). Pulled out as a pure top-level function
 * so it can be unit-tested without a real device — the `uname -r`
 * read and `Build.VERSION` probe happen in the caller.
 *
 * Strategy, in order:
 *  1. Exact `(GKI KMI × kernel series)` match from the supported
 *     shipping matrix → specific kmod zip, preferKmod=true.
 *  2. KMI tag missing from `uname -r` (custom kernel stripped it)
 *     but the kernel series is GKI-shipping:
 *       - 6.1 / 6.6 / 6.12 have a single shipping variant each →
 *         deterministic kmod recommendation, preferKmod=true.
 *       - 5.10 / 5.15 have two shipping variants each → return the
 *         primary plus an alternative via `variantAmbiguous=true`;
 *         the UI shows "try primary, if it doesn't load try alt".
 *  3. Pre-GKI series (<5.10) or unparseable kernel version → fall
 *     back to zygisk (preferKmod=false) since we have no kmod
 *     binaries that can load against such kernels' Module.symvers.
 *
 * Returns `null` only if [kernelRaw] is blank (no uname output).
 * `deviceAndroidLabel` is only reflected back in the returned
 * `androidVersion` for display — it's never used for KMI matching
 * (those spaces are independent: an Android 15 ROM routinely runs
 * an android12 KMI kernel).
 */
internal fun buildNativeInstallRecommendation(
    kernelRaw: String,
    deviceAndroidLabel: String,
): NativeInstallRecommendation? {
    val kernelVersion = kernelRaw.trim().ifBlank { return null }
    val kernelSeries = parseKernelSeries(kernelVersion)
    val kernelBranch = parseKernelAndroidBranch(kernelVersion) // GKI KMI

    data class KmiMatch(
        val kmi: String,
        val zip: String,
    )

    val exact: KmiMatch? =
        when (kernelBranch to kernelSeries) {
            "Android 12" to "5.10" -> KmiMatch("android12-5.10", "vpnhide-kmod-android12-5.10.zip")
            "Android 13" to "5.10" -> KmiMatch("android13-5.10", "vpnhide-kmod-android13-5.10.zip")
            "Android 13" to "5.15" -> KmiMatch("android13-5.15", "vpnhide-kmod-android13-5.15.zip")
            "Android 14" to "5.15" -> KmiMatch("android14-5.15", "vpnhide-kmod-android14-5.15.zip")
            "Android 14" to "6.1" -> KmiMatch("android14-6.1", "vpnhide-kmod-android14-6.1.zip")
            "Android 15" to "6.6" -> KmiMatch("android15-6.6", "vpnhide-kmod-android15-6.6.zip")
            "Android 16" to "6.12" -> KmiMatch("android16-6.12", "vpnhide-kmod-android16-6.12.zip")
            else -> null
        }
    if (exact != null) {
        return NativeInstallRecommendation(
            androidVersion = deviceAndroidLabel,
            kernelVersion = kernelVersion,
            kernelBranch = kernelBranch,
            recommendedArtifact = exact.zip,
            recommendedGkiVariant = exact.kmi,
            preferKmod = true,
        )
    }

    val fallback: Pair<KmiMatch, KmiMatch?>? =
        when (kernelSeries) {
            "5.10" -> {
                KmiMatch("android12-5.10", "vpnhide-kmod-android12-5.10.zip") to
                    KmiMatch("android13-5.10", "vpnhide-kmod-android13-5.10.zip")
            }

            "5.15" -> {
                KmiMatch("android13-5.15", "vpnhide-kmod-android13-5.15.zip") to
                    KmiMatch("android14-5.15", "vpnhide-kmod-android14-5.15.zip")
            }

            "6.1" -> {
                KmiMatch("android14-6.1", "vpnhide-kmod-android14-6.1.zip") to null
            }

            "6.6" -> {
                KmiMatch("android15-6.6", "vpnhide-kmod-android15-6.6.zip") to null
            }

            "6.12" -> {
                KmiMatch("android16-6.12", "vpnhide-kmod-android16-6.12.zip") to null
            }

            else -> {
                null
            }
        }
    if (fallback != null) {
        val (primary, alternative) = fallback
        return NativeInstallRecommendation(
            androidVersion = deviceAndroidLabel,
            kernelVersion = kernelVersion,
            kernelBranch = kernelBranch,
            recommendedArtifact = primary.zip,
            recommendedGkiVariant = primary.kmi,
            preferKmod = true,
            variantAmbiguous = alternative != null,
            alternativeArtifact = alternative?.zip,
            alternativeGkiVariant = alternative?.kmi,
        )
    }

    return NativeInstallRecommendation(
        androidVersion = deviceAndroidLabel,
        kernelVersion = kernelVersion,
        kernelBranch = kernelBranch,
        recommendedArtifact = "vpnhide-zygisk.zip",
        recommendedGkiVariant = null,
        preferKmod = false,
    )
}

// ── Module-prop / status-file parsing (pure, unit-tested) ────────────────

// Strip the `v` prefix from module.prop versions at parse time so everything
// downstream sees a plain semver string (APK versionName has no `v`).
internal data class ModulePropInfo(
    val installed: Boolean,
    val version: String?,
    val gkiVariant: String?,
)

// Older CI-built zips didn't stamp `gkiVariant=` but their injected updateJson
// URL already encodes the KMI: `.../update-kmod-<kmi>.json`. Recover the variant
// from there so wrong-variant detection works for existing installs.
private val UPDATE_JSON_KMI_REGEX = Regex("""update-kmod-([^/]+)\.json""")

internal fun parseModuleProp(raw: String): ModulePropInfo {
    if (raw.isBlank()) return ModulePropInfo(false, null, null)
    var version: String? = null
    var gkiVariant: String? = null
    var updateJsonKmi: String? = null
    for (line in raw.lines()) {
        when {
            line.startsWith("version=") -> {
                version = normalizeVersion(line.removePrefix("version="))
            }

            line.startsWith("gkiVariant=") -> {
                gkiVariant = line.removePrefix("gkiVariant=").trim().ifBlank { null }
            }

            line.startsWith("updateJson=") -> {
                updateJsonKmi = UPDATE_JSON_KMI_REGEX.find(line.removePrefix("updateJson="))?.groupValues?.get(1)
            }
        }
    }
    return ModulePropInfo(true, version, gkiVariant ?: updateJsonKmi)
}

/** Count configured target packages, excluding the app's own package (it is
 * always present invisibly and must not inflate the user-facing count). */
internal fun countTargets(
    raw: String,
    selfPkg: String,
): Int = parseConfigLines(raw).count { it != selfPkg }

internal fun readKmodLoadStatus(
    currentBootId: String,
    raw: String,
    dmesgRaw: String,
): KmodLoadStatus? {
    if (raw.isBlank()) return null
    val props = parseKeyValueLines(raw)
    val bootId = props["boot_id"]?.trim()
    return KmodLoadStatus(
        timestamp = props["timestamp"]?.trim()?.toLongOrNull(),
        bootId = bootId,
        unameR = props["uname_r"]?.trim(),
        gkiVariant = props["gki_variant"]?.trim()?.ifBlank { null },
        kmodVersion = props["kmod_version"]?.trim()?.ifBlank { null },
        rootManager = props["root_manager"]?.trim()?.ifBlank { null },
        kprobes = props["kprobes"]?.trim()?.ifBlank { null },
        kretprobes = props["kretprobes"]?.trim()?.ifBlank { null },
        insmodExit = props["insmod_exit"]?.trim()?.toIntOrNull(),
        loaded = props["loaded"]?.trim() == "1",
        insmodStderr = props["insmod_stderr"]?.trim()?.ifBlank { null },
        dmesgTail = dmesgRaw.trim().ifBlank { null },
        freshForCurrentBoot = bootId != null && bootId == currentBootId,
    )
}

// ── kmod problem classification (pure, unit-tested) ──────────────────────

/**
 * The single diagnosed problem for an installed-but-not-working kmod, as a
 * data-only value with the exact arguments its banner text needs. [reason]
 * drives the red module-card color; the [renderKmodProblem] mapping turns the
 * kind into the localized banner string. Computing the kind in one pure place
 * keeps the card color and the banner text from ever disagreeing — they used
 * to be hand-mirrored across two `when` blocks.
 */
internal sealed interface KmodProblemKind {
    val reason: KmodBrokenReason?

    data object KprobesMissing : KmodProblemKind {
        override val reason get() = KmodBrokenReason.MissingKprobes
    }

    data class UnsupportedKernel(
        val unameR: String,
        val recommendedArtifact: String,
    ) : KmodProblemKind {
        override val reason get() = KmodBrokenReason.UnsupportedKernel
    }

    data class WrongVariant(
        val installedVariant: String,
        val recommendedKmi: String,
        val recommendedArtifact: String,
    ) : KmodProblemKind {
        override val reason get() = KmodBrokenReason.WrongVariant
    }

    data class UnknownVariant(
        val recommendedArtifact: String,
    ) : KmodProblemKind {
        override val reason get() = KmodBrokenReason.UnknownVariantInactive
    }

    data class AmbiguousLoadFailed(
        val installedVariant: String,
        val tryArtifact: String,
    ) : KmodProblemKind {
        override val reason get() = KmodBrokenReason.AmbiguousLoadFailed
    }

    // Generic insmod failure where we only have raw stderr, no named diagnosis.
    data class LoadFailed(
        val insmodStderr: String,
    ) : KmodProblemKind {
        override val reason: KmodBrokenReason? get() = null
    }
}

/**
 * Diagnose what's wrong with an installed kmod, or null if it's fine / not a
 * diagnosable failure. Priority order: kprobes-missing first (no variant will
 * ever work), then unsupported-kernel (wrong tool), wrong-variant (concrete
 * mismatch), unknown-variant (old build that didn't stamp gkiVariant),
 * ambiguous-load-failed (one of two valid candidates failed this boot), and
 * finally a generic insmod failure when we have stderr to show.
 *
 * An active kmod (`/proc/vpnhide_targets` present) is empirical proof the
 * install works, so every check except the activity-independent kprobes probe
 * is gated on `!active`.
 */
internal fun classifyKmodProblem(
    kmod: ModuleState,
    recommendation: NativeInstallRecommendation?,
    loadStatus: KmodLoadStatus?,
): KmodProblemKind? {
    if (kmod !is ModuleState.Installed) return null
    val freshLoad = loadStatus != null && loadStatus.freshForCurrentBoot

    if (freshLoad && loadStatus.kretprobes == "n") {
        return KmodProblemKind.KprobesMissing
    }
    // Past this point every diagnosis means "installed but not loaded".
    if (kmod.active) return null

    val rec = recommendation
    // rec.recommendedArtifact is a non-null field, so a non-null rec always
    // yields a non-null artifact — the `!!` uses below are safe under the
    // `rec != null` / `rec?.preferKmod == true` guards that precede them.
    val recommendedArtifact = rec?.recommendedArtifact
    val installedVariant = kmod.gkiVariant

    if (rec != null && !rec.preferKmod) {
        return KmodProblemKind.UnsupportedKernel(loadStatus?.unameR ?: "?", recommendedArtifact!!)
    }

    val recommendedKmi = rec?.recommendedGkiVariant
    if (rec?.preferKmod == true &&
        recommendedKmi != null &&
        installedVariant != null &&
        installedVariant != recommendedKmi &&
        installedVariant != rec.alternativeGkiVariant
    ) {
        return KmodProblemKind.WrongVariant(installedVariant, recommendedKmi, recommendedArtifact!!)
    }

    if (installedVariant == null && rec?.preferKmod == true) {
        return KmodProblemKind.UnknownVariant(recommendedArtifact!!)
    }

    if (freshLoad &&
        rec?.variantAmbiguous == true &&
        installedVariant != null &&
        (installedVariant == rec.recommendedGkiVariant || installedVariant == rec.alternativeGkiVariant)
    ) {
        val tryArtifact =
            if (installedVariant == rec.recommendedGkiVariant) rec.alternativeArtifact else rec.recommendedArtifact
        return KmodProblemKind.AmbiguousLoadFailed(installedVariant, tryArtifact ?: "?")
    }

    if (freshLoad && loadStatus.insmodStderr != null) {
        return KmodProblemKind.LoadFailed(loadStatus.insmodStderr)
    }

    return null
}

private fun renderKmodProblem(
    kind: KmodProblemKind,
    res: android.content.res.Resources,
): KmodProblem =
    KmodProblem(
        reason = kind.reason,
        text =
            when (kind) {
                KmodProblemKind.KprobesMissing -> {
                    res.getString(R.string.dashboard_issue_kprobes_missing)
                }

                is KmodProblemKind.UnsupportedKernel -> {
                    res.getString(R.string.dashboard_issue_kmod_not_supported_kernel, kind.unameR, kind.recommendedArtifact)
                }

                is KmodProblemKind.WrongVariant -> {
                    res.getString(
                        R.string.dashboard_issue_kmod_wrong_variant,
                        kind.installedVariant,
                        kind.recommendedKmi,
                        kind.recommendedArtifact,
                    )
                }

                is KmodProblemKind.UnknownVariant -> {
                    res.getString(R.string.dashboard_issue_kmod_unknown_variant, kind.recommendedArtifact)
                }

                is KmodProblemKind.AmbiguousLoadFailed -> {
                    res.getString(
                        R.string.dashboard_issue_kmod_ambiguous_try_alternative,
                        kind.installedVariant,
                        kind.tryArtifact,
                    )
                }

                is KmodProblemKind.LoadFailed -> {
                    res.getString(R.string.dashboard_issue_kmod_load_failed, kind.insmodStderr)
                }
            },
    )

// ── LSPosed state resolution (pure, unit-tested) ─────────────────────────

/**
 * Resolve the user-facing [LsposedState] from the framework presence, the
 * on-disk module config, and whether the hook heartbeat is fresh for this
 * boot. A current-boot heartbeat is the strongest signal (module is active);
 * otherwise the config/framework decide inactive/needs-reboot/not-configured.
 */
internal fun resolveLsposedState(
    hooksActiveThisBoot: Boolean,
    hookVersion: String?,
    lsposedTargetCount: Int,
    framework: LsposedFramework,
    config: LsposedConfig?,
): LsposedState {
    if (hooksActiveThisBoot) {
        return LsposedState.Active(hookVersion, lsposedTargetCount)
    }
    return when (config) {
        null -> {
            LsposedState.InstalledInactive(null)
        }

        LsposedConfig.ModuleNotConfigured -> {
            when (framework) {
                LsposedFramework.NotInstalled -> LsposedState.NotInstalled
                is LsposedFramework.Installed -> LsposedState.InstalledInactive(null)
            }
        }

        LsposedConfig.Disabled -> {
            LsposedState.InstalledInactive(null)
        }

        is LsposedConfig.Enabled -> {
            if (config.hasSystemFramework) {
                LsposedState.NeedsReboot(hookVersion)
            } else {
                LsposedState.InstalledInactive(null)
            }
        }
    }
}

internal fun loadDashboardState(
    cm: ConnectivityManager,
    context: android.content.Context,
    selfNeedsRestart: Boolean,
    rootSnapshot: RootSnapshot,
): DashboardState {
    val issues = mutableListOf<Issue>()
    val res = context.resources
    val selfPkg = context.packageName

    fun err(text: String) {
        issues += Issue(IssueSeverity.ERROR, text)
    }

    fun warn(text: String) {
        issues += Issue(IssueSeverity.WARNING, text)
    }

    VpnHideLog.i(TAG, "=== Loading dashboard state ===")
    StartupTrace.mark("dashboard_derive_start")
    val shellSnapshot = rootSnapshot.sections

    // ── Module detection ──
    // Module-prop / status parsing lives in pure top-level functions
    // (parseModuleProp, parseKeyValueLines, countTargets) so they're
    // unit-testable; only the issue-text rendering below needs `res`.
    fun countTargets(raw: String): Int = countTargets(raw, selfPkg)

    fun buildModuleVersionIssue(
        kind: NativeModuleKind,
        moduleVersion: String,
        appVersion: String,
    ): String {
        val normalizedModuleVersion = normalizeVersion(moduleVersion)
        val normalizedAppVersion = normalizeVersion(appVersion)
        return when (compareSemver(normalizedModuleVersion, normalizedAppVersion)) {
            null, 0 -> {
                res.getString(
                    when (kind) {
                        NativeModuleKind.Kmod -> R.string.dashboard_issue_kmod_version_mismatch
                        NativeModuleKind.Zygisk -> R.string.dashboard_issue_zygisk_version_mismatch
                        NativeModuleKind.Ports -> R.string.dashboard_issue_ports_version_mismatch
                    },
                    moduleVersion,
                    appVersion,
                )
            }

            in Int.MIN_VALUE..-1 -> {
                res.getString(
                    when (kind) {
                        NativeModuleKind.Kmod -> R.string.dashboard_issue_update_kmod
                        NativeModuleKind.Zygisk -> R.string.dashboard_issue_update_zygisk
                        NativeModuleKind.Ports -> R.string.dashboard_issue_update_ports
                    },
                    moduleVersion,
                    appVersion,
                )
            }

            else -> {
                res.getString(
                    when (kind) {
                        NativeModuleKind.Kmod -> R.string.dashboard_issue_update_app_for_kmod
                        NativeModuleKind.Zygisk -> R.string.dashboard_issue_update_app_for_zygisk
                        NativeModuleKind.Ports -> R.string.dashboard_issue_update_app_for_ports
                    },
                    moduleVersion,
                    appVersion,
                )
            }
        }
    }

    fun androidMajorVersionLabel(): String {
        @Suppress("DEPRECATION")
        val release =
            if (Build.VERSION.SDK_INT >= 30) {
                Build.VERSION.RELEASE_OR_CODENAME
            } else {
                Build.VERSION.RELEASE
            }.substringBefore('.')
        return "Android $release"
    }

    fun resolveScopeEntryLabel(entry: String): String {
        if (entry == "system" || entry == "system/0") return "System Framework"

        val packageName = entry.substringBefore('/')
        val userId = entry.substringAfter('/', "")
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val appLabel =
                context.packageManager
                    .getApplicationLabel(appInfo)
                    .toString()
                    .trim()
            when {
                appLabel.isEmpty() -> packageName
                userId.isNotEmpty() && userId != "0" -> "$appLabel ($userId)"
                else -> appLabel
            }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun readLsposedConfig(): LsposedConfig? {
        val dbCopy = File(context.cacheDir, "vpnhide_lspd_modules_config.db")
        val dbWalCopy = File(context.cacheDir, "vpnhide_lspd_modules_config.db-wal")
        val dbShmCopy = File(context.cacheDir, "vpnhide_lspd_modules_config.db-shm")
        dbCopy.delete()
        dbWalCopy.delete()
        dbShmCopy.delete()

        val dbPath = dbCopy.absolutePath
        val walPath = dbWalCopy.absolutePath
        val shmPath = dbShmCopy.absolutePath
        val sourceBase = "/data/adb/lspd/config/modules_config.db"
        val copyStart = SystemClock.elapsedRealtime()
        val (copyExit, copyOut) =
            suExec(
                "cat $sourceBase > $dbPath && " +
                    "chmod 644 $dbPath && " +
                    "(cat $sourceBase-wal > $walPath 2>/dev/null && chmod 644 $walPath || true) && " +
                    "(cat $sourceBase-shm > $shmPath 2>/dev/null && chmod 644 $shmPath || true) && " +
                    "ls -l $dbPath $walPath $shmPath 2>/dev/null || true",
            )
        StartupTrace.metric("dashboard_lsposed_db_copy", SystemClock.elapsedRealtime() - copyStart)
        if (copyExit != 0 || !dbCopy.isFile) {
            VpnHideLog.w(TAG, "failed to copy LSPosed config db for inspection: exit=$copyExit out=$copyOut")
            return null
        }
        VpnHideLog.i(TAG, "lsposed db copy: ${copyOut.trim()}")

        val queryStart = SystemClock.elapsedRealtime()
        return try {
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db
                    .rawQuery(
                        "SELECT mid, enabled FROM modules WHERE module_pkg_name = ?",
                        arrayOf(selfPkg),
                    ).use { moduleCursor ->
                        if (!moduleCursor.moveToFirst()) {
                            return LsposedConfig.ModuleNotConfigured
                        }

                        val mid = moduleCursor.getLong(0)
                        val enabled = moduleCursor.getInt(1) != 0
                        if (!enabled) {
                            return LsposedConfig.Disabled
                        }

                        val scopeEntries = mutableListOf<Pair<String, Int>>()
                        db
                            .rawQuery(
                                "SELECT app_pkg_name, user_id FROM scope WHERE mid = ? ORDER BY user_id, app_pkg_name",
                                arrayOf(mid.toString()),
                            ).use { scopeCursor ->
                                while (scopeCursor.moveToNext()) {
                                    scopeEntries += scopeCursor.getString(0) to scopeCursor.getInt(1)
                                }
                            }
                        val hasSystemFramework = scopeEntries.any { (pkg, userId) -> pkg == "system" && userId == 0 }
                        val renderedEntries =
                            scopeEntries.map { (pkg, userId) ->
                                if (pkg == "system" && userId == 0) {
                                    "system"
                                } else {
                                    "$pkg/$userId"
                                }
                            }
                        val extraEntries =
                            scopeEntries
                                .filterNot { (pkg, userId) ->
                                    (pkg == "system" && userId == 0) || pkg == selfPkg
                                }.map { (pkg, userId) -> "$pkg/$userId" }

                        LsposedConfig.Enabled(
                            entries = renderedEntries,
                            hasSystemFramework = hasSystemFramework,
                            extraEntries = extraEntries,
                        )
                    }
            }
        } catch (e: Exception) {
            VpnHideLog.w(TAG, "failed to inspect LSPosed config db: ${e.message}")
            null
        } finally {
            StartupTrace.metric("dashboard_lsposed_db_query", SystemClock.elapsedRealtime() - queryStart)
            dbCopy.delete()
            dbWalCopy.delete()
            dbShmCopy.delete()
        }
    }

    fun detectLsposedFramework(): LsposedFramework {
        val out = shellSnapshot["lsposed_framework"].orEmpty()
        val props = parseKeyValueLines(out)
        val probeOk = props["probe_ok"] == "1"
        val installedValue = props["installed"]
        val disabledValue = props["disabled"]
        val malformed =
            !probeOk ||
                installedValue == null ||
                (installedValue == "1" && disabledValue == null)
        if (malformed) {
            VpnHideLog.w(TAG, "lsposed framework probe returned malformed output: $out")
            return LsposedFramework.NotInstalled
        }
        val installed = installedValue == "1"
        val disabled = props["disabled"] == "1"
        val framework =
            if (installed) {
                LsposedFramework.Installed(disabled = disabled)
            } else {
                LsposedFramework.NotInstalled
            }
        VpnHideLog.i(TAG, "lsposed framework: $framework (raw=$out)")
        return framework
    }

    // kmod
    val kmodProp = parseModuleProp(shellSnapshot["kmod_prop"].orEmpty())
    val procExists = shellSnapshot["proc_exists"].orEmpty()
    val kmodActive = kmodProp.installed && procExists.trim() == "1"
    val kmodTargetCount = if (kmodProp.installed) countTargets(shellSnapshot["kmod_targets"].orEmpty()) else 0
    // Built without brokenReason — populated below after kernelRecommendation
    // and kmodLoadStatus are ready.
    val kmodRaw: ModuleState =
        if (kmodProp.installed) {
            ModuleState.Installed(
                version = kmodProp.version,
                active = kmodActive,
                targetCount = kmodTargetCount,
                gkiVariant = kmodProp.gkiVariant,
            )
        } else {
            ModuleState.NotInstalled
        }
    VpnHideLog.i(TAG, "kmodRaw: $kmodRaw")

    // zygisk
    val zygiskProp = parseModuleProp(shellSnapshot["zygisk_prop"].orEmpty())
    val zygiskInstalled = zygiskProp.installed
    val zygiskVersion = zygiskProp.version
    val zygiskStatusFile = File(context.filesDir, ZYGISK_STATUS_FILE_NAME)
    val zygiskStatusRaw =
        try {
            zygiskStatusFile.takeIf { it.isFile }?.readText().orEmpty()
        } catch (e: Exception) {
            VpnHideLog.w(TAG, "failed to read zygisk status heartbeat: ${e.message}")
            ""
        }
    val zygiskProps = parseKeyValueLines(zygiskStatusRaw)
    val currentBootId = shellSnapshot["current_boot_id"].orEmpty()
    val zygiskBootId = zygiskProps["boot_id"]
    val zygiskActive = zygiskInstalled && zygiskBootId != null && zygiskBootId == currentBootId.trim()
    val zygiskTargetCount = if (zygiskInstalled) countTargets(shellSnapshot["zygisk_targets"].orEmpty()) else 0
    val zygisk: ModuleState =
        if (zygiskInstalled) {
            ModuleState.Installed(zygiskVersion, zygiskActive, zygiskTargetCount)
        } else {
            ModuleState.NotInstalled
        }
    VpnHideLog.i(TAG, "zygisk: $zygisk (heartbeatBootId=$zygiskBootId currentBootId=${currentBootId.trim()})")

    // ports (iptables-based loopback blocker)
    val portsProp = parseModuleProp(shellSnapshot["ports_prop"].orEmpty())
    val portsObserverCount =
        if (portsProp.installed) countTargets(shellSnapshot["ports_observers"].orEmpty()) else 0
    val portsChainExists = shellSnapshot["ports_chain"].orEmpty()
    val portsActive = portsProp.installed && portsChainExists.trim() == "1"
    val ports: ModuleState =
        if (portsProp.installed) {
            ModuleState.Installed(portsProp.version, portsActive, portsObserverCount)
        } else {
            ModuleState.NotInstalled
        }
    VpnHideLog.i(TAG, "ports: $ports")
    StartupTrace.mark("dashboard_modules_done")

    // Recommendation based purely on the kernel — used by the install card,
    // the "kmod-capable kernel, only zygisk installed" warning (W1), and the
    // wrong-variant detection below.
    val kernelRaw = shellSnapshot["kernel_release"].orEmpty()
    val kernelRecommendation = buildNativeInstallRecommendation(kernelRaw, androidMajorVersionLabel())
    val kmodLoadStatus =
        readKmodLoadStatus(
            currentBootId.trim(),
            shellSnapshot["kmod_load_status"].orEmpty(),
            shellSnapshot["kmod_load_dmesg"].orEmpty(),
        )
    VpnHideLog.i(TAG, "kmodLoadStatus=$kmodLoadStatus")

    // Single source of truth for "what's wrong with the installed kmod" —
    // classifyKmodProblem (pure, unit-tested) decides the priority-ordered
    // diagnosis; renderKmodProblem maps it to the localized banner text.
    // [reason] colors the card, [text] is the banner — both derive from the
    // one classification so they can't disagree.
    val kmodProblem: KmodProblem? =
        classifyKmodProblem(kmodRaw, kernelRecommendation, kmodLoadStatus)
            ?.let { renderKmodProblem(it, res) }
    val kmodBrokenReason = kmodProblem?.reason
    val kmod: ModuleState =
        if (kmodRaw is ModuleState.Installed && kmodBrokenReason != null) {
            kmodRaw.copy(brokenReason = kmodBrokenReason)
        } else {
            kmodRaw
        }
    VpnHideLog.i(TAG, "kmod (with brokenReason): $kmod")
    // Only surface the blue "what to install" card when nothing is
    // installed yet. Wrong-variant / broken / unsupported-kernel cases
    // already emit a red error below with the same CTA — showing both
    // duplicates the instruction.
    val nativeInstallRecommendation =
        kernelRecommendation?.takeIf {
            kmod is ModuleState.NotInstalled && zygisk is ModuleState.NotInstalled
        }
    VpnHideLog.i(
        TAG,
        "nativeInstallRecommendation=$nativeInstallRecommendation " +
            "(raw=$kernelRecommendation kmodProblem=$kmodProblem)",
    )
    StartupTrace.mark("dashboard_kernel_done")

    // lsposed hook status
    val hookStatusRaw = shellSnapshot["hook_status"].orEmpty()
    val hookProps = parseKeyValueLines(hookStatusRaw)
    val hookVersion = hookProps["version"]
    val hookBootId = hookProps["boot_id"]
    val hooksActiveThisBoot = hookBootId != null && hookBootId == currentBootId.trim()
    val lsposedTargetCount = countTargets(shellSnapshot["lsposed_targets"].orEmpty())
    val lsposedFramework = detectLsposedFramework()
    val lsposedConfig =
        if (hooksActiveThisBoot) {
            // A current-boot hook heartbeat is stronger evidence than the
            // on-disk LSPosed DB: the module is active, and config warnings
            // are intentionally suppressed for active hooks below.
            null
        } else {
            when (lsposedFramework) {
                LsposedFramework.NotInstalled -> {
                    LsposedConfig.ModuleNotConfigured
                }

                is LsposedFramework.Installed -> {
                    if (lsposedFramework.disabled) {
                        LsposedConfig.Disabled
                    } else {
                        readLsposedConfig()
                    }
                }
            }
        }
    StartupTrace.mark("dashboard_lsposed_config_done")
    val lsposed: LsposedState =
        resolveLsposedState(
            hooksActiveThisBoot = hooksActiveThisBoot,
            hookVersion = hookVersion,
            lsposedTargetCount = lsposedTargetCount,
            framework = lsposedFramework,
            config = lsposedConfig,
        )
    VpnHideLog.i(
        TAG,
        "lsposed: $lsposed (hookBootId=$hookBootId currentBootId=${currentBootId.trim()} " +
            "framework=$lsposedFramework hooksActive=$hooksActiveThisBoot config=$lsposedConfig)",
    )
    StartupTrace.mark("dashboard_lsposed_done")

    // ── Issues ──
    val hasNative = kmod is ModuleState.Installed || zygisk is ModuleState.Installed
    if (!hasNative) {
        err(res.getString(R.string.dashboard_issue_no_native))
    }
    if (lsposedFramework is LsposedFramework.NotInstalled && lsposed !is LsposedState.Active) {
        err(res.getString(R.string.dashboard_issue_lsposed_not_installed))
    }
    if (lsposed is LsposedState.NeedsReboot) {
        err(res.getString(R.string.dashboard_issue_reboot))
    }
    // Only report LSPosed config issues when hooks are not already active at runtime —
    // if hooks are active, the config is clearly working regardless of what we detect on disk
    if (lsposed !is LsposedState.Active) {
        when (lsposedConfig) {
            null -> {
                err(res.getString(R.string.dashboard_issue_lsposed_config_unreadable))
            }

            LsposedConfig.ModuleNotConfigured -> {
                if (lsposedFramework is LsposedFramework.Installed) {
                    err(res.getString(R.string.dashboard_issue_lsposed_not_enabled))
                }
            }

            LsposedConfig.Disabled -> {
                err(res.getString(R.string.dashboard_issue_lsposed_not_enabled))
            }

            is LsposedConfig.Enabled -> {
                if (!lsposedConfig.hasSystemFramework) {
                    err(res.getString(R.string.dashboard_issue_lsposed_no_system_scope))
                }
                if (lsposedConfig.extraEntries.isNotEmpty()) {
                    // Extra entries work, they're just cosmetic noise — warn.
                    warn(
                        res.getString(
                            R.string.dashboard_issue_lsposed_extra_scope,
                            lsposedConfig.extraEntries.map(::resolveScopeEntryLabel).joinToString(", "),
                        ),
                    )
                }
            }
        }
    }

    // AOSP-drift detector: HookEntry's install-time smoke-check on the
    // private NetworkCapabilities/NetworkInfo/LinkProperties fields it
    // touches by reflection. Non-empty means the running AOSP renamed
    // or retyped a field — the corresponding writeToParcel hook was
    // skipped at install time, Java-layer protection is degraded for
    // that class. Independent of lsposed Active/Inactive state: hooks
    // can still be "active" in heartbeat sense but with partial coverage.
    val brokenFields = hookProps["broken_fields"]?.takeIf { it.isNotBlank() }
    if (brokenFields != null) {
        val sdkLabel = hookProps["aosp_sdk"]?.takeIf { it.isNotBlank() } ?: "?"
        err(res.getString(R.string.dashboard_issue_lsposed_field_rename, brokenFields, sdkLabel))
    }

    val appVersion = BuildConfig.VERSION_NAME
    // Version mismatches are warnings — modules keep working, user just needs to
    // update the lagging side. Full coverage is not affected by a patch-level gap.
    val moduleMismatches =
        detectModuleMismatches(
            listOf(
                kmod to NativeModuleKind.Kmod,
                zygisk to NativeModuleKind.Zygisk,
                ports to NativeModuleKind.Ports,
            ),
            appVersion,
        )
    moduleMismatches.forEach { mismatch ->
        warn(buildModuleVersionIssue(mismatch.kind, mismatch.moduleVersion, mismatch.appVersion))
    }
    val totalTargets = lsposedTargetCount + kmodTargetCount + zygiskTargetCount
    if (totalTargets == 0) {
        err(res.getString(R.string.dashboard_issue_no_targets))
    }
    if (ports is ModuleState.Installed && ports.targetCount == 0) {
        warn(res.getString(R.string.dashboard_issue_ports_no_observers))
    }
    if (lsposed is LsposedState.Active) {
        val runningVersion = lsposed.version
        if (versionsMismatch(runningVersion, appVersion)) {
            VpnHideLog.w(TAG, "version mismatch: running=$runningVersion app=$appVersion")
            warn(res.getString(R.string.dashboard_issue_version_mismatch, runningVersion, appVersion))
        }
    }

    // ── Warnings: suboptimal-but-working setups ──

    // W1: kernel supports kmod, but user only installed zygisk. Zygisk is
    // detected by banking / payment apps, so a user has to remember Z-off
    // per such app; kmod is invisible to anti-tamper.
    if (kernelRecommendation?.preferKmod == true &&
        zygisk is ModuleState.Installed &&
        kmod is ModuleState.NotInstalled
    ) {
        warn(
            res.getString(
                R.string.dashboard_issue_kmod_capable_but_zygisk,
                kernelRecommendation.recommendedArtifact,
            ),
        )
    }

    // W2: kmod and zygisk both active simultaneously — same coverage,
    // but Zygisk adds the per-app footgun for banking / payment targets.
    if (kmod is ModuleState.Installed &&
        kmod.active &&
        zygisk is ModuleState.Installed &&
        zygisk.active
    ) {
        warn(res.getString(R.string.dashboard_issue_both_native_active))
    }

    // W3: user has debug logging turned on — VPN Hide is writing verbose lines
    // to logcat that a forensic reader with root can see. The flag file is
    // written by the Diagnostics → Debug logging toggle; absent file ⇒
    // default off ⇒ no warning.
    val debugEnabledRaw = shellSnapshot["debug_logging"].orEmpty()
    if (debugEnabledRaw.trim() == "1") {
        warn(res.getString(R.string.dashboard_issue_debug_logging_on))
    }

    // W4: SELinux Permissive exposes six detection vectors we rely on SELinux
    // to block (RTM_GETROUTE, /proc/net/{tcp,tcp6,udp,udp6,dev,fib_trie},
    // /sys/class/net). See the coverage table in the top-level README.
    val getenforce = shellSnapshot["getenforce"].orEmpty()
    if (getenforce.trim().equals("Permissive", ignoreCase = true)) {
        warn(res.getString(R.string.dashboard_issue_selinux_permissive))
    }

    // W5: VPN Hide installed in more than one user profile (work profile,
    // MIUI Second Space, etc.). Each instance can write to the shared
    // target files, but each one's app picker only sees apps from its own
    // profile (PackageManager.getInstalledApplications is per-user). A
    // Save from a profile that doesn't see all the targets would silently
    // drop them. Recommend uninstalling everywhere except the main profile.
    val selfUidCount =
        parsePackageUidMap(shellSnapshot["pm_packages"].orEmpty())[selfPkg]
            ?.distinct()
            ?.size
            ?: 0
    if (selfUidCount > 1) {
        warn(res.getString(R.string.dashboard_issue_self_multi_profile, selfUidCount))
    }
    StartupTrace.mark("dashboard_issues_done")

    // ── Errors: kmod variant / load problems ──
    // The diagnosis (reason + banner text) was computed once above as
    // `kmodProblem`; emit its text here. Only one kmod-failure banner fires,
    // and its priority can't drift from the card color because both come
    // from the same value.
    kmodProblem?.let { err(it.text) }

    // ── Protection checks ──
    StartupTrace.mark("dashboard_protection_start")
    val vpnActive = isVpnActiveFromSnapshot(shellSnapshot["vpn_ifaces"].orEmpty())
    VpnHideLog.i(TAG, "vpnActive=$vpnActive selfNeedsRestart=$selfNeedsRestart")

    val protection: ProtectionCheck =
        when {
            !vpnActive -> {
                ProtectionCheck.NoVpn
            }

            selfNeedsRestart -> {
                ProtectionCheck.NeedsRestart
            }

            else -> {
                val native =
                    if (hasNative) {
                        runNativeProtectionCheck()
                    } else {
                        NativeResult.NoModule
                    }
                VpnHideLog.i(TAG, "nativeResult=$native")

                val java =
                    if (lsposed is LsposedState.Active) {
                        runJavaProtectionCheck(cm)
                    } else {
                        JavaResult.HooksInactive
                    }
                VpnHideLog.i(TAG, "javaResult=$java")

                ProtectionCheck.Checked(native, java)
            }
        }

    VpnHideLog.i(TAG, "protection=$protection issues=$issues")
    StartupTrace.mark("dashboard_protection_done")
    VpnHideLog.i(TAG, "=== Dashboard state loaded ===")

    return DashboardState(
        kmod = kmod,
        zygisk = zygisk,
        lsposed = lsposed,
        ports = ports,
        nativeInstallRecommendation = nativeInstallRecommendation,
        kmodLoadStatus = kmodLoadStatus,
        protection = protection,
        issues = issues,
    )
}

private fun runNativeProtectionCheck(): NativeResult {
    var passed = 0
    var failed = 0
    var skipped = 0
    for (spec in NATIVE_CHECKS) {
        val name = spec.id
        try {
            val out = spec.run()
            when (out.status) {
                CheckStatus.NETWORK_BLOCKED -> {
                    skipped++
                    VpnHideLog.d(TAG, "native[$name]: NETWORK_BLOCKED")
                }

                CheckStatus.PASS -> {
                    passed++
                    VpnHideLog.d(TAG, "native[$name]: PASS")
                }

                CheckStatus.FAIL -> {
                    failed++
                    VpnHideLog.w(TAG, "native[$name]: FAIL — ${out.detail}")
                }
            }
        } catch (e: Exception) {
            failed++
            Log.e(TAG, "native[$name]: exception — ${e.message}")
        }
    }

    VpnHideLog.i(TAG, "native protection: passed=$passed failed=$failed skipped=$skipped")
    return when {
        // Nothing ran (all NETWORK_BLOCKED) — treat as OK so the UI doesn't
        // paint a scary red when the real issue is the app having no network
        // permission; a dedicated banner covers that case separately.
        passed == 0 && failed == 0 -> NativeResult.Ok

        failed == 0 -> NativeResult.Ok

        passed > 0 -> NativeResult.Fail(passed, failed)

        else -> NativeResult.Fail(0, failed)
    }
}

private fun runJavaProtectionCheck(cm: ConnectivityManager): JavaResult {
    // Same gates the Diagnostics screen uses: with no active network (or no
    // caps for it) there's nothing for an app to leak, so report OK without
    // probing. Then reuse the exact CheckResult-producing probes the
    // Diagnostics screen runs — Dashboard cares only about the five
    // VPN-presence vectors below (not proxy / route checks), so it runs
    // precisely that subset and counts the ones that detected a leak. The
    // probe names are irrelevant here (Dashboard discards the detail text),
    // hence the empty labels.
    if (cm.activeNetwork == null) {
        VpnHideLog.d(TAG, "java: no active network")
        return JavaResult.Ok
    }
    if (cm.getNetworkCapabilities(cm.activeNetwork) == null) {
        VpnHideLog.d(TAG, "java: no capabilities")
        return JavaResult.Ok
    }

    val failed =
        listOf(
            checkHasTransportVpn(cm, ""),
            checkHasCapabilityNotVpn(cm, ""),
            checkTransportInfo(cm, ""),
            checkAllNetworksVpn(cm, ""),
            checkLinkPropertiesIfname(cm, ""),
        ).count { it.passed == false }

    VpnHideLog.i(TAG, "java protection: failed=$failed")
    return if (failed == 0) JavaResult.Ok else JavaResult.Fail(failed)
}
