package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.local.user.UserDatabase
import fr.descentecanyon.app.data.local.user.entity.DebitSendEntity
import fr.descentecanyon.app.data.local.user.entity.MySiteReportEntity
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.domain.model.LogbookDebit
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.level
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
    private val repository = LogbookRepositoryImpl(
        database.logbookDao(),
        database.mySiteReportDao(),
        database.pendingDebitSubmissionDao(),
        database.debitSendDao(),
        Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC),
    )

    @After
    fun tearDown() = database.close()

    private fun outing(canyonId: Int = 26, date: String = "2026-07-30") =
        LogbookEntry(canyonId = canyonId, canyonName = "Furon", date = LocalDate.parse(date))

    private fun report(
        id: Long,
        canyonId: Int = 26,
        date: String = "2026-07-30",
        descended: Boolean = true,
        level: NiveauDebit = NiveauDebit.GROS,
    ) = MySiteReportEntity(
        id = id,
        canyonId = canyonId,
        canyonName = "Furon",
        date = date,
        isDescended = descended,
        debitLevel = level.name,
        waterTemperature = null,
        airTemperature = null,
        publicRemark = "Remarque du site",
        personalComment = "Commentaire perso",
        fetchedAt = 0,
    )

    private fun pending(canyonId: Int = 26, date: String = "2026-07-30", rejectedAt: Long? = null) = PendingDebitSubmissionEntity(
        canyonId = canyonId,
        observerName = "Antoine",
        observerEmail = null,
        observationDate = date,
        isDescended = true,
        debitLevel = NiveauDebit.GROS.name,
        waterTemperature = "INCONNUE",
        airTemperature = "INCONNUE",
        comment = "",
        rejectedAt = rejectedAt,
    )

    private suspend fun debitOf(date: String = "2026-07-30") = repository.observeAll().first().first {
        it.date == LocalDate.parse(date)
    }.debit

    @Test
    fun `an outing is saved with every field and read back`() = runTest {
        repository.save(outing().copy(companions = " Paul ,, Léa ", durationMinutes = 250))

        val saved = repository.observeAll().first().single()
        assertEquals("Paul, Léa", saved.companions)
        assertEquals(250, saved.durationMinutes)
        assertEquals(LogbookSource.MANUAL, saved.source)
        assertEquals(LogbookDebit.None, saved.debit)
        assertEquals(setOf(26), repository.observeDescendedCanyonIds().first())
    }

    @Test
    fun `an edit can clear a field and move the outing to another day`() = runTest {
        repository.save(outing().copy(durationMinutes = 120, companions = "Paul"))
        val saved = repository.observeAll().first().single()

        repository.save(saved.copy(date = LocalDate.of(2026, 7, 31), companions = null))

        val edited = repository.observeAll().first().single()
        assertEquals(saved.id, edited.id)
        assertEquals(LocalDate.of(2026, 7, 31), edited.date)
        assertEquals(null, edited.companions)
        assertEquals(120, edited.durationMinutes)
    }

    @Test
    fun `an outing moved onto the day of another one of the canyon merges with it`() = runTest {
        repository.save(outing(date = "2026-07-30").copy(companions = "Paul"))
        repository.save(outing(date = "2026-07-29").copy(durationMinutes = 200))
        val moved = repository.observeAll().first().first { it.date == LocalDate.of(2026, 7, 29) }

        repository.save(moved.copy(date = LocalDate.of(2026, 7, 30)))

        val merged = repository.observeAll().first().single()
        assertEquals(200, merged.durationMinutes)
        assertEquals("Paul", merged.companions)
    }

    @Test
    fun `an outing recorded again fills the gaps and never overwrites`() = runTest {
        repository.save(outing().copy(companions = "Paul"))

        val wasNew = repository.record(outing().copy(companions = "Marie", durationMinutes = 180, source = LogbookSource.SITE))

        assertFalse(wasNew)
        val entry = repository.observeAll().first().single()
        assertEquals("Paul", entry.companions)
        assertEquals(180, entry.durationMinutes)
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

    @Test
    fun `the flow of an outing is read from the report of the site for that canyon and day`() = runTest {
        repository.save(outing(date = "2026-07-30"))
        repository.save(outing(date = "2026-07-14"))
        database.mySiteReportDao().replaceAll(listOf(report(10), report(11, date = "2026-06-01"), report(12, canyonId = 99)))

        val published = debitOf("2026-07-30")
        assertEquals(NiveauDebit.GROS, published.level)
        assertEquals("Remarque du site", (published as LogbookDebit.Published).report.publicRemark)
        // Nothing is copied: no report for the day of the other outing.
        assertEquals(LogbookDebit.None, debitOf("2026-07-14"))

        database.mySiteReportDao().deleteAll()
        assertEquals(LogbookDebit.None, debitOf("2026-07-30"))
    }

    @Test
    fun `a report of a descent wins over one where the flow was only looked at`() = runTest {
        repository.save(outing())
        database.mySiteReportDao().replaceAll(
            listOf(report(10, descended = true, level = NiveauDebit.CORRECT), report(11, descended = false, level = NiveauDebit.CRUE)),
        )

        assertEquals(NiveauDebit.CORRECT, debitOf().level)
    }

    @Test
    fun `a report waiting to be sent shows first, a refused one does not`() = runTest {
        repository.save(outing())
        database.mySiteReportDao().replaceAll(listOf(report(10)))
        val waitingId = database.pendingDebitSubmissionDao().insert(pending())

        assertEquals(LogbookDebit.Waiting, debitOf())

        database.pendingDebitSubmissionDao().markRejected(waitingId, rejectedAt = 5, reason = "refusé")
        assertEquals(NiveauDebit.GROS, debitOf().level)
    }

    @Test
    fun `a report sent from the app without being listed yet is shown as sent`() = runTest {
        repository.save(outing())
        database.debitSendDao().insert(
            DebitSendEntity(canyonId = 26, observationDate = "2026-07-30", sentAt = 1_000, origin = "FORM", confirmed = true),
        )
        database.debitSendDao().insert(
            DebitSendEntity(canyonId = 26, observationDate = "2026-07-30", sentAt = 2_000, origin = "QUEUE", confirmed = false),
        )

        assertEquals(LogbookDebit.Sent(2_000), debitOf())

        database.mySiteReportDao().replaceAll(listOf(report(10)))
        assertEquals(NiveauDebit.GROS, debitOf().level)
    }
}
