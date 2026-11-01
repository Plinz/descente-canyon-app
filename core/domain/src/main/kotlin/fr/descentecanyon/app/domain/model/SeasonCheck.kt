package fr.descentecanyon.app.domain.model

import java.time.LocalDate
import java.time.Month

/**
 * Whether the month of an outing is a usual one for a canyon, judged from the months it was descended
 * in, by its flow reports (all years together; a flow only looked at does not count). A canyon
 * descended so rarely that month has a topo, anchors and water of another season: a glacier-fed gorge
 * is done from late summer to winter, when the melt is over, a Pyrenean one rarely in February.
 */
sealed interface SeasonCheck {
    /** Too few reports to tell a season. */
    data object Unknown : SeasonCheck

    data object InSeason : SeasonCheck

    /** Few reports in [month]; the usual season runs from [usualFrom] to [usualTo]. */
    data class OffSeason(val month: Month, val reportsInMonth: Int, val totalReports: Int, val usualFrom: Month, val usualTo: Month) :
        SeasonCheck

    companion object {
        /** Below this many reports with a level, no season is told. */
        const val MIN_REPORTS = 20

        /** A month with less than this share of the reports is off season. */
        const val OFF_SEASON_SHARE = 0.02

        /** The months with at least this share of the reports make the usual season. */
        const val USUAL_SHARE = 0.05

        fun of(debits: List<Debit>, date: LocalDate): SeasonCheck {
            // Reports that do not say whether the canyon was descended (older ones) count as descents.
            val profiles = debits.filter { it.isDescended != false }.monthlyProfiles()
            val total = profiles.sumOf { it.reportCount }
            if (total < MIN_REPORTS) return Unknown
            val inMonth = profiles.first { it.month == date.month }.reportCount
            if (inMonth.toDouble() / total >= OFF_SEASON_SHARE) return InSeason
            val usual = longestRun(profiles.map { it.reportCount.toDouble() / total >= USUAL_SHARE }) ?: return Unknown
            return OffSeason(date.month, inMonth, total, Month.of(usual.first + 1), Month.of(usual.last + 1))
        }

        /**
         * The longest run of months flagged in [usual] (January first), the year going round: a
         * southern summer runs from December to March. Its first and last month indexes, null if none.
         */
        private fun longestRun(usual: List<Boolean>): IntRange? {
            if (usual.none { it }) return null
            if (usual.all { it }) return 0..11
            var best: IntRange? = null
            for (start in 0 until 12) {
                // A run starts after a month out of season.
                if (!usual[start] || usual[(start + 11) % 12]) continue
                var length = 0
                while (length < 12 && usual[(start + length) % 12]) length++
                if (best == null || length > best.count()) best = start until start + length
            }
            return best?.let { it.first..(it.last % 12) }
        }
    }
}
