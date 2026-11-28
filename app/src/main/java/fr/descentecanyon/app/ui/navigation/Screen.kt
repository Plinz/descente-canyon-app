package fr.descentecanyon.app.ui.navigation

import fr.descentecanyon.app.domain.model.CanyonSection
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for the app.
 */
sealed interface Screen {

    @Serializable
    data object Home : Screen

    /** Explorer: the search of the catalog, as a list or on a map. */
    @Serializable
    data object Search : Screen

    @Serializable
    data object Favorites : Screen

    @Serializable
    data object Notifications : Screen

    @Serializable
    data object UserSearch : Screen

    /** A member of the site, by account name exactly as the site shows it. */
    @Serializable
    data class UserProfile(val username: String) : Screen

    /**
     * A canyon page. Opened from the search of a day of a stay ([stayId], [stayDate] ISO, and
     * [stayOutingId] the outing whose canyon it replaces, 0 for none), it plans the canyon for that day.
     */
    @Serializable
    data class CanyonDetail(
        val canyonId: Int,
        val openDebitsTab: Boolean = false,
        val stayId: Long = 0,
        val stayDate: String? = null,
        val stayOutingId: Long = 0,
        /** The [fr.descentecanyon.app.domain.model.CanyonSection] path a link opens ("conditions"...). */
        val section: String? = null,
    ) : Screen {
        val initialSection: CanyonSection?
            get() = section?.let(CanyonSection::ofPath) ?: CanyonSection.DEBITS.takeIf { openDebitsTab }
    }

    /** A flow report; an outing already logged prefills its [date] (ISO), nothing else. */
    @Serializable
    data class DebitForm(val canyonId: Int, val date: String? = null) : Screen

    @Serializable
    data class InterestRatingForm(val canyonId: Int) : Screen

    /**
     * A canyon proposed for review: new ([canyonId] 0), named [name] when it was searched for in vain,
     * or a change to the app's canyon [canyonId].
     */
    @Serializable
    data class CanyonProposal(val canyonId: Int = 0, val name: String? = null) : Screen

    @Serializable
    data class PhotoGallery(val canyonId: Int, val initialPhotoId: Long) : Screen

    /** The map of a canyon, on the point nearest to [focus] ("lat,lon") or on the shared track [trackId], from a link. */
    @Serializable
    data class CanyonPointsMap(val canyonId: Int, val focus: String? = null, val trackId: String? = null) : Screen

    @Serializable
    data object DebitPredictionInfo : Screen

    @Serializable
    data object PendingDebits : Screen

    /** The idea box: a bug, an idea or a change, and the answers to the earlier messages. */
    @Serializable
    data object Feedback : Screen

    @Serializable
    data object Settings : Screen

    /**
     * The plan of an outing: planned ([outingId], given the canyon [canyonId] instead when it differs),
     * else new, on [date] (ISO) from the place [placeId] when given; for a day of the stay [stayId] it
     * is planned at once.
     */
    @Serializable
    data class OutingPlan(
        val canyonId: Int,
        val outingId: Long = 0,
        val date: String? = null,
        val placeId: Long = 0,
        val stayId: Long = 0,
        /** An outing from someone's link (packed), its short code and version; added at once when [sharedPlan]. */
        val sharedPayload: String? = null,
        val sharedCode: String? = null,
        val sharedVersion: Int = 0,
        val sharedPlan: Boolean = false,
    ) : Screen

    /** A link someone shared: by its short [code], or an outing the link carries whole ([payload]). */
    @Serializable
    data class SharedLink(val code: String? = null, val payload: String? = null) : Screen

    /** The Sorties tab: the outings planned, the stays and the logbook. */
    @Serializable
    data object Outings : Screen

    /** The search, to choose the canyon of a new outing ([outingPick] is read by the search). */
    @Serializable
    data class OutingCanyonPicker(val outingPick: Boolean = true) : Screen

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

    /** The search, around the place of the stay [stayId], for its day [date] (ISO), or in place of the canyon of [outingId]. */
    @Serializable
    data class StayCanyonPicker(val stayId: Long, val date: String, val outingId: Long = 0) : Screen

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
