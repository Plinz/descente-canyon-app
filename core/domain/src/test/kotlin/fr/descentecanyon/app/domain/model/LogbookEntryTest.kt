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
        companions = "Paul",
    )

    @Test
    fun `an outing found elsewhere fills the gaps without overwriting what was written`() {
        val found = written.copy(id = 0, companions = "Marie", durationMinutes = 250, source = LogbookSource.OUTING)

        assertEquals(written.copy(durationMinutes = 250), written.completedWith(found))
    }

    @Test
    fun `companions are split on commas and semicolons`() {
        assertEquals(listOf("Paul", "Marie", "Léa"), written.copy(companions = " Paul, Marie ;Léa, ").companionNames)
        assertEquals(emptyList<String>(), written.copy(companions = null).companionNames)
    }

    @Test
    fun `the flow of an outing is the one of the site report, none while it is only waiting or sent`() {
        val report = SiteObservation(1, 26, "Furon", written.date, isDescended = true, debitLevel = NiveauDebit.CORRECT)

        assertEquals(NiveauDebit.CORRECT, LogbookDebit.Published(report).level)
        assertEquals(null, LogbookDebit.Published(report.copy(debitLevel = NiveauDebit.INCONNU)).level)
        assertEquals(null, LogbookDebit.Waiting.level)
        assertEquals(null, LogbookDebit.Sent(1L).level)
        assertEquals(null, LogbookDebit.None.level)
    }

    @Test
    fun `only a recent outing without any report is offered a flow report`() {
        val today = written.date.plusDays(3)

        assertEquals(true, written.awaitsFlowReport(today))
        assertEquals(false, written.copy(debit = LogbookDebit.Waiting).awaitsFlowReport(today))
        assertEquals(false, written.copy(debit = LogbookDebit.Sent(1L)).awaitsFlowReport(today))
        // Weeks later the flow helps no one, and it may have been reported before the app kept track.
        assertEquals(false, written.awaitsFlowReport(written.date.plusDays(LogbookEntry.FLOW_REPORT_DAYS + 1)))
        assertEquals(true, written.awaitsFlowReport(written.date.plusDays(LogbookEntry.FLOW_REPORT_DAYS)))
        // A day still to come has nothing to report.
        assertEquals(false, written.awaitsFlowReport(written.date.minusDays(1)))
    }
}
