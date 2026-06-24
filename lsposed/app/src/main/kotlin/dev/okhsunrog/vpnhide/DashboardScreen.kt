package dev.okhsunrog.vpnhide

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.settings.LocalSettingsState
import dev.okhsunrog.vpnhide.ui.components.EnhancedButton
import dev.okhsunrog.vpnhide.ui.components.EnhancedCard
import dev.okhsunrog.vpnhide.ui.components.GroupedCard
import dev.okhsunrog.vpnhide.ui.components.pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DashboardScreen(
    selfNeedsRestart: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by DashboardCache.state.collectAsState()
    val loadError by DashboardCache.error.collectAsState()
    val updateInfo by UpdateCheckCache.info.collectAsState()
    var showChangelog by remember { mutableStateOf(false) }
    var changelogData by remember { mutableStateOf<ChangelogData?>(null) }

    // Both caches are reactive to tab switches without re-doing work:
    // ensureLoaded / ensureFresh are no-ops if the data is already
    // populated or an inflight job hasn't finished yet.
    LaunchedEffect(Unit) {
        DashboardCache.ensureLoaded(scope, context, selfNeedsRestart)
        UpdateCheckCache.ensureFresh(scope, BuildConfig.VERSION_NAME)
    }
    LaunchedEffect(Unit) {
        if (shouldShowChangelog(context)) {
            val data = withContext(Dispatchers.IO) { loadChangelog(context) }
            // Only raise the dialog when there's something to show — the
            // emptiness guard lives here (a side-effect scope) rather than
            // inside ChangelogDialog's composition body.
            if (data != null && data.history.isNotEmpty()) {
                changelogData = data
                showChangelog = true
            }
            markChangelogSeen(context)
        }
    }

    if (showChangelog && changelogData != null) {
        ChangelogDialog(
            data = changelogData!!,
            onDismiss = { showChangelog = false },
        )
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        // Pinned status palette — shared by the Protection status banners
        // (NeedsRestart) and the Errors / Warnings issue banners below.
        // Theme.colorScheme.{errorContainer,tertiaryContainer} get remixed
        // by Material You to whatever the wallpaper suggests, which in
        // practice landed on "lavender" and "pink" on user devices — those
        // read as "note", not "problem". Same hardcoded pairs the module-
        // status cards use for active/inactive.
        val errorBg = StatusColors.errorContainer()
        val errorHeader = StatusColors.errorHeader()
        val warningBg = StatusColors.warningContainer()
        val warningHeader = StatusColors.warningHeader()
        val onBannerColor = MaterialTheme.colorScheme.onSurface

        val s = state
        val error = loadError
        if (error != null) {
            DashboardLoadErrorCard(
                title = stringResource(R.string.dashboard_load_failed_title),
                message =
                    stringResource(
                        if (s == null) {
                            R.string.dashboard_load_failed_message
                        } else {
                            R.string.dashboard_refresh_failed_message
                        },
                    ),
                containerColor = errorBg,
                titleColor = errorHeader,
                contentColor = onBannerColor,
                onRetry = { DashboardCache.refresh(scope, context, selfNeedsRestart) },
            )
            Spacer(Modifier.height(12.dp))
            if (s == null) return@Column
        }

        if (s == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // Issues split by severity — computed up front so the hero card can
        // summarize them. Errors = user attention, warnings = working-but-
        // suboptimal. Their banner sections render further down.
        val errors = s.issues.filter { it.severity == IssueSeverity.ERROR }
        val warnings = s.issues.filter { it.severity == IssueSeverity.WARNING }

        // Hero: the whole setup's health at a glance.
        DashboardHeroCard(state = s, errorCount = errors.size, warningCount = warnings.size)
        Spacer(Modifier.height(20.dp))

        // Module status cards — one grouped block (byIndex corners).
        SectionHeader(stringResource(R.string.dashboard_modules))
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LsposedCard(s.lsposed, index = 0, count = 4)
            ModuleCard(stringResource(R.string.dashboard_kmod), s.kmod, index = 1, count = 4)
            ModuleCard(stringResource(R.string.dashboard_zygisk), s.zygisk, selfNeedsRestart, index = 2, count = 4)
            ModuleCard(stringResource(R.string.dashboard_ports), s.ports, index = 3, count = 4)
        }
        s.nativeInstallRecommendation?.let { recommendation ->
            Spacer(Modifier.height(8.dp))
            NativeInstallRecommendationCard(recommendation)
        }
        updateInfo?.let { info ->
            Spacer(Modifier.height(8.dp))
            UpdateAvailableCard(info)
        }

        // Protection status
        Spacer(Modifier.height(20.dp))
        SectionHeader(stringResource(R.string.dashboard_protection))
        Spacer(Modifier.height(8.dp))

        when (val p = s.protection) {
            is ProtectionCheck.NoVpn -> {
                VpnOffPrompt(
                    onRetry = {
                        // Re-read dashboard state (re-runs its own VPN
                        // + protection probes) and re-run the diag
                        // cache so both screens move to "Ready" when
                        // VPN is back.
                        DashboardCache.refresh(scope, context, selfNeedsRestart)
                        DiagnosticsCache.retry(scope, context)
                    },
                )
            }

            is ProtectionCheck.NeedsRestart -> {
                StatusBanner(
                    text = stringResource(R.string.dashboard_needs_restart),
                    containerColor = warningBg,
                    contentColor = onBannerColor,
                )
            }

            is ProtectionCheck.Checked -> {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    NativeProtectionCard(p.native, index = 0, count = 2)
                    JavaProtectionCard(p.java, index = 1, count = 2)
                }
            }
        }

        if (errors.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.dashboard_issues, errors.size), color = errorHeader)
            Spacer(Modifier.height(8.dp))
            for (issue in errors) {
                StatusBanner(
                    text = issue.text,
                    containerColor = errorBg,
                    contentColor = onBannerColor,
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        if (warnings.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.dashboard_warnings, warnings.size), color = warningHeader)
            Spacer(Modifier.height(8.dp))
            for (issue in warnings) {
                StatusBanner(
                    text = issue.text,
                    containerColor = warningBg,
                    contentColor = onBannerColor,
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── UI Components ────────────────────────────────────────────────────────

/** A bold section title. Pass [color] for the colored issue/warning headers. */
@Composable
private fun SectionHeader(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

/** Overall health, ranked worst-signal-wins from protection state + issues. */
private enum class HeroStatus { Protected, Attention, Unprotected, VpnOff }

private fun computeHeroStatus(
    state: DashboardState,
    errorCount: Int,
    warningCount: Int,
): HeroStatus {
    val p = state.protection
    if (p is ProtectionCheck.NoVpn) return HeroStatus.VpnOff
    // 0 = protected, 1 = attention, 2 = unprotected — keep the worst signal.
    var rank = 0
    when (p) {
        ProtectionCheck.NoVpn -> {}

        // handled above
        ProtectionCheck.NeedsRestart -> {
            rank = maxOf(rank, 1)
        }

        is ProtectionCheck.Checked -> {
            val native = p.native
            val java = p.java
            val hardFail = (native is NativeResult.Fail && native.passed == 0) || java is JavaResult.Fail
            val partial =
                native is NativeResult.Fail || native is NativeResult.NoModule || java is JavaResult.HooksInactive
            when {
                hardFail -> rank = maxOf(rank, 2)
                partial -> rank = maxOf(rank, 1)
            }
        }
    }
    when {
        errorCount > 0 -> rank = maxOf(rank, 2)
        warningCount > 0 -> rank = maxOf(rank, 1)
    }
    return when (rank) {
        0 -> HeroStatus.Protected
        1 -> HeroStatus.Attention
        else -> HeroStatus.Unprotected
    }
}

private data class HeroVisual(
    val container: Color,
    val accent: Color,
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
)

/**
 * The big at-a-glance status card at the top of the Dashboard. Summarizes the
 * whole setup's health into one of four states with a tinted container, accent
 * icon and headline; the icon breathes when fully protected.
 */
@Composable
private fun DashboardHeroCard(
    state: DashboardState,
    errorCount: Int,
    warningCount: Int,
) {
    val animations = LocalSettingsState.current.animationsEnabled
    val status = computeHeroStatus(state, errorCount, warningCount)
    val visual =
        when (status) {
            HeroStatus.Protected -> {
                HeroVisual(
                    container = StatusColors.successContainer(),
                    accent = StatusColors.successDot,
                    icon = Icons.Default.CheckCircle,
                    titleRes = R.string.dashboard_hero_protected_title,
                    subtitleRes = R.string.dashboard_hero_protected_subtitle,
                )
            }

            HeroStatus.Attention -> {
                HeroVisual(
                    container = StatusColors.warningContainer(),
                    accent = StatusColors.warningAccent,
                    icon = Icons.Default.Warning,
                    titleRes = R.string.dashboard_hero_attention_title,
                    subtitleRes = R.string.dashboard_hero_attention_subtitle,
                )
            }

            HeroStatus.Unprotected -> {
                HeroVisual(
                    container = StatusColors.errorContainer(),
                    accent = StatusColors.errorAccent,
                    icon = Icons.Default.Warning,
                    titleRes = R.string.dashboard_hero_unprotected_title,
                    subtitleRes = R.string.dashboard_hero_unprotected_subtitle,
                )
            }

            HeroStatus.VpnOff -> {
                HeroVisual(
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.Default.Info,
                    titleRes = R.string.dashboard_hero_vpnoff_title,
                    subtitleRes = R.string.dashboard_hero_vpnoff_subtitle,
                )
            }
        }
    EnhancedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = visual.container,
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .pulse(
                            enabled = status == HeroStatus.Protected && animations,
                            min = 0.92f,
                            max = 1.06f,
                            durationMillis = 1300,
                        ).clip(CircleShape)
                        .background(visual.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.accent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(visual.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(visual.subtitleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun ModuleCard(
    name: String,
    state: ModuleState,
    selfNeedsRestart: Boolean = false,
    index: Int = -1,
    count: Int = 1,
) {
    when (state) {
        is ModuleState.NotInstalled -> {
            ModuleCardShell(
                name = name,
                index = index,
                count = count,
                version = null,
                subtitle = stringResource(R.string.dashboard_not_installed),
                dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        is ModuleState.Installed -> {
            val active = state.active
            val broken = state.brokenReason
            val brokenSubtitleRes =
                when (broken) {
                    KmodBrokenReason.WrongVariant -> R.string.dashboard_kmod_broken_wrong_variant
                    KmodBrokenReason.UnsupportedKernel -> R.string.dashboard_kmod_broken_unsupported_kernel
                    KmodBrokenReason.MissingKprobes -> R.string.dashboard_kmod_broken_no_kprobes
                    KmodBrokenReason.UnknownVariantInactive -> R.string.dashboard_kmod_broken_unknown_variant
                    KmodBrokenReason.AmbiguousLoadFailed -> R.string.dashboard_kmod_broken_ambiguous
                    KmodBrokenReason.SignatureEnforced -> R.string.dashboard_kmod_broken_signature_enforced
                    null -> null
                }
            ModuleCardShell(
                name = name,
                index = index,
                count = count,
                version = state.version,
                subtitle =
                    when {
                        brokenSubtitleRes != null -> stringResource(brokenSubtitleRes)
                        active -> stringResource(R.string.dashboard_active_targets, state.targetCount)
                        selfNeedsRestart -> stringResource(R.string.dashboard_installed_restart_app)
                        else -> stringResource(R.string.dashboard_installed_inactive)
                    },
                dotColor =
                    when {
                        broken != null -> StatusColors.errorDot
                        active -> StatusColors.successDot
                        else -> StatusColors.warningAccent
                    },
                containerColor =
                    when {
                        broken != null -> StatusColors.errorContainer()
                        active -> StatusColors.successContainer()
                        else -> StatusColors.warningContainer()
                    },
            )
        }
    }
}

@Composable
private fun LsposedCard(
    state: LsposedState,
    index: Int = -1,
    count: Int = 1,
) {
    val moduleName = stringResource(R.string.dashboard_lsposed_module)
    val installedVersion = BuildConfig.VERSION_NAME
    when (state) {
        is LsposedState.NotInstalled -> {
            ModuleCardShell(
                name = moduleName,
                index = index,
                count = count,
                version = installedVersion,
                subtitle = stringResource(R.string.dashboard_not_installed),
                dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        is LsposedState.InstalledInactive -> {
            ModuleCardShell(
                name = moduleName,
                index = index,
                count = count,
                version = installedVersion,
                subtitle = stringResource(R.string.dashboard_installed_inactive),
                dotColor = StatusColors.warningAccent,
                containerColor = StatusColors.warningContainer(),
            )
        }

        is LsposedState.NeedsReboot -> {
            ModuleCardShell(
                name = moduleName,
                index = index,
                count = count,
                version = installedVersion,
                subtitle = stringResource(R.string.dashboard_reboot_needed),
                dotColor = StatusColors.warningAccent,
                containerColor = StatusColors.warningContainer(),
            )
        }

        is LsposedState.Active -> {
            val subtitle =
                stringResource(R.string.dashboard_active_targets, state.targetCount) +
                    if (state.version != null) {
                        "\n" + stringResource(R.string.dashboard_running_version, state.version)
                    } else {
                        ""
                    }
            ModuleCardShell(
                name = moduleName,
                index = index,
                count = count,
                version = installedVersion,
                subtitle = subtitle,
                dotColor = StatusColors.successDot,
                containerColor = StatusColors.successContainer(),
            )
        }
    }
}

@Composable
private fun ModuleCardShell(
    name: String,
    version: String?,
    subtitle: String,
    dotColor: Color,
    containerColor: Color,
    index: Int,
    count: Int,
) {
    GroupedCard(
        index = index,
        count = count,
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = dotColor,
                modifier = Modifier.size(12.dp),
            ) {}
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (version != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = version,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun NativeInstallRecommendationCard(recommendation: NativeInstallRecommendation) {
    val containerColor =
        if (recommendation.preferKmod) {
            StatusColors.infoContainer()
        } else {
            StatusColors.zygiskRecommendContainer()
        }

    EnhancedCard(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.dashboard_install_recommendation_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text =
                    stringResource(
                        R.string.dashboard_install_recommendation_device,
                        recommendation.androidVersion,
                        recommendation.kernelVersion,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Disambiguate the GKI KMI tag baked into uname -r (e.g.
            // "android12-5.10") from the device's Android OS release on
            // devices where they differ — common on old Pixels still on
            // an android12 KMI kernel under an Android 14/15 ROM. Hide
            // the note when both match (would just be noise) or when
            // uname -r carries no KMI tag at all.
            val kmiBranch = recommendation.kernelBranch
            if (kmiBranch != null && kmiBranch != recommendation.androidVersion) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        stringResource(
                            R.string.dashboard_install_recommendation_kmi_note,
                            kmiBranch.replace(" ", "").lowercase(),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            val alternative = recommendation.alternativeArtifact
            Text(
                text =
                    when {
                        !recommendation.preferKmod -> {
                            stringResource(
                                R.string.dashboard_install_recommendation_zygisk,
                                recommendation.recommendedArtifact,
                            )
                        }

                        recommendation.variantAmbiguous && alternative != null -> {
                            stringResource(
                                R.string.dashboard_install_recommendation_kmod_ambiguous,
                                recommendation.recommendedArtifact,
                                alternative,
                            )
                        }

                        else -> {
                            stringResource(
                                R.string.dashboard_install_recommendation_kmod,
                                recommendation.recommendedArtifact,
                            )
                        }
                    },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (!recommendation.preferKmod) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dashboard_install_recommendation_zygisk_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun NativeProtectionCard(
    result: NativeResult,
    index: Int = -1,
    count: Int = 1,
) {
    val (containerColor, statusText, statusColor) =
        when (result) {
            is NativeResult.Ok -> {
                Triple(
                    StatusColors.successContainer(),
                    stringResource(R.string.dashboard_protection_ok),
                    StatusColors.successDot,
                )
            }

            is NativeResult.Fail -> {
                val text =
                    if (result.passed > 0) {
                        stringResource(R.string.dashboard_protection_partial)
                    } else {
                        stringResource(R.string.dashboard_protection_fail)
                    }
                val color = if (result.passed > 0) StatusColors.warningAccent else StatusColors.errorAccent
                val bg = if (result.passed > 0) StatusColors.warningContainer() else StatusColors.errorContainer()
                Triple(bg, text, color)
            }

            is NativeResult.NoModule -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceVariant,
                    stringResource(R.string.dashboard_protection_no_module),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    ProtectionCardShell(
        label = stringResource(R.string.dashboard_native_protection),
        statusText = statusText,
        statusColor = statusColor,
        containerColor = containerColor,
        pulsing = result is NativeResult.Ok,
        index = index,
        count = count,
    )
}

@Composable
private fun JavaProtectionCard(
    result: JavaResult,
    index: Int = -1,
    count: Int = 1,
) {
    val (containerColor, statusText, statusColor) =
        when (result) {
            is JavaResult.Ok -> {
                Triple(
                    StatusColors.successContainer(),
                    stringResource(R.string.dashboard_protection_ok),
                    StatusColors.successDot,
                )
            }

            is JavaResult.Fail -> {
                Triple(
                    StatusColors.errorContainer(),
                    stringResource(R.string.dashboard_protection_fail),
                    StatusColors.errorAccent,
                )
            }

            is JavaResult.HooksInactive -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceVariant,
                    stringResource(R.string.dashboard_protection_hooks_inactive),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    ProtectionCardShell(
        label = stringResource(R.string.dashboard_java_protection),
        statusText = statusText,
        statusColor = statusColor,
        containerColor = containerColor,
        pulsing = result is JavaResult.Ok,
        index = index,
        count = count,
    )
}

@Composable
private fun ProtectionCardShell(
    label: String,
    statusText: String,
    statusColor: Color,
    containerColor: Color,
    pulsing: Boolean = false,
    index: Int = -1,
    count: Int = 1,
) {
    val animations = LocalSettingsState.current.animationsEnabled
    GroupedCard(
        index = index,
        count = count,
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pulsing) {
                    Box(
                        modifier =
                            Modifier
                                .size(9.dp)
                                .pulse(enabled = animations, min = 0.55f, max = 1f, durationMillis = 1100)
                                .clip(CircleShape)
                                .background(statusColor),
                    )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }
        }
    }
}

@Composable
private fun DashboardLoadErrorCard(
    title: String,
    message: String,
    containerColor: Color,
    titleColor: Color,
    contentColor: Color,
    onRetry: () -> Unit,
) {
    EnhancedCard(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = titleColor,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            Spacer(Modifier.height(12.dp))
            EnhancedButton(onClick = onRetry) {
                Text(stringResource(R.string.vpn_off_retry))
            }
        }
    }
}

// ── Update & Changelog ──────────────────────────────────────────────────

@Composable
private fun UpdateAvailableCard(info: UpdateInfo) {
    val context = LocalContext.current
    EnhancedCard(
        modifier = Modifier.fillMaxWidth(),
        color = StatusColors.infoContainer(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.update_available_subtitle, info.latestVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.width(12.dp))
            EnhancedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)),
                    )
                },
            ) {
                Text(stringResource(R.string.update_download))
            }
        }
    }
}

@Composable
private fun ChangelogDialog(
    data: ChangelogData,
    onDismiss: () -> Unit,
) {
    // Non-empty by construction — the caller only shows this dialog when
    // changelog history has entries (see the load effect above).
    val entries = remember(data) { data.history }
    var index by remember { mutableIntStateOf(0) }
    val entry = entries[index]
    val locale =
        LocalConfiguration.current.locales[0]
            .language
    val sectionLabels =
        mapOf(
            "added" to stringResource(R.string.changelog_section_added),
            "changed" to stringResource(R.string.changelog_section_changed),
            "fixed" to stringResource(R.string.changelog_section_fixed),
            "notes" to stringResource(R.string.changelog_section_notes),
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entries.size > 1) {
                    IconButton(
                        onClick = { index-- },
                        enabled = index > 0,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.changelog_title, entry.version),
                    modifier = Modifier.weight(1f),
                )
                if (entries.size > 1) {
                    IconButton(
                        onClick = { index++ },
                        enabled = index < entries.size - 1,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (section in entry.sections) {
                    if (section.items.isEmpty()) continue
                    Text(
                        text = sectionLabels[section.type] ?: section.type,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    for (item in section.items) {
                        val text = if (locale == "ru") item.ru else item.en
                        Text(
                            text = "\u2022 $text",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}
