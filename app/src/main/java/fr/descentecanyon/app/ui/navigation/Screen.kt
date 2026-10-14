package fr.descentecanyon.app.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for the app.
 */
sealed interface Screen {

    @Serializable
    data object Home : Screen

    @Serializable
    data object Search : Screen

    @Serializable
    data object Map : Screen

    @Serializable
    data object Favorites : Screen

    @Serializable
    data object Notifications : Screen

    @Serializable
    data object UserSearch : Screen

    @Serializable
    data class UserProfile(val normalizedUsername: String) : Screen

    @Serializable
    data class CanyonDetail(val canyonId: Int, val openDebitsTab: Boolean = false) : Screen

    @Serializable
    data class DebitForm(val canyonId: Int) : Screen

    @Serializable
    data class InterestRatingForm(val canyonId: Int) : Screen

    @Serializable
    data class PhotoGallery(val canyonId: Int, val initialPhotoId: Long) : Screen

    @Serializable
    data class CanyonPointsMap(val canyonId: Int) : Screen

    @Serializable
    data object DebitPredictionInfo : Screen
}
