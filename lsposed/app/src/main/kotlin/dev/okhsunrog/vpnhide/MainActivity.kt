package dev.okhsunrog.vpnhide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val splashReady = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.mark("activity_on_create")
        installSplashScreen().setKeepOnScreenCondition { !splashReady.get() }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Load the user's debug-logging preference before anything else
        // runs so the first suExec + Dashboard reload honor it.
        VpnHideLog.init(applicationContext)
        setContent {
            VpnHideApp(
                onDashboardReady = {
                    if (splashReady.compareAndSet(false, true)) {
                        StartupTrace.dashboardReady()
                        // Re-propagate the persisted flag to the on-disk sinks as a
                        // safety-net, but keep it off the cold-start critical path.
                        lifecycleScope.launch(Dispatchers.IO) {
                            applyDebugLoggingRuntime(VpnHideLog.enabled)
                        }
                    }
                },
                onRootDeniedReady = {
                    if (splashReady.compareAndSet(false, true)) {
                        StartupTrace.rootDeniedReady()
                    }
                },
            )
        }
    }
}

private sealed interface RootState {
    data object Granted : RootState

    data object Denied : RootState
}

private fun checkRootAccess(): Boolean {
    val (exitCode, stdout) = suExec("id")
    return exitCode == 0 && stdout.contains("uid=0")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnHideApp(
    onDashboardReady: () -> Unit = {},
    onRootDeniedReady: () -> Unit = {},
) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (darkTheme) darkColorScheme() else lightColorScheme()
        }

    MaterialTheme(colorScheme = colorScheme) {
        var rootState by remember { mutableStateOf<RootState?>(null) }

        LaunchedEffect(Unit) {
            rootState =
                withContext(Dispatchers.IO) {
                    if (checkRootAccess()) RootState.Granted else RootState.Denied
                }
            StartupTrace.mark("root_check_done")
        }

        when (rootState) {
            // splash holds until root check completes
            null -> {
            }

            RootState.Denied -> {
                // Drop splash — RootDeniedScreen has no async prerequisites.
                LaunchedEffect(Unit) { onRootDeniedReady() }
                RootDeniedScreen()
            }

            RootState.Granted -> {
                MainScreen(onReady = onDashboardReady)
            }
        }
    }
}

private enum class Tab { Dashboard, Protection, Diagnostics }

private data class RefreshContext(
    val loading: Boolean,
    val onRefresh: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onReady: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appContext = context.applicationContext
    val startupCoordinator = remember(appContext) { StartupCoordinator(appContext) }
    var currentTab by remember { mutableStateOf(Tab.Dashboard) }
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var showSystem by remember { mutableStateOf(false) }
    var showRussianOnly by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    val appListLoading by AppListCache.loading.collectAsState()
    val targetsLoading by TargetsCache.loading.collectAsState()
    val dashboardLoading by DashboardCache.loading.collectAsState()
    val dashboardState by DashboardCache.state.collectAsState()
    val dashboardError by DashboardCache.error.collectAsState()
    val rootSnapshot by RootSnapshotCache.snapshot.collectAsState()
    val selfTargetState by startupCoordinator.selfTargetState.collectAsState()
    val selfNeedsRestart =
        (selfTargetState as? StartupSelfTargetState.Ready)?.selfNeedsRestart
    val selfTargetError =
        (selfTargetState as? StartupSelfTargetState.Failed)?.message
    val refreshRestart = selfNeedsRestart ?: false

    LaunchedEffect(startupCoordinator) {
        startupCoordinator.prepareSelfTargets()
    }

    // Start the app-scoped caches as soon as the self-target preparation
    // is resolved. Keep that preparation first: it mutates the target files
    // and determines whether this app process needs a restart, so Dashboard
    // must not derive protection state from a stale answer. Protection still
    // prewarms during splash, but without racing the self-target root shell.
    LaunchedEffect(selfNeedsRestart) {
        val r = selfNeedsRestart ?: return@LaunchedEffect
        startupCoordinator.ensureInitialCaches(scope, r)
    }

    // Protection depends on the same root snapshot as Dashboard. Let
    // Dashboard own the initial root snapshot so a transient shell timeout
    // cannot make startup immediately do a second expensive retry. As soon
    // as that shared snapshot exists, TargetsCache parses it from memory and
    // Protection is still prewarmed before a normal tab switch.
    LaunchedEffect(selfNeedsRestart, rootSnapshot) {
        startupCoordinator.ensureProtectionCacheAfterRootSnapshot(scope, selfNeedsRestart, rootSnapshot)
    }

    // Hold the splash screen until the first Dashboard frame can render
    // with real content. Without this, the user sees splash → brief
    // selfNeedsRestart-null spinner → brief Dashboard state-null spinner
    // → content, with each spinner swap being visible flicker.
    val uiReady = startupCoordinator.isUiReady(dashboardState, dashboardError)
    var fullyDrawnReady by remember { mutableStateOf(false) }
    ReportDrawnWhen { fullyDrawnReady }
    LaunchedEffect(uiReady) {
        if (uiReady) {
            withFrameNanos { }
            fullyDrawnReady = true
            onReady()
        }
    }

    // Kick the update check once (silently) on first launch, and again
    // on ON_RESUME if it's been a while. Listener lives as long as
    // MainScreen is composed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    startupCoordinator.ensureUpdateFresh(scope)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(currentTab) {
        if (currentTab != Tab.Protection) {
            searchActive = false
            searchQuery = ""
        }
    }

    Scaffold(
        topBar = {
            if (searchActive && currentTab == Tab.Protection) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = {},
                    active = false,
                    onActiveChange = {},
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    leadingIcon = {
                        IconButton(onClick = {
                            searchActive = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {}
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    actions = {
                        // Refresh is contextual: Protection refreshes
                        // the app list, Dashboard refreshes the dashboard
                        // state + update check. Diagnostics has its own
                        // run buttons per-check, no top-bar refresh.
                        val refreshContext =
                            when (currentTab) {
                                Tab.Dashboard -> {
                                    RefreshContext(
                                        loading = dashboardLoading,
                                        onRefresh = {
                                            startupCoordinator.refreshDashboard(scope, refreshRestart)
                                        },
                                    )
                                }

                                Tab.Protection -> {
                                    RefreshContext(
                                        loading = appListLoading || targetsLoading,
                                        onRefresh = {
                                            startupCoordinator.refreshProtection(scope)
                                        },
                                    )
                                }

                                Tab.Diagnostics -> {
                                    null
                                }
                            }
                        refreshContext?.let { rc ->
                            IconButton(
                                onClick = rc.onRefresh,
                                enabled = !rc.loading,
                            ) {
                                if (rc.loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.action_refresh_apps),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                        if (currentTab == Tab.Protection) {
                            IconButton(onClick = { searchActive = true }) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Box {
                                val anyFilterActive = showSystem || showRussianOnly
                                // Active-filter indicator: the old `tint = primary`
                                // did not contrast reliably against the topbar's
                                // `primaryContainer` on Material You palettes where
                                // primary and primaryContainer end up close in tone.
                                // FilledIconButton paints itself with `primary` /
                                // `onPrimary`, a pair M3 guarantees to contrast,
                                // so the indicator reads on any dynamic theme.
                                if (anyFilterActive) {
                                    FilledIconButton(onClick = { showFilterMenu = true }) {
                                        Icon(
                                            Icons.Default.FilterList,
                                            contentDescription = null,
                                        )
                                    }
                                } else {
                                    IconButton(onClick = { showFilterMenu = true }) {
                                        Icon(
                                            Icons.Default.FilterList,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showFilterMenu,
                                    onDismissRequest = { showFilterMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.filter_show_system)) },
                                        onClick = { showSystem = !showSystem },
                                        leadingIcon = {
                                            Checkbox(
                                                checked = showSystem,
                                                onCheckedChange = null,
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.filter_russian_only)) },
                                        onClick = { showRussianOnly = !showRussianOnly },
                                        leadingIcon = {
                                            Checkbox(
                                                checked = showRussianOnly,
                                                onCheckedChange = null,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == Tab.Dashboard,
                    onClick = { currentTab = Tab.Dashboard },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_dashboard)) },
                )
                NavigationBarItem(
                    selected = currentTab == Tab.Protection,
                    onClick = { currentTab = Tab.Protection },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_protection)) },
                )
                NavigationBarItem(
                    selected = currentTab == Tab.Diagnostics,
                    onClick = { currentTab = Tab.Diagnostics },
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_diagnostics)) },
                )
            }
        },
    ) { innerPadding ->
        val restart = selfNeedsRestart
        val preparationError = selfTargetError
        if (preparationError != null) {
            RootPreparationErrorScreen(
                modifier = Modifier.padding(innerPadding),
                onRetry = { startupCoordinator.retrySelfTargets(scope) },
            )
        } else if (restart == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            when (currentTab) {
                Tab.Dashboard -> {
                    DashboardScreen(
                        selfNeedsRestart = restart,
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                Tab.Protection -> {
                    ProtectionScreen(
                        searchQuery = searchQuery,
                        showSystem = showSystem,
                        showRussianOnly = showRussianOnly,
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                Tab.Diagnostics -> {
                    DiagnosticsScreen(
                        selfNeedsRestart = restart,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun RootPreparationErrorScreen(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.self_targets_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.self_targets_error_message),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.vpn_off_retry))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootDeniedScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        titleContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.root_error_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.root_error_message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
