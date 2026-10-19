package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.local.user.UserDatabase
import fr.descentecanyon.app.data.local.user.entity.CanyonNoteEntity
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PersonalDataImport
import fr.descentecanyon.app.testutil.TestDatabases
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PersonalDataRepositoryImplTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC)
    private val source: UserDatabase = TestDatabases.user()
    private val target: UserDatabase = TestDatabases.user()

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun repositoryOf(database: UserDatabase): PersonalDataRepositoryImpl {
        val logbook = LogbookRepositoryImpl(database.logbookDao(), clock)
        return PersonalDataRepositoryImpl(database.logbookDao(), database.canyonNoteDao(), database.favoriteDao(), logbook, clock)
    }

    private suspend fun fillSource() {
        val logbook = LogbookRepositoryImpl(source.logbookDao(), clock)
        logbook.save(
            LogbookEntry(
                canyonId = 26,
                canyonName = "Furon",
                date = LocalDate.of(2026, 7, 30),
                note = "Rappel \"arrosé\", puis\nretour, à pied",
                debitLevel = NiveauDebit.CORRECT,
                companions = "Paul, Léa",
                durationMinutes = 250,
            ),
        )
        logbook.save(LogbookEntry(canyonId = 2283, canyonName = "Ga", date = LocalDate.of(2025, 8, 2)))
        source.canyonNoteDao().upsert(CanyonNoteEntity(canyonId = 26, text = "Parking après le pont", updatedAt = 1_000L))
        source.favoriteDao().insert(FavoriteEntity(canyonId = 26, addedAt = 2_000L))
    }

    @Test
    fun `an export read on another phone brings back outings, memos and favorites`() = runTest {
        fillSource()
        val json = repositoryOf(source).exportJson()

        val result = repositoryOf(target).importJson(json).getOrThrow()

        assertEquals(PersonalDataImport(outings = 2, memos = 1, favorites = 1), result)
        val outings = LogbookRepositoryImpl(target.logbookDao(), clock).observeAll().first()
        assertEquals(listOf(26, 2283), outings.map { it.canyonId })
        assertEquals("Rappel \"arrosé\", puis\nretour, à pied", outings.first().note)
        assertEquals(NiveauDebit.CORRECT, outings.first().debitLevel)
        assertEquals(LogbookSource.FILE, outings.first().source)
        assertEquals("Parking après le pont", target.canyonNoteDao().observe(26).first())
        assertEquals(listOf(26), target.favoriteDao().observeIds().first())
    }

    @Test
    fun `importing the same file twice adds nothing and keeps the memos already written`() = runTest {
        fillSource()
        val json = repositoryOf(source).exportJson()
        target.canyonNoteDao().upsert(CanyonNoteEntity(canyonId = 26, text = "Mon mémo à moi", updatedAt = 5_000L))

        repositoryOf(target).importJson(json)
        val second = repositoryOf(target).importJson(json).getOrThrow()

        assertEquals(PersonalDataImport(outings = 0, memos = 0, favorites = 0), second)
        assertEquals("Mon mémo à moi", target.canyonNoteDao().observe(26).first())
    }

    @Test
    fun `a file that is not an export is refused`() = runTest {
        assertTrue(repositoryOf(target).importJson("""{"hello": 1}""").isFailure)
        assertTrue(repositoryOf(target).importJson("not json").isFailure)
    }

    @Test
    fun `the CSV has one quoted line per outing, oldest first`() = runTest {
        fillSource()

        val lines = repositoryOf(source).exportLogbookCsv().split("\r\n")

        assertEquals("date,canyon,canyon_id,flow,companions,duration_min,note,source", lines[0])
        assertEquals("2025-08-02,Ga,2283,,,,,manual", lines[1])
        assertEquals("2026-07-30,Furon,26,Débit correct,\"Paul, Léa\",250,\"Rappel \"\"arrosé\"\", puis\nretour, à pied\",manual", lines[2])
    }

    @Test
    fun `clearing removes the logbook and memos only`() = runTest {
        fillSource()

        repositoryOf(source).clearLogbookAndMemos()

        assertEquals(emptyList<Any>(), source.logbookDao().getAll())
        assertEquals(emptyList<Any>(), source.canyonNoteDao().getAll())
        assertEquals(listOf(26), source.favoriteDao().observeIds().first())
    }
}
