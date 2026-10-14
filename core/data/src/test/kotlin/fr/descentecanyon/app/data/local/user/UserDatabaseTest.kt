package fr.descentecanyon.app.data.local.user

import android.app.Application
import fr.descentecanyon.app.data.local.user.entity.DebitEntity
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.testutil.TestDatabases
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
class UserDatabaseTest {

    private val database = TestDatabases.user()

    @After
    fun tearDown() = database.close()

    @Test
    fun `favorites are listed most recent first and can be removed`() = runTest {
        val favorites = database.favoriteDao()
        favorites.insert(FavoriteEntity(canyonId = 1, addedAt = 10))
        favorites.insert(FavoriteEntity(canyonId = 2, addedAt = 20))
        favorites.insert(FavoriteEntity(canyonId = 1, addedAt = 30)) // already there: ignored

        assertEquals(listOf(2, 1), favorites.observeIds().first())
        assertTrue(favorites.observeIsFavorite(1).first())

        favorites.delete(1)
        assertFalse(favorites.observeIsFavorite(1).first())
    }

    @Test
    fun `cached debits of a canyon are replaced as a whole`() = runTest {
        val debits = database.debitDao()
        debits.insertAll(listOf(debit(1, "2026-05-01"), debit(1, "2026-05-02"), debit(2, "2026-05-03")))

        debits.replaceForCanyon(1, listOf(debit(1, "2026-06-01")))

        assertEquals(listOf("2026-06-01"), debits.getByCanyonId(1).first().map { it.date })
        assertEquals(1, debits.getByCanyonId(2).first().size)
    }

    @Test
    fun `pending submissions copied twice are not duplicated`() = runTest {
        val pending = database.pendingDebitSubmissionDao()
        val submission = PendingDebitSubmissionEntity(
            id = 7,
            canyonId = 1,
            observerName = "Antoine",
            observerEmail = null,
            observationDate = "2026-05-11",
            isDescended = true,
            debitLevel = "CORRECT",
            waterTemperature = "FROIDE",
            airTemperature = "BON",
            comment = "",
        )

        pending.insertAllIfAbsent(listOf(submission))
        pending.insertAllIfAbsent(listOf(submission))

        assertEquals(listOf(7L), pending.getAll().map { it.id })
    }

    private fun debit(canyonId: Int, date: String) = DebitEntity(canyonId = canyonId, date = date, niveau = "CORRECT")
}
