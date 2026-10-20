package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.local.user.UserDatabase
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.OutingAnchor
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.OutingPhase
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.testutil.TestDatabases
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutingRepositoryImplTest {

    private val now = Instant.parse("2026-07-14T06:00:00Z")
    private val database: UserDatabase = TestDatabases.user()
    private val repository = OutingRepositoryImpl(database.plannedOutingDao(), Clock.fixed(now, ZoneOffset.UTC))

    @After
    fun tearDown() = database.close()

    private fun outing(canyonId: Int, startInHours: Long) = PlannedOuting(
        canyonId = canyonId,
        canyonName = "Canyon $canyonId",
        start = now.plus(Duration.ofHours(startInHours)),
        expectedBack = now.plus(Duration.ofHours(startInHours + 4)),
        alertAt = now.plus(Duration.ofHours(startInHours + 7)),
        latestBack = now.plus(Duration.ofHours(startInHours + 5)),
    )

    @Test
    fun `outings are kept soonest first with the choices that reopen their plan`() = runTest {
        val choices = OutingChoices(
            date = LocalDate.of(2026, 7, 15),
            anchorTime = LocalTime.of(7, 45),
            anchor = OutingAnchor.LEAVE_PLACE,
            placeId = 3,
            groupSize = 7,
            groupLevel = GroupLevel.DISCOVERY,
            withShuttle = false,
            alertMarginHours = 3,
            alertDelayMinutes = 30,
            adjustments = mapOf(OutingPhase.DESCENT to 30),
            checkedGear = setOf("gear:HELMET", "hint:ROPE"),
            meetAtPlace = true,
            note = "Covoiturage depuis le camping",
        )
        val later = repository.save(outing(42, startInHours = 26).copy(choices = choices, groupSize = 7, relativeInformed = true))
        val sooner = repository.save(outing(7, startInHours = 2).copy(departure = now.plus(Duration.ofHours(1))))

        val outings = repository.observeAll().first()
        assertEquals(listOf(sooner, later), outings.map { it.id })
        assertEquals(choices, outings.last().choices)
        assertEquals(true, outings.last().relativeInformed)
        assertEquals(now.plus(Duration.ofHours(1)), outings.first().departure)
        assertEquals(now.plus(Duration.ofHours(7)), repository.get(sooner)?.latestBack)

        repository.save(outings.first().copy(alertAt = now.plus(Duration.ofHours(10))))
        assertEquals(now.plus(Duration.ofHours(10)), repository.get(sooner)?.alertAt)
        assertEquals(2, repository.observeAll().first().size)

        repository.delete(sooner)
        assertNull(repository.get(sooner))
        repository.clear()
        assertEquals(emptyList<PlannedOuting>(), repository.observeAll().first())
    }

    @Test
    fun `an outing long past its alert is forgotten`() = runTest {
        val old = repository.save(outing(1, startInHours = -30))
        assertEquals(emptyList<PlannedOuting>(), repository.observeAll().first())

        repository.save(outing(2, startInHours = 1))
        assertNull(repository.get(old))
    }
}
