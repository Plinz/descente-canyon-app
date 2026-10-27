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

    /** A member of the site, by account name exactly as the site shows it. */
    @Serializable
    data class UserProfile(val username: String) : Screen

    @Serializable
    data class CanyonDetail(val canyonId: Int, val openDebitsTab: Boolean = false) : Screen

    /** A flow report; an outing just logged prefills its [date] (ISO), [descended] and flow [level] (NiveauDebit name). */
    @Serializable
    data class DebitForm(val canyonId: Int, val date: String? = null, val descended: Boolean = false, val level: String? = null) : Screen

    @Serializable
    data class InterestRatingForm(val canyonId: Int) : Screen

    @Serializable
    data class PhotoGallery(val canyonId: Int, val initialPhotoId: Long) : Screen

    @Serializable
    data class CanyonPointsMap(val canyonId: Int) : Screen

    @Serializable
    data object DebitPredictionInfo : Screen

    @Serializable
    data object PendingDebits : Screen

    @Serializable
    data object Settings : Screen

    /**
     * The plan of an outing: kept ([outingId]), else new, on [date] (ISO) from the place [placeId] when
     * given, for a day of the stay [stayId]; [startNow] turns its follow-up on at once ("I'm leaving now").
     */
    @Serializable
    data class OutingPlan(
        val canyonId: Int,
        val outingId: Long = 0,
        val date: String? = null,
        val placeId: Long = 0,
        val stayId: Long = 0,
        val startNow: Boolean = false,
    ) : Screen

    /** The outings planned, the stays and the drafts. */
    @Serializable
    data object Outings : Screen

    @Serializable
    data object Places : Screen

    /** The coefficients and times of outings. */
    @Serializable
    data object OutingSettings : Screen

    /** A stay day by day: the weather at its place and the outing of each day. */
    @Serializable
    data class Stay(val stayId: Long) : Screen

    /** A new stay ([stayId] 0) or a stay changed: its place and its days. */
    @Serializable
    data class StayEdit(val stayId: Long = 0) : Screen

    /** The canyons around the place of the stay [stayId], for its day [date] (ISO). */
    @Serializable
    data class StayCanyonPicker(val stayId: Long, val date: String) : Screen

    /** The logbook, opened on [tab] (LogbookTab ordinal: outings, canyons, figures). */
    @Serializable
    data class Logbook(val tab: Int = 0) : Screen

    /** The user space: account, logbook, memos, contributions, follows and data. */
    @Serializable
    data object MyAccount : Screen

    /** Emergency numbers, position and escape routes; [canyonId] 0 when opened outside a canyon. */
    @Serializable
    data class Emergency(val canyonId: Int = 0) : Screen
}
