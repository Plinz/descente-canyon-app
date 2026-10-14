package fr.descentecanyon.app.data.mapper

import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import fr.descentecanyon.app.domain.model.NiveauDebit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DebitMapperTest {

    @Test
    fun `ScrapedDebit toDomain keeps canyon name and parses latest debit date`() {
        val scraped = ScrapedDebit(
            canyonId = 220,
            canyonNom = "Ruzand",
            date = "mar. 17/03",
            niveauRaw = "CORRECT",
        )

        val debit = scraped.toDomain()

        assertEquals(220, debit.canyonId)
        assertEquals("Ruzand", debit.canyonNom)
        assertEquals(LocalDate.now().year, debit.date.year)
        assertEquals(3, debit.date.monthValue)
        assertEquals(17, debit.date.dayOfMonth)
        assertEquals(NiveauDebit.CORRECT, debit.niveau)
    }
}
