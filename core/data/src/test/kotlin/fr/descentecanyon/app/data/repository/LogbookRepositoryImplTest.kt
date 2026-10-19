package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.local.user.UserDatabase
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.testutil.TestDatabases
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LogbookRepositoryImplTest {

    private val database: UserDatabase = TestDatabases.user()
    private val repository =
        LogbookRepositoryImpl(database.logbookDao(), Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC))

    @After
    fun tearDown() = database.close()

    private fun outing(canyonId: Int = 26, date: String = "2026-07-30", note: String? = null) =
        LogbookEntry(canyonId = canyonId, canyonName = "Furon", date = LocalDate.parse(date), note = note)

    @Test
    fun `an outing is saved with every field and read back`() = runTest {
        repository.save(
            outing(note = "  Rappel C7 coincé  ").copy(debitLevel = NiveauDebit.GROS, companions = " Paul ,, Léa ", durationMinutes = 250),
        )

        val saved = repository.observeAll().first().single()
        assertEquals("Rappel C7 coincé", saved.note)
        assertEquals(NiveauDebit.GROS, saved.debitLevel)
        assertEquals("Paul, Léa", saved.companions)
        assertEquals(250, saved.durationMinutes)
        assertEquals(LogbookSource.MANUAL, saved.source)
        assertEquals(setOf(26), repository.observeDescendedCanyonIds().first())
    }

    @Test
    fun `an edit can clear a field and move the outing to another day`() = runTest {
        repository.save(outing(note = "Brouillon").copy(durationMinutes = 120))
        val saved = repository.observeAll().first().single()

        repository.save(saved.copy(date = LocalDate.of(2026, 7, 31), note = null))

        val edited = repository.observeAll().first().single()
        assertEquals(saved.id, edited.id)
        assertEquals(LocalDate.of(2026, 7, 31), edited.date)
        assertEquals(null, edited.note)
        assertEquals(120, edited.durationMinutes)
    }

    @Test
    fun `an outing moved onto the day of another one of the canyon merges with it`() = runTest {
        repository.save(outing(date = "2026-07-30").copy(debitLevel = NiveauDebit.CORRECT, companions = "Paul"))
        repository.save(outing(date = "2026-07-29", note = "Le bon récit"))
        val moved = repository.observeAll().first().first { it.date == LocalDate.of(2026, 7, 29) }

        repository.save(moved.copy(date = LocalDate.of(2026, 7, 30)))

        val merged = repository.observeAll().first().single()
        assertEquals("Le bon récit", merged.note)
        assertEquals(NiveauDebit.CORRECT, merged.debitLevel)
        assertEquals("Paul", merged.companions)
    }

    @Test
    fun `an outing recorded again fills the gaps and never overwrites`() = runTest {
        repository.save(outing(note = "Écrit à la main"))

        val wasNew = repository.record(
            outing(note = "Commentaire du site").copy(debitLevel = NiveauDebit.FILET, source = LogbookSource.SITE),
        )

        assertFalse(wasNew)
        val entry = repository.observeAll().first().single()
        assertEquals("Écrit à la main", entry.note)
        assertEquals(NiveauDebit.FILET, entry.debitLevel)
        assertEquals(LogbookSource.MANUAL, entry.source)
        assertTrue(repository.record(outing(canyonId = 2283, date = "2026-07-14").copy(source = LogbookSource.OUTING)))
        assertEquals(LogbookSource.OUTING, repository.observeForCanyon(2283).first().single().source)
    }

    @Test
    fun `companions are offered by how often they came`() = runTest {
        repository.save(outing(date = "2026-07-01").copy(companions = "Léa, Paul"))
        repository.save(outing(date = "2026-07-02").copy(companions = "paul"))
        repository.save(outing(date = "2026-07-03").copy(companions = "Marc"))

        assertEquals(listOf("Paul", "Léa", "Marc"), repository.knownCompanions())

        repository.clear()
        assertEquals(emptyList<LogbookEntry>(), repository.observeAll().first())
    }
}
