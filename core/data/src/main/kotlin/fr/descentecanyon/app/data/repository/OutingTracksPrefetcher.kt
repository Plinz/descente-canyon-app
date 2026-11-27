package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.CommunityTrackRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Keeps on the phone the shared GPS tracks of the canyons of the outings to come (chained canyons
 * included), read while there is network: the approach and the way back are often walked out of it.
 */
@Singleton
class OutingTracksPrefetcher @Inject constructor(
    private val outingRepository: OutingRepository,
    private val trackRepository: CommunityTrackRepository,
    private val appServerRepository: AppServerRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) {
    /** Reads the tracks of the canyons of the outings not over yet, unless read lately; returns how many canyons were read. */
    suspend fun refresh(): Result<Int> {
        if (!appServerRepository.observeState().first().communityTracksAvailable) return Result.success(0)
        if (!connectivityObserver.isCurrentlyOnline()) return Result.success(0)
        val canyonIds = canyonsToKeep(outingRepository.observeAll().first(), clock.instant())
        if (canyonIds.isEmpty()) return Result.success(0)
        return trackRepository.prefetch(canyonIds, MAX_AGE)
    }

    companion object {
        /** Read again twice a day at most: new tracks and votes reach the phone before the outing. */
        val MAX_AGE: Duration = Duration.ofHours(12)

        /** The canyons of the outings not over at [now] (planned, or in progress), each once. */
        fun canyonsToKeep(outings: List<PlannedOuting>, now: Instant): List<Int> =
            outings.filter { it.latestBack.isAfter(now) }.sortedBy { it.start }.flatMap { it.canyonIds }.distinct()
    }
}
