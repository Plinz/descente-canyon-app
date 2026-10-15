package fr.descentecanyon.app.domain.model

import java.time.LocalDate
import java.time.Month
import org.junit.Assert.assertEquals
import org.junit.Test

class DebitSeasonalityTest {

    @Test
    fun `reports are grouped by calendar month across years, unknown levels ignored`() {
        val debits = listOf(
            debit("2024-07-02", NiveauDebit.FILET),
            debit("2025-07-20", NiveauDebit.FILET),
            debit("2025-07-21", NiveauDebit.SEC),
            debit("2023-07-01", NiveauDebit.CORRECT),
            debit("2025-04-10", NiveauDebit.GROS),
            debit("2025-04-11", NiveauDebit.INCONNU),
        )

        val profiles = debits.monthlyProfiles()

        assertEquals(Month.entries.toList(), profiles.map { it.month })
        val july = profiles[Month.JULY.ordinal]
        assertEquals(4, july.reportCount)
        assertEquals(0.5, july.share(NiveauDebit.FILET), 1e-9)
        assertEquals(0.25, july.share(NiveauDebit.SEC), 1e-9)
        assertEquals(1, profiles[Month.APRIL.ordinal].reportCount)
        assertEquals(0, profiles[Month.JANUARY.ordinal].reportCount)
        assertEquals(0.0, profiles[Month.JANUARY.ordinal].share(NiveauDebit.SEC), 1e-9)
    }

    private fun debit(date: String, level: NiveauDebit) = Debit(canyonId = 1, date = LocalDate.parse(date), niveau = level)
}
