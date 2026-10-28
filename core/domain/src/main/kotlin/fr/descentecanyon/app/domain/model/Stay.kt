package fr.descentecanyon.app.domain.model

import java.time.LocalDate

/** Days spent at a place (campsite, gîte), from [start] to [end]: a canyon is chosen for each of them. */
data class Stay(val placeId: Long, val start: LocalDate, val end: LocalDate, val id: Long = 0) {
    init {
        require(!end.isBefore(start)) { "A stay ends after it starts: $start – $end" }
    }

    fun covers(date: LocalDate): Boolean = date in start..end

    /** Shares at least one day with [other]: one is at one place a day, so two such stays are refused. */
    fun overlaps(other: Stay): Boolean = !start.isAfter(other.end) && !other.start.isAfter(end)

    /** Over: its last day is before [today]. */
    fun isOver(today: LocalDate): Boolean = end.isBefore(today)

    /** The days still to come at [today], all of them before the stay. */
    fun daysFrom(today: LocalDate): List<LocalDate> =
        generateSequence(maxOf(start, today)) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.take(MAX_DAYS).toList()

    /** Whether [outing] is one of its outings: planned for it, or on one of its days from its place. */
    fun includes(outing: PlannedOuting): Boolean {
        if (outing.stayId == id) return true
        val choices = outing.choices ?: return false
        return outing.stayId == null && choices.placeId == placeId && covers(choices.date)
    }

    /** The days its outings move by when it becomes [moved]: the whole stay shifted, null when its length changes. */
    fun shiftTo(moved: Stay): Long? {
        val days = moved.start.toEpochDay() - start.toEpochDay()
        return days.takeIf { it != 0L && moved.end.toEpochDay() - end.toEpochDay() == it }
    }

    companion object {
        /** A stay of more than a month is a place one lives at, not a stay. */
        const val MAX_DAYS = 31

        /** The days a new stay lasts by default: a week. */
        const val DEFAULT_DAYS = 7L
    }
}

/** The other stay that shares a day with [stay], if any. */
fun List<Stay>.overlapping(stay: Stay): Stay? = firstOrNull { it.id != stay.id && it.overlaps(stay) }
