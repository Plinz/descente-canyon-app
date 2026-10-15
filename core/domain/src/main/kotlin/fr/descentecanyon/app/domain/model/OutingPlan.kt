package fr.descentecanyon.app.domain.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

/** A moment known within a range: the durations of the sheets are ranges ("20 à 45min"). */
data class TimeWindow(val earliest: LocalDateTime, val latest: LocalDateTime) {
    operator fun plus(range: DurationRange) = TimeWindow(
        earliest = earliest.plusMinutes(range.minMinutes.toLong()),
        latest = latest.plusMinutes(range.maxMinutes.toLong()),
    )
}

/**
 * Timeline of an outing from the parking: start of the descent (after the approach), end of the
 * descent and back at the car (after the return walk). A step is null when a duration before it is
 * unknown on the sheet.
 */
data class OutingTimeline(
    val start: LocalDateTime,
    val descentStart: TimeWindow?,
    val descentEnd: TimeWindow?,
    val backAtCar: TimeWindow?,
) {
    /** Longest total duration, when every step is known. */
    val longestDuration: Duration? get() = backAtCar?.let { Duration.between(start, it.latest) }

    companion object {
        fun compute(start: LocalDateTime, approach: CanyonDuration, descent: CanyonDuration, back: CanyonDuration): OutingTimeline {
            val descentStart = approach.rangeOrNull()?.let { TimeWindow(start, start) + it }
            val descentEnd = descentStart?.let { window -> descent.rangeOrNull()?.takeIf { it.maxMinutes > 0 }?.let { window + it } }
            val backAtCar = descentEnd?.let { window -> back.rangeOrNull()?.let { window + it } }
            return OutingTimeline(start, descentStart, descentEnd, backAtCar)
        }

        private fun CanyonDuration.rangeOrNull(): DurationRange? = when (this) {
            CanyonDuration.None -> DurationRange.NONE
            is CanyonDuration.Known -> range
            CanyonDuration.Unknown -> null
        }
    }
}

/** How the outing fits in the daylight of its day. */
sealed interface DaylightCheck {
    /** Back at the car before the sunset, with [margin] left at worst. */
    data class Ok(val sunset: LocalTime, val margin: Duration) : DaylightCheck

    /** At worst back after the sunset (or less than [SAFETY_MARGIN] before): start before [latestStart]. */
    data class Late(val sunset: LocalTime, val latestStart: LocalTime?) : DaylightCheck

    /** Durations or sunset unknown. */
    data object Unknown : DaylightCheck

    companion object {
        /** Keep half an hour of daylight for the unexpected (a jammed rope, a slow member). */
        val SAFETY_MARGIN: Duration = Duration.ofMinutes(30)

        fun of(timeline: OutingTimeline, sunset: LocalTime?): DaylightCheck {
            val longest = timeline.longestDuration ?: return Unknown
            sunset ?: return Unknown
            val sunsetAt = timeline.start.toLocalDate().atTime(sunset)
            val margin = Duration.between(timeline.start.plus(longest), sunsetAt)
            if (margin >= SAFETY_MARGIN) return Ok(sunset, margin)
            val latestStart = sunsetAt.minus(SAFETY_MARGIN).minus(longest)
            return Late(sunset, latestStart.takeIf { it.toLocalDate() == timeline.start.toLocalDate() }?.toLocalTime())
        }
    }
}

/**
 * An outing the user announced: the app reminds them at the expected return to report the flow,
 * and the relatives who received the plan know when to worry.
 */
data class PlannedOuting(
    val canyonId: Int,
    val canyonName: String,
    val start: Instant,
    /** Latest expected return to the car. */
    val expectedBack: Instant,
    /** After this time without news, the relatives should call for help. */
    val alertAt: Instant,
) {
    /** An outing whose alert time is long past is forgotten. */
    fun isStale(now: Instant): Boolean = now.isAfter(alertAt.plus(STALE_AFTER))

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(12)
    }
}
