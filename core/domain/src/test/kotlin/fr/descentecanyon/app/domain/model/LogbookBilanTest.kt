package fr.descentecanyon.app.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class LogbookBilanTest {

    private val today = LocalDate.of(2026, 10, 19)
    private val summaries = listOf(
        CanyonSummary(id = 26, nom = "Furon (partie haute)", pays = "France", departement = "Isère", cotation = "v3a3II", url = ""),
        CanyonSummary(id = 2283, nom = "Ga", pays = "France", departement = "Hautes-Alpes", cotation = "v5a4IV", url = ""),
        CanyonSummary(id = 22937, nom = "Vallungo", pays = "Italie", departement = "Piémont", cotation = "v4a4III", url = ""),
    ).associateBy { it.id }

    private val entries = listOf(
        entry(26, "2026-07-30", NiveauDebit.CORRECT, "Paul, Marie", 250),
        entry(2283, "2026-07-14", NiveauDebit.GROS, "paul"),
        entry(26, "2025-08-02", NiveauDebit.CORRECT, duration = 200),
        entry(22937, "2023-08-08", NiveauDebit.FILET),
        entry(26, "2023-07-13"),
        // A canyon gone from the catalog still counts, without place nor rating.
        entry(99999, "2018-07-26", name = "Ancien canyon"),
    )

    @Test
    fun `totals, years, places and ratings`() {
        val bilan = LogbookBilan.of(entries, summaries, today)

        assertEquals(LogbookStats(outings = 6, canyons = 4, outingsThisYear = 2), bilan.stats)
        assertEquals(
            listOf(LogbookYear(2026, 2, 2), LogbookYear(2025, 1, 1), LogbookYear(2023, 2, 2), LogbookYear(2018, 1, 1)),
            bilan.years,
        )
        assertEquals(listOf(LogbookCount("France", 4), LogbookCount("Italie", 1)), bilan.countries)
        assertEquals(LogbookCount("Isère", 3), bilan.areas.first())
        assertEquals(listOf(LogbookCount("v3", 3), LogbookCount("v4", 1), LogbookCount("v5", 1)), bilan.verticalRatings)
        assertEquals(LogbookCanyonCount(2283, "Ga", 1), bilan.hardest)
        assertEquals("v5a4IV", bilan.hardestRating)
    }

    @Test
    fun `canyons done again, flows, companions and time`() {
        val bilan = LogbookBilan.of(entries, summaries, today)

        assertEquals(listOf(LogbookCanyonCount(26, "Furon (partie haute)", 3)), bilan.repeated)
        assertEquals(listOf(NiveauDebit.GROS to 1, NiveauDebit.CORRECT to 2, NiveauDebit.FILET to 1), bilan.levels)
        // Names are counted whatever their case, shown as first typed.
        assertEquals(listOf(LogbookCount("Paul", 2), LogbookCount("Marie", 1)), bilan.companions)
        assertEquals(450, bilan.totalDurationMinutes)
        assertEquals(2, bilan.timedOutings)
    }

    @Test
    fun `an empty logbook has empty figures`() {
        val bilan = LogbookBilan.of(emptyList(), summaries, today)

        assertEquals(LogbookStats(0, 0, 0), bilan.stats)
        assertEquals(emptyList<LogbookYear>(), bilan.years)
        assertEquals(null, bilan.hardest)
    }

    private fun entry(
        canyonId: Int,
        date: String,
        level: NiveauDebit? = null,
        companions: String? = null,
        duration: Int? = null,
        name: String = summaries[canyonId]?.nom.orEmpty(),
    ) = LogbookEntry(
        canyonId = canyonId,
        canyonName = name,
        date = LocalDate.parse(date),
        debitLevel = level,
        companions = companions,
        durationMinutes = duration,
    )
}
