package fr.descentecanyon.app.domain.model

import java.time.LocalDate

/** A label and how many outings it gathers. */
data class LogbookCount(val label: String, val outings: Int)

/** Outings and distinct canyons of a year. */
data class LogbookYear(val year: Int, val outings: Int, val canyons: Int)

/** A canyon of the logbook and how many times it was descended. */
data class LogbookCanyonCount(val canyonId: Int, val canyonName: String, val outings: Int)

/** What the logbook tells over the years: when, where, how hard, how often, with whom and which flows. */
data class LogbookBilan(
    val stats: LogbookStats,
    /** Newest first. */
    val years: List<LogbookYear>,
    /** Most outings first. */
    val countries: List<LogbookCount>,
    val areas: List<LogbookCount>,
    /** Outings by vertical rating ("v4"), easiest first. */
    val verticalRatings: List<LogbookCount>,
    /** The hardest canyon descended, by its rating. */
    val hardest: LogbookCanyonCount?,
    val hardestRating: String?,
    /** Canyons descended more than once, most first. */
    val repeated: List<LogbookCanyonCount>,
    /** Flows seen, by level, wettest first. */
    val levels: List<Pair<NiveauDebit, Int>>,
    val companions: List<LogbookCount>,
    /** Time spent in the outings whose duration is known. */
    val totalDurationMinutes: Int,
    val timedOutings: Int,
) {
    companion object {
        private val VERTICAL = Regex("""(?i)\bv\s*(\d)""")

        /** [summaries] give each canyon's country, area and rating; canyons missing from the catalog are counted without them. */
        fun of(entries: List<LogbookEntry>, summaries: Map<Int, CanyonSummary>, today: LocalDate): LogbookBilan {
            val years = entries.groupBy { it.date.year }
                .map { (year, outings) -> LogbookYear(year, outings.size, outings.map { it.canyonId }.distinct().size) }
                .sortedByDescending { it.year }
            val byCanyon = entries.groupBy { it.canyonId }
            val counts = byCanyon.map { (id, outings) ->
                LogbookCanyonCount(id, summaries[id]?.nom ?: outings.first().canyonName, outings.size)
            }
            val hardest = byCanyon.keys
                .mapNotNull { id -> summaries[id]?.let { id to CotationRating.parse(it.cotation) } }
                .filter { (_, rating) -> rating.isKnown }
                .maxWithOrNull(compareBy(HARDEST_FIRST) { it.second })
            return LogbookBilan(
                stats = LogbookStats.of(entries, today),
                years = years,
                countries = entries.countBy { summaries[it.canyonId]?.pays?.takeIf(String::isNotBlank) },
                areas = entries.countBy { summaries[it.canyonId]?.departement?.takeIf(String::isNotBlank) },
                verticalRatings = entries
                    .mapNotNull { entry -> summaries[entry.canyonId]?.cotation?.let { VERTICAL.find(it)?.groupValues?.get(1)?.toInt() } }
                    .groupingBy { it }
                    .eachCount()
                    .toSortedMap()
                    .map { (level, outings) -> LogbookCount("v$level", outings) },
                hardest = hardest?.let { (id, _) -> counts.first { it.canyonId == id } },
                hardestRating = hardest?.second?.raw,
                repeated = counts.filter {
                    it.outings > 1
                }.sortedWith(compareByDescending<LogbookCanyonCount> { it.outings }.thenBy { it.canyonName }),
                levels = entries.mapNotNull { it.debitLevel?.takeIf { level -> level != NiveauDebit.INCONNU } }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.key.ordinal }
                    .map { it.key to it.value },
                companions = entries.flatMap { it.companionNames }
                    .groupBy { it.lowercase() }
                    .map { (_, names) -> LogbookCount(names.first(), names.size) }
                    .sortedWith(compareByDescending<LogbookCount> { it.outings }.thenBy { it.label }),
                totalDurationMinutes = entries.sumOf { it.durationMinutes ?: 0 },
                timedOutings = entries.count { it.durationMinutes != null },
            )
        }

        private fun List<LogbookEntry>.countBy(label: (LogbookEntry) -> String?): List<LogbookCount> = mapNotNull(label)
            .groupingBy { it }
            .eachCount()
            .map { (name, outings) -> LogbookCount(name, outings) }
            .sortedWith(compareByDescending<LogbookCount> { it.outings }.thenBy { it.label })

        /** The higher of the three ratings decides, then the next ones (v5a2 is harder than v4a4). */
        private val HARDEST_FIRST = Comparator<CotationRating> { left, right ->
            val leftProfile = left.difficultyProfileDescending().orEmpty()
            val rightProfile = right.difficultyProfileDescending().orEmpty()
            leftProfile.zip(rightProfile).firstOrNull { it.first != it.second }?.let { (l, r) -> l.compareTo(r) } ?: 0
        }
    }
}
