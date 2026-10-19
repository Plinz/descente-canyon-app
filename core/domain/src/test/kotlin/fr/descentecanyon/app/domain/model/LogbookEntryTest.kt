package fr.descentecanyon.app.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class LogbookEntryTest {

    private val written = LogbookEntry(
        id = 5,
        canyonId = 26,
        canyonName = "Furon",
        date = LocalDate.of(2026, 7, 30),
        note = "Rappel C7 coincé",
        companions = "Paul",
    )

    @Test
    fun `an outing found elsewhere fills the gaps without overwriting what was written`() {
        val fromSite = written.copy(
            id = 0,
            note = "Débit correct, eau froide",
            debitLevel = NiveauDebit.CORRECT,
            companions = null,
            durationMinutes = 250,
            source = LogbookSource.SITE,
            siteObservationId = 199338,
        )

        assertEquals(
            written.copy(debitLevel = NiveauDebit.CORRECT, durationMinutes = 250, siteObservationId = 199338),
            written.completedWith(fromSite),
        )
    }

    @Test
    fun `companions are split on commas and semicolons`() {
        assertEquals(listOf("Paul", "Marie", "Léa"), written.copy(companions = " Paul, Marie ;Léa, ").companionNames)
        assertEquals(emptyList<String>(), written.copy(companions = null).companionNames)
    }
}
