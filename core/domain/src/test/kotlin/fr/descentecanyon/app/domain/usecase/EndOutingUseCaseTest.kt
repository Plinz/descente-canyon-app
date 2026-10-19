package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndOutingUseCaseTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val current = MutableStateFlow<PlannedOuting?>(null)
    private val outingRepository = object : OutingRepository {
        override fun observeCurrent() = current
        override suspend fun save(outing: PlannedOuting) {
            current.value = outing
        }
        override suspend fun clear() {
            current.value = null
        }
    }
    private val logbookRepository = mockk<LogbookRepository> { coEvery { record(any()) } returns true }
    private var now = Instant.parse("2026-07-15T02:10:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = this@EndOutingUseCaseTest.zone
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val useCase = EndOutingUseCase(outingRepository, logbookRepository, clock)

    /** Started at 23:30 in Paris on 14 July (21:30 UTC): the logbook keeps the local day. */
    private val outing = PlannedOuting(
        canyonId = 42,
        canyonName = "Riolan",
        start = Instant.parse("2026-07-14T21:30:00Z"),
        expectedBack = Instant.parse("2026-07-15T03:00:00Z"),
        alertAt = Instant.parse("2026-07-15T05:00:00Z"),
    )

    @Test
    fun `the outing goes to the logbook on the day it started, with its duration, and is cleared`() = runTest {
        current.value = outing

        assertEquals(outing, useCase(42))

        coVerify {
            logbookRepository.record(
                LogbookEntry(
                    canyonId = 42,
                    canyonName = "Riolan",
                    date = LocalDate.of(2026, 7, 14),
                    durationMinutes = 4 * 60 + 40,
                    source = LogbookSource.OUTING,
                ),
            )
        }
        assertNull(current.value)
    }

    @Test
    fun `"I'm back" tapped long after the return records no duration`() = runTest {
        current.value = outing
        now = Instant.parse("2026-07-16T09:00:00Z")

        useCase(42)

        coVerify { logbookRepository.record(match { it.durationMinutes == null && it.date == LocalDate.of(2026, 7, 14) }) }
    }

    @Test
    fun `another canyon or no outing changes nothing`() = runTest {
        current.value = outing

        assertNull(useCase(7))
        assertEquals(outing, current.value)

        current.value = null
        assertNull(useCase())
        coVerify(exactly = 0) { logbookRepository.record(any()) }
    }
}
