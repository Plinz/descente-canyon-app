package fr.descentecanyon.app.domain.model

import java.time.LocalDate

/**
 * A canyon the user descended, kept in their logbook on the device. The logbook holds what only
 * the person knows (day, companions, duration); the flow of the day belongs to the site and is only
 * read back through [debit].
 */
data class LogbookEntry(
    val id: Long = 0,
    val canyonId: Int,
    val canyonName: String,
    val date: LocalDate,
    /** Who came along, as typed: names separated by commas. */
    val companions: String? = null,
    /** From leaving the car to being back at it, in minutes. */
    val durationMinutes: Int? = null,
    val source: LogbookSource = LogbookSource.MANUAL,
    /** The flow report of that canyon and day: read from the site and the send queue, never saved here. */
    val debit: LogbookDebit = LogbookDebit.None,
) {
    /** Names of [companions], trimmed, in the order typed. */
    val companionNames: List<String> get() = splitCompanions(companions)

    /**
     * This entry completed by [other], an outing of the same canyon and day found elsewhere (back
     * from an outing, report, import): what is already written wins, only the gaps are filled.
     */
    fun completedWith(other: LogbookEntry): LogbookEntry = copy(
        companions = companions ?: other.companions,
        durationMinutes = durationMinutes ?: other.durationMinutes,
    )

    /**
     * A recent outing with no flow report found: worth offering one. A flow seen weeks ago no longer
     * helps anyone decide, and an old outing may have been reported before the app kept track of it.
     */
    fun awaitsFlowReport(today: LocalDate): Boolean =
        debit == LogbookDebit.None && !date.isAfter(today) && !date.isBefore(today.minusDays(FLOW_REPORT_DAYS))

    companion object {
        /** Outings older than this are not offered a flow report. */
        const val FLOW_REPORT_DAYS = 30L

        /** Longest outing recorded on its own: beyond, "I'm back" was tapped long after the return. */
        const val MAX_RECORDED_DURATION_MINUTES = 16 * 60

        fun splitCompanions(text: String?): List<String> = text.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** How an outing got into the logbook. */
enum class LogbookSource {
    /** Added or edited by hand. */
    MANUAL,

    /** "I'm back" after a planned outing. */
    OUTING,

    /** A flow report of a descent sent from the app. */
    REPORT,

    /** The list of reports of the site account ("Ma liste de courses"). */
    SITE,

    /** A file exported from the app. */
    FILE,
}

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

/** The user's memo about a canyon: lasting information (access, gear, traps), one per canyon. */
data class CanyonNote(val canyonId: Int, val text: String, val updatedAt: Long)
