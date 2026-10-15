package fr.descentecanyon.app.domain.model

import java.time.LocalDate

/** A canyon the user descended, kept in their logbook on the device only. */
data class LogbookEntry(val id: Long = 0, val canyonId: Int, val canyonName: String, val date: LocalDate, val note: String? = null)

/** Totals of the logbook: outings, distinct canyons, and the outings of the current year. */
data class LogbookStats(val outings: Int, val canyons: Int, val outingsThisYear: Int) {
    companion object {
        fun of(entries: List<LogbookEntry>, today: LocalDate): LogbookStats = LogbookStats(
            outings = entries.size,
            canyons = entries.map { it.canyonId }.distinct().size,
            outingsThisYear = entries.count { it.date.year == today.year },
        )
    }
}
