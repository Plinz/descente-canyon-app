package fr.descentecanyon.app.domain.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * The timing of the phone that shared an outing: its settings (gearing up, packing, coefficients of
 * the group) and the pace measured in its logbook. Everyone who adds the outing gets the same times
 * as the person who shared it, not times stretched by their own pace.
 */
data class SharedTiming(val settings: OutingSettings, val personalRatio: Double? = null) {
    val personalPace: PersonalPace? get() = personalRatio?.let { PersonalPace(it, PersonalPace.MIN_OUTINGS) }
}

/** A place an outing starts from, shared by name and position (a campsite, never a home unless the team meets there). */
data class SharedPlace(val name: String, val latitude: Double, val longitude: Double)

data class SharedNextCanyon(val canyonId: Int, val name: String, val link: CanyonLink, val adjustments: Map<OutingPhase, Int> = emptyMap())

/** What the sharing phone computed, shown as is to those who receive the link. */
data class SharedOutingSummary(
    val names: List<String> = emptyList(),
    val meet: LocalTime? = null,
    val walk: LocalTime? = null,
    val back: LocalTime? = null,
    val backLatest: LocalTime? = null,
)

/**
 * An outing as a link carries it: the canyons and their order, the day (none for a route shared to
 * be planned later), the meeting time, the group, the steps lengthened, the timing, the note. Not
 * shared: the starting place unless the team meets there, the gear checked, the alert and the relative.
 */
data class SharedOuting(
    val canyonId: Int,
    val next: List<SharedNextCanyon> = emptyList(),
    val date: LocalDate?,
    /** Arrival at the meeting parking, or leaving the place when the team meets there. */
    val anchorTime: LocalTime,
    val meetAtPlace: Boolean = false,
    /** The place the team meets at, only when it meets there. */
    val place: SharedPlace? = null,
    val groupSize: Int,
    val groupLevel: GroupLevel,
    val withShuttle: Boolean? = null,
    val adjustments: Map<OutingPhase, Int> = emptyMap(),
    val timing: SharedTiming? = null,
    val note: String = "",
    val summary: SharedOutingSummary = SharedOutingSummary(),
) {
    val canyonIds: List<Int> get() = listOf(canyonId) + next.map { it.canyonId }

    /** "Riolan + Aiglun", from the names the sharing phone gave. */
    val title: String get() = summary.names.joinToString(" + ")

    /** A route to plan on a day of one's choice. */
    val isRoute: Boolean get() = date == null

    /**
     * The choices of an outing added from this link on [day] (its own day when it has one), from
     * [placeId] when the team meets at the shared place: the times follow the sharing phone's.
     */
    fun toChoices(day: LocalDate, placeId: Long?, link: OutingLink?): OutingChoices = OutingChoices(
        date = date ?: day,
        anchorTime = anchorTime,
        anchor = if (meetAtPlace && placeId != null) OutingAnchor.LEAVE_PLACE else OutingAnchor.ARRIVE_PARKING,
        placeId = placeId.takeIf { meetAtPlace },
        groupSize = groupSize,
        groupLevel = groupLevel,
        withShuttle = withShuttle,
        adjustments = adjustments,
        meetAtPlace = meetAtPlace && placeId != null,
        note = note,
        nextCanyons = next.map { NextCanyon(it.canyonId, it.name, it.link, it.adjustments) },
        timing = timing,
        link = link,
    )

    /**
     * [choices] brought to this new version of the link: what the sharing phone decides changes,
     * what is this phone's own (gear checked, alert margin and delay, its starting place when the
     * team meets at the parking) stays.
     */
    fun update(choices: OutingChoices, placeId: Long?, link: OutingLink?): OutingChoices {
        val fresh = toChoices(choices.date, placeId, link)
        val ownPlace = if (fresh.meetAtPlace) fresh.placeId else choices.placeId
        return fresh.copy(
            placeId = ownPlace,
            anchor = if (fresh.meetAtPlace) OutingAnchor.LEAVE_PLACE else OutingAnchor.ARRIVE_PARKING,
            alertMarginHours = choices.alertMarginHours,
            alertDelayMinutes = choices.alertDelayMinutes,
            checkedGear = choices.checkedGear,
        )
    }

    companion object {
        const val MAX_NOTE = 500
    }
}

/** A stay as a link carries it: its place, its days and the outings planned on them. */
data class SharedStay(val place: SharedPlace, val start: LocalDate, val end: LocalDate, val outings: List<SharedOuting>)

/** What a shared link holds. */
sealed interface SharedContent {
    data class Outing(val outing: SharedOuting) : SharedContent

    data class Stay(val stay: SharedStay) : SharedContent

    data class CanyonList(val title: String, val canyonIds: List<Int>) : SharedContent
}

/**
 * The short link an outing was shared by ([isOwn]: this phone made it and can change it) or added
 * from, and the [version] of its content this outing matches.
 */
data class OutingLink(
    val code: String,
    val version: Int,
    val isOwn: Boolean,
    /** This phone's link: what it last published, to tell when the outing changed since. */
    val publishedFingerprint: String? = null,
)

/** A link made by the server: its code, address and version. */
data class PublishedShare(val code: String, val url: String, val version: Int)

/** A link read from the server. */
data class ReceivedShare(val code: String, val version: Int, val content: SharedContent)
