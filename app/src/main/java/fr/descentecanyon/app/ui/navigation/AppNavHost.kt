package fr.descentecanyon.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@Composable
fun AppNavHost(navController: NavHostController, topLevelContentPadding: PaddingValues = PaddingValues()) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        composable<Screen.Home>(
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            HomeScreen(
                onCanyonClick = { canyonId ->
                    navController.navigateSingleTop(Screen.CanyonDetail(canyonId))
                },
                onQuickSearchClick = {
                    navController.navigateSingleTop(Screen.Search)
                },
                onMapClick = {
                    navController.navigateSingleTop(Screen.Map)
                },
                onNotificationsClick = {
                    navController.navigateSingleTop(Screen.Notifications)
                },
                onUsersClick = {
                    navController.navigateSingleTop(Screen.UserSearch)
                },
                onPendingDebitsClick = {
                    navController.navigateSingleTop(Screen.PendingDebits)
                },
                onOutingClick = { canyonId -> navController.navigateSingleTop(Screen.OutingPlan(canyonId)) },
                // Back from the outing: the flow seen is the most useful report.
                onOutingEnded = { canyonId -> navController.navigateSingleTop(Screen.DebitForm(canyonId)) },
                onEmergencyClick = { canyonId -> navController.navigateSingleTop(Screen.Emergency(canyonId)) },
                onSettingsClick = {
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
                onCanyonClick = { canyonId ->
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
                onCanyonClick = { canyonId ->
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
                onCanyonClick = { canyonId ->
                    navController.navigateSingleTop(Screen.CanyonDetail(canyonId))
                },
                onLogbookClick = { navController.navigateSingleTop(Screen.Logbook) },
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
                onBackClick = { navController.popBackStack() },
                onReportDebitClick = { navController.navigateSingleTop(Screen.DebitForm(detail.canyonId)) },
                onRateInterestClick = { navController.navigateSingleTop(Screen.InterestRatingForm(detail.canyonId)) },
                onShowMapClick = { navController.navigateSingleTop(Screen.CanyonPointsMap(detail.canyonId)) },
                onOpenPredictionInfo = {
                    navController.navigateSingleTop(Screen.DebitPredictionInfo)
                },
                onOpenPhotoGallery = { photoId ->
                    navController.navigateSingleTop(Screen.PhotoGallery(detail.canyonId, photoId))
                },
                onUserClick = { username -> navController.navigateSingleTop(Screen.UserProfile(username.normalizeForSearch())) },
                // Not single top: another canyon is pushed so "back" returns to this one.
                onOpenCanyon = { canyonId -> navController.navigate(Screen.CanyonDetail(canyonId)) },
                onPlanOuting = { navController.navigateSingleTop(Screen.OutingPlan(detail.canyonId)) },
                onOpenEmergency = { navController.navigateSingleTop(Screen.Emergency(detail.canyonId)) },
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
                onBackClick = { navController.popBackStack() },
                onCanyonClick = { canyonId ->
                    navController.navigateSingleTop(Screen.CanyonDetail(canyonId, openDebitsTab = true))
                },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.UserSearch> {
            UserSearchScreen(
                onBackClick = { navController.popBackStack() },
                onUserClick = { username -> navController.navigateSingleTop(Screen.UserProfile(username)) },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.UserProfile> {
            UserProfileScreen(
                onBackClick = { navController.popBackStack() },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.PhotoGallery> {
            PhotoGalleryScreen(
                onBackClick = { navController.popBackStack() },
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
                onBackClick = { navController.popBackStack() },
                onOpenEmergency = { navController.navigateSingleTop(Screen.Emergency(route.canyonId)) },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.DebitForm> {
            DebitFormScreen(
                onBackClick = { navController.popBackStack() },
                onSubmissionSuccess = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(DEBIT_SUBMISSION_REFRESH_KEY, true)
                    navController.popBackStack()
                },
                onOpenPendingDebits = { navController.navigateSingleTop(Screen.PendingDebits) },
            )
        }

        composable<Screen.Settings> {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onOpenPendingDebits = { navController.navigateSingleTop(Screen.PendingDebits) },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.OutingPlan> {
            OutingPlanScreen(
                onBackClick = { navController.popBackStack() },
                onOpenEmergency = { canyonId -> navController.navigateSingleTop(Screen.Emergency(canyonId)) },
                onReportFlow = { canyonId -> navController.navigateSingleTop(Screen.DebitForm(canyonId)) },
            )
        }

        composable<Screen.Logbook> {
            LogbookScreen(
                onBackClick = { navController.popBackStack() },
                onCanyonClick = { canyonId -> navController.navigateSingleTop(Screen.CanyonDetail(canyonId)) },
            )
        }

        composable<Screen.Emergency> {
            EmergencyScreen(onBackClick = { navController.popBackStack() })
        }

        composable<Screen.PendingDebits> {
            PendingDebitsScreen(
                onBackClick = { navController.popBackStack() },
                onCanyonClick = { canyonId -> navController.navigateSingleTop(Screen.CanyonDetail(canyonId)) },
                contentPadding = topLevelContentPadding,
            )
        }

        composable<Screen.InterestRatingForm> {
            InterestRatingFormScreen(
                onBackClick = { navController.popBackStack() },
                onSubmissionSuccess = {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(INTEREST_RATING_REFRESH_KEY, true)
                    navController.popBackStack()
                },
            )
        }

        composable<Screen.DebitPredictionInfo> {
            DebitPredictionInfoScreen(
                onBackClick = { navController.popBackStack() },
                contentPadding = topLevelContentPadding,
            )
        }
    }
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
