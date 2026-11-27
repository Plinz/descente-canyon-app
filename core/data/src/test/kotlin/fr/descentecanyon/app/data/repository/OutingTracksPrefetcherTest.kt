package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.AppServerState
import fr.descentecanyon.app.domain.model.NextCanyon
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.ServerFeatures
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.CommunityTrackRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OutingTracksPrefetcherTest {

    private val now = Instant.parse("2026-07-14T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val outings = MutableStateFlow<List<PlannedOuting>>(emptyList())
    private val outingRepository = mockk<OutingRepository> { every { observeAll() } returns outings }
    private val tracks = mockk<CommunityTrackRepository> { coEvery { prefetch(any(), any()) } returns Result.success(2) }
    private val server = MutableStateFlow(AppServerState(isConfigured = true))
    private val appServer = mockk<AppServerRepository> { every { observeState() } returns server }
    private var online = true
    private val connectivity = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } answers { online } }
    private val prefetcher = OutingTracksPrefetcher(outingRepository, tracks, appServer, connectivity, clock)

    private fun outing(id: Long, canyonId: Int, startHours: Long, backHours: Long, next: List<Int> = emptyList()) = PlannedOuting(
        canyonId = canyonId,
        canyonName = "Canyon $canyonId",
        start = now.plusSeconds(startHours * 3600),
        expectedBack = now.plusSeconds(backHours * 3600),
        alertAt = now.plusSeconds((backHours + 3) * 3600),
        choices = OutingChoices(
            date = LocalDate.of(2026, 7, 14),
            anchorTime = LocalTime.NOON,
            nextCanyons = next.map { NextCanyon(canyonId = it, name = "Canyon $it") },
        ),
        id = id,
    )

    @Test
    fun `the canyons of the outings not over yet are kept, chained ones included, the soonest first`() {
        val kept = OutingTracksPrefetcher.canyonsToKeep(
            listOf(
                outing(id = 1, canyonId = 30, startHours = 48, backHours = 54),
                // Over: its tracks are not needed any more.
                outing(id = 2, canyonId = 10, startHours = -8, backHours = -2),
                // In progress ("leaving now"), then a canyon after it.
                outing(id = 3, canyonId = 20, startHours = -1, backHours = 4, next = listOf(21, 30)),
            ),
            now,
        )

        assertEquals(listOf(20, 21, 30), kept)
    }

    @Test
    fun `the tracks are read only with the server's tracks on and the network`() = runTest {
        outings.value = listOf(outing(id = 1, canyonId = 30, startHours = 48, backHours = 54))

        assertEquals(2, prefetcher.refresh().getOrThrow())
        coVerify(exactly = 1) { tracks.prefetch(listOf(30), OutingTracksPrefetcher.MAX_AGE) }

        online = false
        assertEquals(0, prefetcher.refresh().getOrThrow())
        online = true
        server.value = AppServerState(isConfigured = true, features = ServerFeatures(communityTracks = false))
        assertEquals(0, prefetcher.refresh().getOrThrow())
        server.value = AppServerState(isConfigured = true, isEnabled = false)
        assertEquals(0, prefetcher.refresh().getOrThrow())
        coVerify(exactly = 1) { tracks.prefetch(any(), any()) }
    }

    @Test
    fun `without an outing to come nothing is asked`() = runTest {
        outings.value = listOf(outing(id = 2, canyonId = 10, startHours = -8, backHours = -2))

        assertEquals(0, prefetcher.refresh().getOrThrow())
        coVerify(exactly = 0) { tracks.prefetch(any(), any()) }
    }
}
