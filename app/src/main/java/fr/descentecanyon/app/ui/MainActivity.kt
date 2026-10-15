package fr.descentecanyon.app.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.ThemeMode
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.startup.AppStartupCoordinator
import fr.descentecanyon.app.startup.StartupLaunchMode
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.navigation.AppLaunchTarget
import fr.descentecanyon.app.ui.navigation.AppNavHost
import fr.descentecanyon.app.ui.navigation.BottomNavItem
import fr.descentecanyon.app.ui.navigation.LaunchSection
import fr.descentecanyon.app.ui.navigation.Screen
import fr.descentecanyon.app.ui.navigation.consumeLaunchTarget
import fr.descentecanyon.app.ui.navigation.navigateSingleTop
import fr.descentecanyon.app.ui.test.TestTags
import fr.descentecanyon.app.ui.theme.DescenteCanyonTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appStartupCoordinator: AppStartupCoordinator

    @Inject lateinit var preferencesRepository: PreferencesRepository

    private val incomingLaunchTargets = MutableSharedFlow<AppLaunchTarget>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PerformanceTrace.logEvent(
            event = "main_activity_created",
            "isColdStart" to (savedInstanceState == null),
        )
        enableEdgeToEdge()
        if (savedInstanceState == null) AppShortcuts.publish(this)
        val initialLaunchTarget = consumeLaunchTarget(intent)
        setContent {
            val themeMode by preferencesRepository.observeThemeMode()
                .collectAsStateWithLifecycle(initialValue = preferencesRepository.getThemeMode())
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // System bar icons follow the app theme, which can differ from the system one.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_NAVIGATION_SCRIM, DARK_NAVIGATION_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            DescenteCanyonTheme(darkTheme = darkTheme) {
                AppStartupScreen(
                    appStartupCoordinator = appStartupCoordinator,
                    initialLaunchTarget = initialLaunchTarget,
                    incomingLaunchTargets = incomingLaunchTargets,
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val launchTarget = consumeLaunchTarget(intent)
        if (launchTarget != AppLaunchTarget.None) {
            incomingLaunchTargets.tryEmit(launchTarget)
        }
    }
}

@Composable
private fun AppStartupScreen(
    appStartupCoordinator: AppStartupCoordinator,
    initialLaunchTarget: AppLaunchTarget,
    incomingLaunchTargets: MutableSharedFlow<AppLaunchTarget>,
) {
    val retryVersion = remember { mutableIntStateOf(0) }
    val startupState by produceState<StartupUiState>(
        initialValue = StartupUiState.Resolving,
        key1 = appStartupCoordinator,
        key2 = retryVersion.intValue,
    ) {
        value = suspendRunCatching {
            when (appStartupCoordinator.resolveLaunchMode()) {
                StartupLaunchMode.NON_BLOCKING -> StartupUiState.AppVisible(isBackgroundInitializing = true)

                StartupLaunchMode.BLOCKING_IMPORT -> {
                    PerformanceTrace.logEvent("startup_blocking_ui_required")
                    runStartupInitialization(appStartupCoordinator)
                    StartupUiState.AppVisible(isBackgroundInitializing = false)
                }
            }
        }.getOrElse { throwable ->
            Log.e(TAG, "Startup initialization failed", throwable)
            StartupUiState.Error
        }
    }

    when (val state = startupState) {
        StartupUiState.Resolving -> {
            LaunchedEffect(Unit) {
                PerformanceTrace.logEvent("startup_loading_ui_visible")
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is StartupUiState.AppVisible -> {
            if (state.isBackgroundInitializing) {
                LaunchedEffect(appStartupCoordinator) {
                    suspendRunCatching {
                        runStartupInitialization(appStartupCoordinator)
                    }.onFailure { throwable ->
                        PerformanceTrace.logEvent(
                            event = "startup_background_initialize_failed",
                            "error" to (throwable.message ?: throwable::class.simpleName),
                        )
                    }
                }
            }
            MainScreen(
                initialLaunchTarget = initialLaunchTarget,
                incomingLaunchTargets = incomingLaunchTargets,
            )
        }

        StartupUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.startup_initialization_error))
                    Text(stringResource(R.string.startup_initialization_error_body))
                    Button(onClick = { retryVersion.intValue += 1 }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}

private sealed interface StartupUiState {
    data object Resolving : StartupUiState
    data class AppVisible(val isBackgroundInitializing: Boolean) : StartupUiState
    data object Error : StartupUiState
}

private const val TAG = "MainActivity"

// Default scrims of enableEdgeToEdge() for 3-button navigation bars (ARGB).
private const val LIGHT_NAVIGATION_SCRIM = 0xE6FFFFFF.toInt()
private const val DARK_NAVIGATION_SCRIM = 0x801B1B1B.toInt()

@Composable
private fun MainScreen(initialLaunchTarget: AppLaunchTarget, incomingLaunchTargets: MutableSharedFlow<AppLaunchTarget>) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        PerformanceTrace.logEvent("main_screen_visible")
    }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val selectedBottomNavItem = resolveSelectedBottomNavItem(navBackStackEntry)
    val showBottomBar = shouldShowBottomBar(navBackStackEntry)
    val dcColors = LocalDcColors.current
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(navController, initialLaunchTarget, incomingLaunchTargets) {
        fun navigateTo(target: AppLaunchTarget) {
            when (target) {
                AppLaunchTarget.None -> Unit

                is AppLaunchTarget.Notifications -> {
                    if (target.clearBackStack) {
                        navController.navigate(Screen.Notifications) {
                            applyAppRootNavigation(navController.graph.findStartDestination().id)
                        }
                    } else {
                        navController.navigateSingleTop(Screen.Notifications)
                    }
                }

                is AppLaunchTarget.Section -> {
                    AppShortcuts.reportUsed(context, target.section)
                    val screen = target.section.toScreen()
                    if (target.clearBackStack) {
                        navController.navigate(screen) {
                            applyAppRootNavigation(navController.graph.findStartDestination().id)
                        }
                    } else {
                        navController.navigateSingleTop(screen)
                    }
                }

                is AppLaunchTarget.CanyonDetail -> {
                    if (target.clearBackStack) {
                        navController.navigate(Screen.CanyonDetail(target.canyonId, target.openDebitsTab)) {
                            applyAppRootNavigation(navController.graph.findStartDestination().id)
                        }
                    } else {
                        navController.navigateSingleTop(
                            Screen.CanyonDetail(
                                canyonId = target.canyonId,
                                openDebitsTab = target.openDebitsTab,
                            ),
                        )
                    }
                }
            }
        }

        navigateTo(initialLaunchTarget)
        incomingLaunchTargets.collect(::navigateTo)
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Surface(
                    color = dcColors.surfaceOverlay,
                    contentColor = dcColors.textPrimary,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, dcColors.borderSubtle),
                ) {
                    NavigationBar(
                        modifier = Modifier.background(
                            Brush.horizontalGradient(
                                listOf(
                                    dcColors.waterDeep.copy(alpha = 0.26f),
                                    dcColors.surfaceRaised,
                                    dcColors.rock.copy(alpha = 0.16f),
                                ),
                            ),
                        ),
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        tonalElevation = 0.dp,
                        windowInsets = NavigationBarDefaults.windowInsets,
                    ) {
                        BottomNavItem.entries.forEach { item ->
                            val itemLabel = stringResource(item.labelResId)
                            NavigationBarItem(
                                modifier = Modifier.testTag(TestTags.bottomNavItem(item.name)),
                                icon = { Icon(item.icon, contentDescription = itemLabel) },
                                label = { Text(itemLabel) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = dcColors.primaryActionContent,
                                    selectedTextColor = dcColors.textPrimary,
                                    indicatorColor = dcColors.primaryAction.copy(alpha = 0.92f),
                                    unselectedIconColor = dcColors.textMuted,
                                    unselectedTextColor = dcColors.textMuted,
                                ),
                                selected = item == selectedBottomNavItem,
                                onClick = {
                                    item.launchSection()?.let { section -> AppShortcuts.reportUsed(context, section) }
                                    val startDestinationId = navController.graph.findStartDestination().id
                                    handleBottomNavClick(
                                        item = item,
                                        popToHome = {
                                            navController.popBackStack(startDestinationId, false)
                                        },
                                        navigate = { screen ->
                                            navController.navigate(screen) {
                                                applyAppRootNavigation(startDestinationId)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            topLevelContentPadding = innerPadding,
        )
    }
}

internal fun BottomNavItem.launchSection(): LaunchSection? = when (this) {
    BottomNavItem.HOME -> null
    BottomNavItem.SEARCH -> LaunchSection.SEARCH
    BottomNavItem.MAP -> LaunchSection.MAP
    BottomNavItem.FAVORITES -> LaunchSection.FAVORITES
}

internal fun LaunchSection.toScreen(): Screen = when (this) {
    LaunchSection.SEARCH -> Screen.Search
    LaunchSection.MAP -> Screen.Map
    LaunchSection.FAVORITES -> Screen.Favorites
    LaunchSection.PENDING_DEBITS -> Screen.PendingDebits
}

internal fun resolveSelectedBottomNavItem(backStackEntry: NavBackStackEntry?): BottomNavItem? {
    val destination = backStackEntry?.destination ?: return null
    return when {
        destination.hasRoute(Screen.Home::class) -> BottomNavItem.HOME
        destination.hasRoute(Screen.Search::class) -> BottomNavItem.SEARCH
        destination.hasRoute(Screen.Map::class) -> BottomNavItem.MAP
        destination.hasRoute(Screen.Favorites::class) -> BottomNavItem.FAVORITES
        else -> null
    }
}

internal fun selectedBottomNavItemForScreen(screen: Screen): BottomNavItem? = when (screen) {
    Screen.Home -> BottomNavItem.HOME
    Screen.Search -> BottomNavItem.SEARCH
    Screen.Map -> BottomNavItem.MAP
    Screen.Favorites -> BottomNavItem.FAVORITES
    else -> null
}

internal fun shouldShowBottomBar(backStackEntry: NavBackStackEntry?): Boolean {
    val destination = backStackEntry?.destination ?: return false
    return destination.hasRoute(Screen.Home::class) ||
        destination.hasRoute(Screen.Search::class) ||
        destination.hasRoute(Screen.Map::class) ||
        destination.hasRoute(Screen.Favorites::class) ||
        destination.hasRoute(Screen.CanyonDetail::class)
}

internal fun handleBottomNavClick(item: BottomNavItem, popToHome: () -> Boolean, navigate: (Screen) -> Unit) {
    if (item == BottomNavItem.HOME && popToHome()) {
        return
    }

    navigate(item.screen)
}

internal fun NavOptionsBuilder.applyAppRootNavigation(startDestinationId: Int) {
    popUpTo(startDestinationId)
    launchSingleTop = true
}

private const val STARTUP_INITIALIZE_TRACE_KEY = "startup.initialize"

private suspend fun runStartupInitialization(appStartupCoordinator: AppStartupCoordinator) {
    PerformanceTrace.start(key = STARTUP_INITIALIZE_TRACE_KEY, event = "startup_initialize")
    try {
        appStartupCoordinator.initialize()
        PerformanceTrace.end(key = STARTUP_INITIALIZE_TRACE_KEY, outcome = "ready")
    } catch (throwable: Throwable) {
        PerformanceTrace.end(
            key = STARTUP_INITIALIZE_TRACE_KEY,
            outcome = "failed",
            "error" to (throwable.message ?: throwable::class.simpleName),
        )
        throw throwable
    }
}
