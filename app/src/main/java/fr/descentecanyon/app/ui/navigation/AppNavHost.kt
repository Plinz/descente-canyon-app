package fr.descentecanyon.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.ui.canyon.CanyonDetailScreen
import fr.descentecanyon.app.ui.canyon.CanyonPointsMapScreen
import fr.descentecanyon.app.ui.canyon.DebitPredictionInfoScreen
import fr.descentecanyon.app.ui.canyon.PhotoGalleryScreen
import fr.descentecanyon.app.ui.debit.DebitFormScreen
import fr.descentecanyon.app.ui.design.LocalDcMotion
import fr.descentecanyon.app.ui.favorites.FavoritesScreen
import fr.descentecanyon.app.ui.home.HomeScreen
import fr.descentecanyon.app.ui.interest.InterestRatingFormScreen
import fr.descentecanyon.app.ui.map.MapScreen
import fr.descentecanyon.app.ui.notifications.NotificationCenterScreen
import fr.descentecanyon.app.ui.outing.EmergencyScreen
import fr.descentecanyon.app.ui.outing.LogbookScreen
import fr.descentecanyon.app.ui.outing.OutingPlanScreen
import fr.descentecanyon.app.ui.pending.PendingDebitsScreen
import fr.descentecanyon.app.ui.search.SearchScreen
import fr.descentecanyon.app.ui.settings.SettingsScreen
import fr.descentecanyon.app.ui.users.UserProfileScreen
import fr.descentecanyon.app.ui.users.UserSearchScreen

/**
 * Every navigation started from a click goes through [dropUnlessResumed]: while a screen enters or
 * leaves it is not resumed, so a second tap on "back" cannot pop the start destination (blank
 * screen) and a second tap on a link cannot push the same screen twice.
 */
@Composable
fun AppNavHost(navController: NavHostController, topLevelContentPadding: PaddingValues = PaddingValues()) {
    val motion = LocalDcMotion.current
    val screenTransition = tween<Float>(durationMillis = motion.screenTransitionMillis, easing = motion.easing)
    NavHost(
        navController = navController,
        startDestination = Screen.Home,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        // The default fade lasts 700 ms: short transitions keep the app responsive.
        enterTransition = { fadeIn(screenTransition) },
        exitTransition = { fadeOut(screenTransition) },
        popEnterTransition = { fadeIn(screenTransition) },
        popExitTransition = { fadeOut(screenTransition) },
    ) {
        composable<Screen.Home>(
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            HomeScreen(
                onCanyonClick = dropUnlessResumedWith { canyonId: Int ->
                    navController.navigateSingleTop(Screen.CanyonDetail(canyonId))
                },
                onQuickSearchClick = dropUnlessResumed {
                    navController.navigateSingleTop(Screen.Search)
                },
                onMapClick = dropUnlessResumed {
                    navController.navigateSingleTop(Screen.Map)
                },
                onNotificationsClick = dropUnlessResumed {
                    navController.navigateSingleTop(Screen.Notifications)
                },
                onUsersClick = dropUnlessResumed {
                    navController.navigateSingleTop(Screen.UserSearch)
                },
                onPendingDebitsClick = dropUnlessResumed {
                    navController.navigateSingleTop(Screen.PendingDebits)
                },
                onOutingClick = dropUnlessResumedWith { canyonId: Int -> navController.navigateSingleTop(Screen.OutingPlan(canyonId)) },
                // Back from the outing: the flow seen is the most useful report.
                onOutingEnded = dropUnlessResumedWith { canyonId: Int -> navController.navigateSingleTop(Screen.DebitForm(canyonId)) },
                onEmergencyClick = dropUnlessResumedWith { canyonId: Int -> navController.navigateSingleTop(Screen.Emergency(canyonId)) },
                onSettingsClick = dropUnlessResumed {
                    navController.navigateSingleTop(Screen.Settings)
                },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.Search>(
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            SearchScreen(
                onCanyonClick = dropUnlessResumedWith { canyonId: Int ->
                    navController.navigateSingleTop(Screen.CanyonDetail(canyonId))
                },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.Map>(
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            MapScreen(
                onCanyonClick = dropUnlessResumedWith { canyonId: Int ->
                    navController.navigateSingleTop(Screen.CanyonDetail(canyonId))
                },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.Favorites>(
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            FavoritesScreen(
                onCanyonClick = dropUnlessResumedWith { canyonId: Int ->
                    navController.navigateSingleTop(Screen.CanyonDetail(canyonId))
                },
                onLogbookClick = dropUnlessResumed { navController.navigateSingleTop(Screen.Logbook) },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.CanyonDetail> { backStackEntry ->
            val detail = backStackEntry.toRoute<Screen.CanyonDetail>()
            val refreshDebitsAfterSubmission by backStackEntry.savedStateHandle
                .getStateFlow(DEBIT_SUBMISSION_REFRESH_KEY, false)
                .collectAsStateWithLifecycle()
            val refreshDetailAfterInterestRating by backStackEntry.savedStateHandle
                .getStateFlow(INTEREST_RATING_REFRESH_KEY, false)
                .collectAsStateWithLifecycle()
            CanyonDetailScreen(
                canyonId = detail.canyonId,
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onReportDebitClick = dropUnlessResumed { navController.navigateSingleTop(Screen.DebitForm(detail.canyonId)) },
                onRateInterestClick = dropUnlessResumed { navController.navigateSingleTop(Screen.InterestRatingForm(detail.canyonId)) },
                onShowMapClick = dropUnlessResumed { navController.navigateSingleTop(Screen.CanyonPointsMap(detail.canyonId)) },
                onOpenPredictionInfo = dropUnlessResumed {
                    navController.navigateSingleTop(Screen.DebitPredictionInfo)
                },
                onOpenPhotoGallery = dropUnlessResumedWith { photoId: Long ->
                    navController.navigateSingleTop(Screen.PhotoGallery(detail.canyonId, photoId))
                },
                onUserClick = dropUnlessResumedWith { username: String ->
                    navController.navigateSingleTop(Screen.UserProfile(username.normalizeForSearch()))
                },
                // Not single top: another canyon is pushed so "back" returns to this one.
                onOpenCanyon = dropUnlessResumedWith { canyonId: Int -> navController.navigate(Screen.CanyonDetail(canyonId)) },
                onPlanOuting = dropUnlessResumed { navController.navigateSingleTop(Screen.OutingPlan(detail.canyonId)) },
                onOpenEmergency = dropUnlessResumed { navController.navigateSingleTop(Screen.Emergency(detail.canyonId)) },
                openDebitsTabInitially = detail.openDebitsTab,
                contentPadding = topLevelContentPadding,
                refreshDebitsAfterSubmission = refreshDebitsAfterSubmission,
                onRefreshDebitsAfterSubmissionHandled = {
                    backStackEntry.savedStateHandle[DEBIT_SUBMISSION_REFRESH_KEY] = false
                },
                refreshDetailAfterInterestRating = refreshDetailAfterInterestRating,
                onRefreshDetailAfterInterestRatingHandled = {
                    backStackEntry.savedStateHandle[INTEREST_RATING_REFRESH_KEY] = false
                },
            )
        }

        composable<Screen.Notifications>(
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            NotificationCenterScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onCanyonClick = dropUnlessResumedWith { canyonId: Int ->
                    navController.navigateSingleTop(Screen.CanyonDetail(canyonId, openDebitsTab = true))
                },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.UserSearch> {
            UserSearchScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onUserClick = dropUnlessResumedWith { username: String -> navController.navigateSingleTop(Screen.UserProfile(username)) },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.UserProfile> {
            UserProfileScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.PhotoGallery> {
            PhotoGalleryScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onClose = { navController.closeIfCurrent<Screen.PhotoGallery>() },
            )
        }

        composable<Screen.CanyonPointsMap>(
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.CanyonPointsMap>()
            CanyonPointsMapScreen(
                canyonId = route.canyonId,
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onOpenEmergency = dropUnlessResumed { navController.navigateSingleTop(Screen.Emergency(route.canyonId)) },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.DebitForm> {
            DebitFormScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onSubmissionSuccess = {
                    navController.closeIfCurrent<Screen.DebitForm> { canyon ->
                        canyon?.savedStateHandle?.set(DEBIT_SUBMISSION_REFRESH_KEY, true)
                    }
                },
                onClose = { navController.closeIfCurrent<Screen.DebitForm>() },
                onOpenPendingDebits = dropUnlessResumed { navController.navigateSingleTop(Screen.PendingDebits) },
            )
        }

        composable<Screen.Settings> {
            SettingsScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onOpenPendingDebits = dropUnlessResumed { navController.navigateSingleTop(Screen.PendingDebits) },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.OutingPlan> {
            OutingPlanScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onOpenEmergency = dropUnlessResumedWith { canyonId: Int -> navController.navigateSingleTop(Screen.Emergency(canyonId)) },
                onReportFlow = dropUnlessResumedWith { canyonId: Int -> navController.navigateSingleTop(Screen.DebitForm(canyonId)) },
            )
        }

        composable<Screen.Logbook> {
            LogbookScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onCanyonClick = dropUnlessResumedWith { canyonId: Int -> navController.navigateSingleTop(Screen.CanyonDetail(canyonId)) },
            )
        }

        composable<Screen.Emergency> {
            EmergencyScreen(onBackClick = dropUnlessResumed { navController.popBackStack() })
        }

        composable<Screen.PendingDebits> {
            PendingDebitsScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onCanyonClick = dropUnlessResumedWith { canyonId: Int -> navController.navigateSingleTop(Screen.CanyonDetail(canyonId)) },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.InterestRatingForm> {
            InterestRatingFormScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                onSubmissionSuccess = {
                    navController.closeIfCurrent<Screen.InterestRatingForm> { canyon ->
                        canyon?.savedStateHandle?.set(INTEREST_RATING_REFRESH_KEY, true)
                    }
                },
            )
        }

        composable<Screen.DebitPredictionInfo> {
            DebitPredictionInfoScreen(
                onBackClick = dropUnlessResumed { navController.popBackStack() },
                contentPadding = topLevelContentPadding,
            )
        }
    }
}

/**
 * Closes the screen [T] once its work is done (report sent, nothing to show). Not a click: it must
 * run even while the screen is not resumed, but never when the user already left it, where popping
 * would close another screen. [beforeClose] receives the screen below.
 */
private inline fun <reified T : Screen> NavHostController.closeIfCurrent(beforeClose: (NavBackStackEntry?) -> Unit = {}) {
    if (currentBackStackEntry?.destination?.hasRoute(T::class) != true) return
    beforeClose(previousBackStackEntry)
    popBackStack()
}

/** [dropUnlessResumed] for a click that carries a value (the canyon or the photo clicked). */
@Composable
private fun <T> dropUnlessResumedWith(block: (T) -> Unit): (T) -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return { value -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block(value) }
}

private const val DEBIT_SUBMISSION_REFRESH_KEY = "debit_submission_refresh"
private const val INTEREST_RATING_REFRESH_KEY = "interest_rating_refresh"

internal fun NavHostController.navigateSingleTop(screen: Screen) {
    navigate(screen) {
        applySingleTopNavigation()
    }
}

internal fun NavOptionsBuilder.applySingleTopNavigation() {
    launchSingleTop = true
}
