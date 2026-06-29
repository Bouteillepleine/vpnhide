package dev.okhsunrog.vpnhide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.generated.HookIds
import dev.okhsunrog.vpnhide.ui.components.EnhancedButton
import dev.okhsunrog.vpnhide.ui.components.EnhancedCard
import dev.okhsunrog.vpnhide.ui.components.GroupedCard
import dev.okhsunrog.vpnhide.ui.theme.AppColors

@Composable
fun StatisticsScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val state by StatisticsCache.state.collectAsState()
    val loadError by StatisticsCache.error.collectAsState()

    LaunchedEffect(Unit) {
        StatisticsCache.ensureLoaded(scope)
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        val s = state
        val error = loadError
        if (error != null) {
            StatisticsLoadErrorCard(
                previousDataVisible = s != null,
                onRetry = { StatisticsCache.refresh(scope) },
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

        StatisticsHeroCard(s)
        Spacer(Modifier.height(20.dp))

        SectionHeader(stringResource(R.string.statistics_backends))
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            s.backends.forEachIndexed { index, backend ->
                BackendSummaryCard(backend, index = index, count = s.backends.size)
            }
        }

        if (!s.hasAnyData) {
            Spacer(Modifier.height(12.dp))
            StatusBanner(
                text = stringResource(R.string.statistics_no_data),
                containerColor = StatusColors.infoContainer(),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }

        s.backends
            .filter { it.hasData }
            .forEach { backend ->
                Spacer(Modifier.height(20.dp))
                SectionHeader(backendName(backend.backend))
                Spacer(Modifier.height(8.dp))
                if (backend.rows.isEmpty()) {
                    StatusBanner(
                        text = stringResource(R.string.statistics_no_counters),
                        containerColor = StatusColors.infoContainer(),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        backend.rows.forEachIndexed { index, row ->
                            StatisticsRowCard(row, index = index, count = backend.rows.size)
                        }
                    }
                }
            }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatisticsLoadErrorCard(
    previousDataVisible: Boolean,
    onRetry: () -> Unit,
) {
    EnhancedCard(
        modifier = Modifier.fillMaxWidth(),
        color = StatusColors.errorContainer(),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.statistics_load_failed_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = StatusColors.errorHeader(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text =
                    stringResource(
                        if (previousDataVisible) {
                            R.string.statistics_refresh_failed_message
                        } else {
                            R.string.statistics_load_failed_message
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            EnhancedButton(onClick = onRetry) {
                Text(stringResource(R.string.vpn_off_retry))
            }
        }
    }
}

@Composable
private fun StatisticsHeroCard(state: StatisticsState) {
    EnhancedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = AppColors.cardContainer,
    ) {
        Column(modifier = Modifier.padding(18.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(
                    iconTint = StatusColors.infoAccent,
                    container = StatusColors.infoContainer(),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.statistics_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.statistics_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatisticsMetric(
                        label = stringResource(R.string.statistics_total_events),
                        value = formatStatCount(state.totalCount),
                        accent = StatusColors.infoAccent,
                        modifier = Modifier.weight(1f),
                    )
                    StatisticsMetric(
                        label = stringResource(R.string.statistics_active_backends),
                        value = "${state.activeBackendCount}/${state.backends.size}",
                        accent = if (state.hasAnyData) StatusColors.successDot else StatusColors.warningAccent,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatisticsMetric(
                        label = stringResource(R.string.statistics_counter_rows),
                        value = state.totalRows.toString(),
                        accent = StatusColors.successDot,
                        modifier = Modifier.weight(1f),
                    )
                    StatisticsMetric(
                        label = stringResource(R.string.statistics_hooks),
                        value = state.backends.sumOf { it.hookedCount }.toString(),
                        accent = StatusColors.successDot,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun IconBubble(
    iconTint: Color,
    container: Color,
) {
    Box(
        modifier =
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.BarChart,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(31.dp),
        )
    }
}

@Composable
private fun StatisticsMetric(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .background(AppColors.cardContainerStrong)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BackendSummaryCard(
    backend: BackendStatistics,
    index: Int,
    count: Int,
) {
    val health = backendHealth(backend)
    val visual = backendHealthVisual(health)
    GroupedCard(
        index = index,
        count = count,
        modifier = Modifier.fillMaxWidth(),
        color = AppColors.cardContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackendBadge(
                text = backendBadge(backend.backend),
                accentColor = visual.accent,
                containerColor = visual.container,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = backendName(backend.backend),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        stringResource(
                            R.string.statistics_backend_detail,
                            visual.label,
                            backend.hookedCount,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatStatCount(backend.totalCount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = visual.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.statistics_events),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatisticsRowCard(
    row: StatisticsRow,
    index: Int,
    count: Int,
) {
    GroupedCard(
        index = index,
        count = count,
        modifier = Modifier.fillMaxWidth(),
        color = AppColors.cardContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = rowTargetText(row),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = rowHookText(row),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.hook?.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatStatCount(row.count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = StatusColors.infoAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BackendBadge(
    text: String,
    accentColor: Color,
    containerColor: Color,
) {
    Box(
        modifier =
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun backendName(backend: HookIds.Backend): String =
    stringResource(
        when (backend) {
            HookIds.Backend.KMOD -> R.string.dashboard_backend_kmod
            HookIds.Backend.KPM -> R.string.dashboard_backend_kpm
            HookIds.Backend.ZYGISK -> R.string.dashboard_backend_zygisk
            HookIds.Backend.LSPOSED -> R.string.dashboard_backend_lsposed
        },
    )

private fun backendBadge(backend: HookIds.Backend): String =
    when (backend) {
        HookIds.Backend.KMOD -> "K"
        HookIds.Backend.KPM -> "KPM"
        HookIds.Backend.ZYGISK -> "Z"
        HookIds.Backend.LSPOSED -> "J"
    }

@Composable
private fun rowTargetText(row: StatisticsRow): String =
    row.packageNames
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?: stringResource(R.string.statistics_unknown_uid, row.uid)

@Composable
private fun rowHookText(row: StatisticsRow): String = row.hook?.hookName ?: stringResource(R.string.statistics_unknown_hook, row.hookId)

private enum class BackendHealth { Ok, Partial, Error, NoData }

private fun backendHealth(backend: BackendStatistics): BackendHealth {
    val status = backend.status
    return when {
        status == null && backend.rows.isEmpty() -> BackendHealth.NoData

        status == null -> BackendHealth.Partial

        status.error ==
            HookIds.StatusError.OK.code
                .toLong()
        -> BackendHealth.Ok

        status.error ==
            HookIds.StatusError.PARTIAL_HOOKS.code
                .toLong()
        -> BackendHealth.Partial

        else -> BackendHealth.Error
    }
}

private data class HealthVisual(
    val label: String,
    val accent: Color,
    val container: Color,
)

@Composable
private fun backendHealthVisual(health: BackendHealth): HealthVisual =
    when (health) {
        BackendHealth.Ok -> {
            HealthVisual(
                label = stringResource(R.string.statistics_status_ok),
                accent = StatusColors.successDot,
                container = StatusColors.successContainer(),
            )
        }

        BackendHealth.Partial -> {
            HealthVisual(
                label = stringResource(R.string.statistics_status_partial),
                accent = StatusColors.warningAccent,
                container = StatusColors.warningContainer(),
            )
        }

        BackendHealth.Error -> {
            HealthVisual(
                label = stringResource(R.string.statistics_status_error),
                accent = StatusColors.errorAccent,
                container = StatusColors.errorContainer(),
            )
        }

        BackendHealth.NoData -> {
            HealthVisual(
                label = stringResource(R.string.statistics_status_no_data),
                accent = StatusColors.neutralAccent,
                container = AppColors.neutralAccentContainer,
            )
        }
    }
