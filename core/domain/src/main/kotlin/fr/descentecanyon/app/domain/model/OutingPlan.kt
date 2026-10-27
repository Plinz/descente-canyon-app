package fr.descentecanyon.app.domain.model

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/** What the user chose for an outing, kept with it so its plan reopens as it was. */
data class OutingChoices(
    val date: LocalDate,
    val anchorTime: LocalTime,
    /** What [anchorTime] is: leaving the starting place when there is one, else arriving at the parking (see [withPlace]). */
    val anchor: OutingAnchor = OutingAnchor.ARRIVE_PARKING,
    /** The starting place (campsite, home), if any. */
    val placeId: Long? = null,
    val groupSize: Int = OutingSettings.SMALL_GROUP_MAX,
    val groupLevel: GroupLevel = GroupLevel.AUTONOMOUS,
    /** Null: as the sheet says (a shuttle when it gives its distance). */
    val withShuttle: Boolean? = null,
    val alertMarginHours: Int = DEFAULT_ALERT_MARGIN_HOURS,
    /** The alert pushed back during the outing, in minutes. */
    val alertDelayMinutes: Int = 0,
    /** Minutes added to (or taken from) a step for this outing. */
    val adjustments: Map<OutingPhase, Int> = emptyMap(),
    /** Ids of the gear already checked. */
    val checkedGear: Set<String> = emptySet(),
    /** The team meets at the starting place rather than at the parking. */
    val meetAtPlace: Boolean = false,
    /** Free text added to the messages. */
    val note: String = "",
) {
    /** From [placeId] (null: none): the time set is then leaving it, else arriving at the parking. */
    fun withPlace(placeId: Long?): OutingChoices =
        copy(placeId = placeId, anchor = if (placeId != null) OutingAnchor.LEAVE_PLACE else OutingAnchor.ARRIVE_PARKING)

    companion object {
        const val DEFAULT_ALERT_MARGIN_HOURS = 2
        val ALERT_MARGINS = listOf(1, 2, 3)
        const val ADJUSTMENT_STEP_MINUTES = 15
    }
}

/**
 * An outing the user planned. A draft ([tracked] false) is only kept; once the user turns the
 * follow-up on, the app follows it with notifications, and the relatives who received the plan know
 * when to worry.
 */
data class PlannedOuting(
    val canyonId: Int,
    val canyonName: String,
    /** Leaving the car on foot. */
    val start: Instant,
    /** Most likely return to the car. */
    val expectedBack: Instant,
    /** After this time without news, the relatives should call for help. */
    val alertAt: Instant,
    /** Return to the car if every step takes long: the end reminder comes then. */
    val latestBack: Instant = expectedBack,
    /** Leaving the starting place, when the drive is known. */
    val departure: Instant? = null,
    val groupSize: Int? = null,
    /** The alert time a relative was last sent, null if the plan went to no one: they wait for news at the return. */
    val informedAlertAt: Instant? = null,
    val choices: OutingChoices? = null,
    /** The user turned the follow-up on (notifications, reminders); a draft is only kept. */
    val tracked: Boolean = true,
    /** The stay the outing belongs to, if it was planned for one of its days. */
    val stayId: Long? = null,
    val id: Long = 0,
) {
    /** The plan went to a relative: they wait for news at the return. */
    val relativeInformed: Boolean get() = informedAlertAt != null

    /** The relative was told another alert time than the one planned now. */
    val alertChangedSinceInformed: Boolean get() = informedAlertAt != null && informedAlertAt != alertAt

    /** An outing whose alert time is long past is forgotten. */
    fun isStale(now: Instant): Boolean = now.isAfter(alertAt.plus(STALE_AFTER))

    /** From when the outing is followed: leaving the place, else a while before the walk (people leave earlier than planned). */
    val underwayFrom: Instant get() = listOfNotNull(departure, start.minus(EARLY_START)).min()

    /** Under way at [now]: followed, started or about to, and not forgotten. A draft is never under way. */
    fun isUnderway(now: Instant): Boolean = tracked && !now.isBefore(underwayFrom) && !isStale(now)

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(12)
        val EARLY_START: Duration = Duration.ofHours(2)
    }
}

/** The outing under way at [now]: the one started last when several are (a first one not ended). */
fun List<PlannedOuting>.underwayAt(now: Instant): PlannedOuting? = filter { it.isUnderway(now) }.maxByOrNull { it.start }

/** The followed outings still to come at [now], soonest first. */
fun List<PlannedOuting>.upcomingAt(now: Instant): List<PlannedOuting> =
    filter { it.tracked && now.isBefore(it.underwayFrom) }.sortedBy { it.start }

/** The drafts not forgotten at [now], soonest first. */
fun List<PlannedOuting>.draftsAt(now: Instant): List<PlannedOuting> = filter { !it.tracked && !it.isStale(now) }.sortedBy { it.start }

/** The outing under way, if any, the followed ones still to come and the drafts, soonest first. */
data class OutingAgenda(
    val underway: PlannedOuting? = null,
    val upcoming: List<PlannedOuting> = emptyList(),
    val drafts: List<PlannedOuting> = emptyList(),
)

/** The next moment after [now] when one of the outings gets under way or is forgotten. */
fun List<PlannedOuting>.nextChangeAfter(now: Instant): Instant? =
    flatMap { listOf(it.underwayFrom, it.alertAt.plus(PlannedOuting.STALE_AFTER)) }.filter { it.isAfter(now) }.minOrNull()

/**
 * The agenda of the outings, given again each time one of them gets under way or is forgotten, so
 * the screens follow the time without polling. Stops waiting when [clock] does not move (tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<PlannedOuting>>.agenda(clock: Clock): Flow<OutingAgenda> = transformLatest { outings ->
    var now = clock.instant()
    while (true) {
        emit(OutingAgenda(outings.underwayAt(now), outings.upcomingAt(now), outings.draftsAt(now)))
        val next = outings.nextChangeAfter(now) ?: break
        delay(Duration.between(now, next).toMillis().coerceAtLeast(1))
        val later = clock.instant()
        if (!later.isAfter(now)) break
        now = later
    }
}
