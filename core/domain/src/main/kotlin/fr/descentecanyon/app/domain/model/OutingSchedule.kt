package fr.descentecanyon.app.domain.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A duration known within a range, in minutes: shortest, most likely and longest. */
data class TimeSpan(val shortest: Long, val likely: Long, val longest: Long) {
    init {
        require(shortest in 0..likely && likely <= longest) { "Invalid time span $shortest/$likely/$longest" }
    }

    operator fun plus(other: TimeSpan) = TimeSpan(shortest + other.shortest, likely + other.likely, longest + other.longest)

    /** Lengthened, or shortened when [minutes] is negative, never below zero. */
    fun adjustedBy(minutes: Int): TimeSpan = if (minutes == 0) {
        this
    } else {
        TimeSpan((shortest + minutes).coerceAtLeast(0), (likely + minutes).coerceAtLeast(0), (longest + minutes).coerceAtLeast(0))
    }

    companion object {
        val ZERO = TimeSpan(0, 0, 0)

        fun exactly(minutes: Long) = TimeSpan(minutes, minutes, minutes)

        /**
         * A duration of a sheet stretched by [factor]: the middle of its range is the likely time,
         * its ends widened by [earlierPercent] and [laterPercent] give the shortest and longest ones.
         * Most sheets give a single value ("2h"): the margins make it a range.
         */
        fun fromSheet(range: DurationRange, factor: Double, earlierPercent: Int, laterPercent: Int): TimeSpan {
            val shortest = (range.minMinutes * factor * (1 - earlierPercent / 100.0)).roundMinutes()
            val longest = (range.maxMinutes * factor * (1 + laterPercent / 100.0)).roundMinutes()
            val likely = ((range.minMinutes + range.maxMinutes) / 2.0 * factor).roundMinutes().coerceIn(shortest, longest)
            return TimeSpan(shortest, likely, longest)
        }
    }
}

/** A moment known within a range, with its most likely time. */
data class TimeWindow(val earliest: LocalDateTime, val estimate: LocalDateTime, val latest: LocalDateTime) {
    operator fun plus(span: TimeSpan) = TimeWindow(
        earliest = earliest.plusMinutes(span.shortest),
        estimate = estimate.plusMinutes(span.likely),
        latest = latest.plusMinutes(span.longest),
    )

    companion object {
        fun at(time: LocalDateTime) = TimeWindow(time, time, time)
    }
}

/** The car shuttle of a canyon that ends far from where it starts: a car is left at the end first. */
data class Shuttle(val distanceKm: Double) {
    /** Driving the shuttle road once, rounded up to five minutes. */
    val driveMinutes: Int get() = roundUpToFive(distanceKm / SPEED_KMH * 60)

    companion object {
        /** Small mountain roads. */
        const val SPEED_KMH = 40.0

        private val DISTANCE = Regex("""(\d+(?:[.,]\d+)?)\s*km""")

        /** Reads the shuttle of a sheet ("3.0km"); null for "néant", "??" or nothing. */
        fun parse(raw: String?): Shuttle? {
            val match = DISTANCE.find(raw?.lowercase().orEmpty()) ?: return null
            val km = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return Shuttle(km).takeIf { km > 0.0 }
        }
    }
}

/** Which time of the day the user sets; the others follow from it. */
enum class OutingAnchor {
    /** Leaving the starting place (campsite, home): needs a place. */
    LEAVE_PLACE,

    /** Arriving at the parking, where the group meets. */
    ARRIVE_PARKING,
}

/** Steps of an outing the user can lengthen or shorten for the day. */
enum class OutingPhase {
    SHUTTLE,
    PREP,
    APPROACH,
    DESCENT,
    RETURN,
    PACK_UP,
}

data class OutingScheduleInput(
    val date: LocalDate,
    val anchor: OutingAnchor,
    val anchorTime: LocalTime,
    /** From the starting place to the meeting parking; null without a starting place. */
    val driveMinutes: Int?,
    val shuttle: Shuttle?,
    val approach: CanyonDuration,
    val descent: CanyonDuration,
    val back: CanyonDuration,
    val pace: OutingPace,
    val settings: OutingSettings,
    val adjustments: Map<OutingPhase, Int> = emptyMap(),
    /** The canyons after the first one, in their order. */
    val nextCanyons: List<NextCanyonInput> = emptyList(),
)

/** A canyon after the first one: the parts of its sheet and how the group gets to it. */
data class NextCanyonInput(
    val link: CanyonLink,
    val approach: CanyonDuration,
    val descent: CanyonDuration,
    val back: CanyonDuration,
    /** By car, from the parking of the canyon before; null when unknown. */
    val driveMinutes: Int? = null,
    /**
     * From the end of the descent before to the start of this one, as the topos give it: then
     * neither the way out of the canyon before nor the approach of this one is counted. Straight on,
     * none given means no time at all.
     */
    val betweenMinutes: Int? = null,
    /**
     * Minutes added to (or taken from) its [OutingPhase.APPROACH], [OutingPhase.DESCENT] and
     * [OutingPhase.RETURN]; straight on or with [betweenMinutes], its approach is the time from one
     * canyon to the other.
     */
    val adjustments: Map<OutingPhase, Int> = emptyMap(),
)

/** A canyon after the first one in the day; a moment is null when a part before it is unknown on a sheet. */
data class NextCanyonSchedule(
    val link: CanyonLink,
    /** From the end of the canyon before straight to this one (straight on, or a walk the topos give): no way out of it counted. */
    val direct: Boolean = false,
    /** By car: back at the car after the canyon before. */
    val backAtCarBefore: TimeWindow?,
    /** By car: packing up, the drive and getting ready again at the next parking. */
    val transfer: TimeSpan?,
    /** On the way to this canyon: the end of the canyon before (straight on), out of it, or leaving the car. */
    val walkStart: TimeWindow?,
    val descentStart: TimeWindow?,
    val descentEnd: TimeWindow?,
    /** Its approach, descent and return, as computed. */
    val spans: Map<OutingPhase, TimeSpan>,
)

/**
 * The whole outing day: drive from the starting place, shuttle, gearing up, the three parts of the
 * sheet stretched for the group, packing, shuttle back and drive back. The parts of the sheet are
 * ranges; a step is null when a part before it is unknown on the sheet.
 */
data class OutingSchedule(
    /** The anchor actually used: leaving the place needs a drive time. */
    val anchor: OutingAnchor,
    val anchorAt: LocalDateTime,
    val leavePlace: LocalDateTime?,
    /** Arrival at the first parking, the end one with a shuttle: where the group meets. */
    val arriveParking: LocalDateTime,
    /** Arrival at the start parking, after leaving a car at the end with a shuttle. */
    val arriveStartParking: LocalDateTime,
    /** Leaving the car on foot. */
    val walkStart: LocalDateTime,
    val descentStart: TimeWindow?,
    val descentEnd: TimeWindow?,
    val backAtCar: TimeWindow?,
    /** Packed, and the car of the start fetched with a shuttle. */
    val readyToLeave: TimeWindow?,
    val backAtPlace: TimeWindow?,
    val driveMinutes: Int?,
    /** What each step takes, as computed (null for the parts unknown on the sheet). */
    val spans: Map<OutingPhase, TimeSpan>,
    /** Fetching the car left at the start, after packing, with a shuttle. */
    val shuttleBack: TimeSpan? = null,
    /** The canyons after the first one; [descentStart], [descentEnd] and the spans of the sheet are those of the first one. */
    val nextCanyons: List<NextCanyonSchedule> = emptyList(),
) {
    /** From leaving the car to being back at it, at worst. */
    val longestOnFoot: Duration? get() = backAtCar?.let { Duration.between(walkStart, it.latest) }

    /** The end of the last descent of the day. */
    val lastDescentEnd: TimeWindow? get() = if (nextCanyons.isEmpty()) descentEnd else nextCanyons.last().descentEnd

    companion object {
        fun compute(input: OutingScheduleInput): OutingSchedule {
            val settings = input.settings
            val pace = input.pace
            fun adjustment(phase: OutingPhase) = input.adjustments[phase] ?: 0
            fun parkingSpan(base: Int) = TimeSpan.exactly((base * pace.parkingFactor).roundMinutes())
            fun parkingMinutes(base: Int, phase: OutingPhase) = parkingSpan(base).adjustedBy(adjustment(phase))
            fun sheetSpan(
                duration: CanyonDuration,
                factor: Double,
                phase: OutingPhase,
                adjustments: Map<OutingPhase, Int> = input.adjustments,
            ): TimeSpan? = when (duration) {
                CanyonDuration.None -> TimeSpan.ZERO
                CanyonDuration.Unknown -> null
                is CanyonDuration.Known -> TimeSpan.fromSheet(duration.range, factor, settings.earlierPercent, settings.laterPercent)
            }?.adjustedBy(adjustments[phase] ?: 0)

            val drive = input.driveMinutes?.toLong()
            val shuttleMorning = input.shuttle?.let {
                TimeSpan.exactly((it.driveMinutes + settings.shuttleHandlingMinutes).toLong()).adjustedBy(adjustment(OutingPhase.SHUTTLE))
            }
            val shuttleEvening = input.shuttle?.let {
                TimeSpan.exactly(it.driveMinutes.toLong()).adjustedBy(adjustment(OutingPhase.SHUTTLE))
            }
            val prep = parkingMinutes(settings.prepMinutes, OutingPhase.PREP)
            val packUp = parkingMinutes(settings.packUpMinutes, OutingPhase.PACK_UP)
            val approach = sheetSpan(input.approach, pace.walkFactor, OutingPhase.APPROACH)
            // A sheet without descent time ("néant") says nothing of the descent.
            val descent = (input.descent as? CanyonDuration.Known)?.let { sheetSpan(it, pace.descentFactor, OutingPhase.DESCENT) }
            val back = sheetSpan(input.back, pace.walkFactor, OutingPhase.RETURN)

            val anchor = if (drive == null) OutingAnchor.ARRIVE_PARKING else input.anchor
            val anchorAt = input.date.atTime(input.anchorTime)
            val arriveParking = if (anchor == OutingAnchor.LEAVE_PLACE && drive != null) anchorAt.plusMinutes(drive) else anchorAt
            val arriveStartParking = arriveParking.plusMinutes(shuttleMorning?.likely ?: 0)
            val walkStart = arriveStartParking.plusMinutes(prep.likely)
            val descentStart = approach?.let { TimeWindow.at(walkStart) + it }
            val descentEnd = descentStart?.let { window -> descent?.let { window + it } }
            // Each next canyon starts from the end of the one before: straight on, or its return then
            // the way in to the next one, on foot or after a drive.
            var end = descentEnd
            var lastBack = back
            val nextCanyons = input.nextCanyons.map { next ->
                val between = next.betweenMinutes?.takeIf { next.link != CanyonLink.BY_CAR }
                    ?: 0.takeIf { next.link == CanyonLink.CONTINUOUS }
                val direct = between != null
                val nextApproach = if (between != null) {
                    TimeSpan.exactly(between.toLong()).adjustedBy(next.adjustments[OutingPhase.APPROACH] ?: 0)
                } else {
                    sheetSpan(next.approach, pace.walkFactor, OutingPhase.APPROACH, next.adjustments)
                }
                val nextDescent = (next.descent as? CanyonDuration.Known)
                    ?.let { sheetSpan(it, pace.descentFactor, OutingPhase.DESCENT, next.adjustments) }
                val nextBack = sheetSpan(next.back, pace.walkFactor, OutingPhase.RETURN, next.adjustments)
                val outOfPrevious = end?.let { window -> lastBack?.let { window + it } }
                val transfer = next.driveMinutes?.takeIf { next.link == CanyonLink.BY_CAR }?.let { drive ->
                    parkingSpan(settings.packUpMinutes) + TimeSpan.exactly(drive.toLong()) + parkingSpan(settings.prepMinutes)
                }
                val walkStart = when {
                    direct -> end
                    next.link == CanyonLink.ON_FOOT -> outOfPrevious
                    else -> outOfPrevious?.let { window -> transfer?.let { window + it } }
                }
                val nextStart = walkStart?.let { window -> nextApproach?.let { window + it } }
                val nextEnd = nextStart?.let { window -> nextDescent?.let { window + it } }
                end = nextEnd
                lastBack = nextBack
                NextCanyonSchedule(
                    link = next.link,
                    direct = direct,
                    backAtCarBefore = outOfPrevious.takeIf { next.link == CanyonLink.BY_CAR },
                    transfer = transfer,
                    walkStart = walkStart,
                    descentStart = nextStart,
                    descentEnd = nextEnd,
                    spans = buildMap {
                        nextApproach?.let { put(OutingPhase.APPROACH, it) }
                        nextDescent?.let { put(OutingPhase.DESCENT, it) }
                        nextBack?.let { put(OutingPhase.RETURN, it) }
                    },
                )
            }
            val backAtCar = end?.let { window -> lastBack?.let { window + it } }
            val readyToLeave = backAtCar?.let { it + packUp + (shuttleEvening ?: TimeSpan.ZERO) }
            return OutingSchedule(
                anchor = anchor,
                anchorAt = anchorAt,
                leavePlace = drive?.let { arriveParking.minusMinutes(it) },
                arriveParking = arriveParking,
                arriveStartParking = arriveStartParking,
                walkStart = walkStart,
                descentStart = descentStart,
                descentEnd = descentEnd,
                backAtCar = backAtCar,
                readyToLeave = readyToLeave,
                backAtPlace = readyToLeave?.let { window -> drive?.let { window + TimeSpan.exactly(it) } },
                driveMinutes = input.driveMinutes,
                spans = buildMap {
                    shuttleMorning?.let { put(OutingPhase.SHUTTLE, it) }
                    put(OutingPhase.PREP, prep)
                    approach?.let { put(OutingPhase.APPROACH, it) }
                    descent?.let { put(OutingPhase.DESCENT, it) }
                    back?.let { put(OutingPhase.RETURN, it) }
                    put(OutingPhase.PACK_UP, packUp)
                },
                shuttleBack = shuttleEvening,
                nextCanyons = nextCanyons,
            )
        }
    }
}

/** How the outing fits in the daylight of its day. */
sealed interface DaylightCheck {
    /**
     * Back at the car before the sunset even when every step takes long, with [margin] left; the
     * descent must start by [latestDescentStart] to keep it so.
     */
    data class Ok(val sunset: LocalTime, val margin: Duration, val latestDescentStart: LocalTime? = null) : DaylightCheck

    /** At worst back after the sunset (or less than [SAFETY_MARGIN] before): the anchor time must move to [latestAnchor]. */
    data class Late(val sunset: LocalTime, val latestAnchor: LocalTime?) : DaylightCheck

    /** Durations or sunset unknown. */
    data object Unknown : DaylightCheck

    companion object {
        /** Keep half an hour of daylight for the unexpected (a jammed rope, a slow member). */
        val SAFETY_MARGIN: Duration = Duration.ofMinutes(30)

        /** Back at the car at [latestBack] at worst, on an outing of [day]: too close to the [sunset] or after it. */
        fun endsTooLate(day: LocalDate, latestBack: LocalDateTime, sunset: LocalTime): Boolean =
            Duration.between(latestBack, day.atTime(sunset)) < SAFETY_MARGIN

        fun of(schedule: OutingSchedule, sunset: LocalTime?): DaylightCheck {
            val back = schedule.backAtCar ?: return Unknown
            sunset ?: return Unknown
            val day = schedule.walkStart.toLocalDate()
            val limit = day.atTime(sunset).minus(SAFETY_MARGIN)
            val margin = Duration.between(back.latest, day.atTime(sunset))
            if (margin >= SAFETY_MARGIN) {
                val latestDescentStart = schedule.descentStart?.let { start -> limit.minus(Duration.between(start.latest, back.latest)) }
                return Ok(sunset, margin, latestDescentStart?.takeIf { it.toLocalDate() == day }?.toLocalTime())
            }
            val latestAnchor = schedule.anchorAt.minus(Duration.between(limit, back.latest))
            return Late(sunset, latestAnchor.takeIf { it.toLocalDate() == schedule.anchorAt.toLocalDate() }?.toLocalTime())
        }
    }
}

/** Minutes rounded half up; products of factors such as 20 × 1.15 × 1.5 land just below the half. */
internal fun Double.roundMinutes(): Long = (this + ROUNDING_TOLERANCE).roundToLong()

private const val ROUNDING_TOLERANCE = 1e-6

/** Rounds minutes up to the next multiple of five, at least five: times shown are not more precise. */
fun roundUpToFive(minutes: Double): Int = (ceil(minutes / 5.0 - ROUNDING_TOLERANCE) * 5).roundToInt().coerceAtLeast(5)
