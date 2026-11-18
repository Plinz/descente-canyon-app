package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.DebitEstimationProgress
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitEstimateRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Flow estimates of a list of canyons, for the search filter. Each one goes through the same
 * pipeline as the canyon page (and fills the same cache), a few at a time so that the weather
 * servers are not flooded.
 */
@Singleton
class DebitEstimateRepositoryImpl @Inject constructor(
    private val canyonRepository: CanyonRepository,
    private val predictionRepository: DebitPredictionRepository,
    private val connectivityObserver: ConnectivityObserver,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DebitEstimateRepository {

    override fun estimate(canyonIds: List<Int>): Flow<DebitEstimationProgress> = channelFlow {
        val total = canyonIds.size
        val estimates = LinkedHashMap<Int, CanyonDebitPredictions>()
        val unavailable = LinkedHashSet<Int>()
        var done = 0
        var offlineSkipped = false
        val state = Mutex()
        val permits = Semaphore(MAX_PARALLEL_ESTIMATES)

        suspend fun report(update: () -> Unit) {
            val progress = state.withLock {
                update()
                done++
                DebitEstimationProgress(total, done, estimates.toMap(), unavailable.toSet(), offlineSkipped)
            }
            send(progress)
        }

        send(DebitEstimationProgress(total, 0, emptyMap(), emptySet()))
        canyonIds.forEach { id ->
            launch {
                permits.withPermit {
                    // The estimate kept on the device covers today: no request at all.
                    val cached = predictionRepository.getCachedPredictions(id)
                    if (cached != null) {
                        report { estimates[id] = cached }
                        return@withPermit
                    }
                    if (!connectivityObserver.isCurrentlyOnline()) {
                        report { offlineSkipped = true }
                        return@withPermit
                    }
                    val detail = canyonRepository.getCanyonPreview(id).getOrNull()
                    if (detail == null || !detail.isLocated) {
                        report { unavailable += id }
                        return@withPermit
                    }
                    val predictions = predictionRepository.getPredictions(detail).getOrNull()
                    report {
                        if (predictions != null) estimates[id] = predictions else unavailable += id
                    }
                }
            }
        }
    }.flowOn(ioDispatcher)

    private companion object {
        const val MAX_PARALLEL_ESTIMATES = 3
    }
}
