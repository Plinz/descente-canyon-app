package fr.descentecanyon.app.outing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.repository.OutingEstimateMonitor
import fr.descentecanyon.app.data.repository.OutingTracksPrefetcher
import fr.descentecanyon.app.domain.repository.OutingRepository
import java.time.Clock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Runs [OutingEstimateWorker] every few hours while an outing is planned (the worker only computes
 * the outings of the days estimated, so the evening before and the morning of each), and once right
 * away when the outings change: the estimate is then saved on the phone before leaving. The shared
 * tracks of the canyons of the outings not over yet are read once the network is there
 * ([OutingTracksWorker]), whenever those canyons change, then again with the estimates.
 */
@Singleton
class OutingEstimateScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val outingRepository: OutingRepository,
    private val monitor: OutingEstimateMonitor,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            outingRepository.observeAll()
                .map { outings -> outings.map { Triple(it.id, it.start, it.canyonIds) }.takeIf { monitor.isWatched(outings) } }
                .distinctUntilChanged()
                .collect { upcoming -> schedule(hasUpcoming = upcoming != null) }
        }
        scope.launch {
            outingRepository.observeAll()
                .map { outings -> OutingTracksPrefetcher.canyonsToKeep(outings, clock.instant()).toSet() }
                .distinctUntilChanged()
                .collect { canyonIds -> if (canyonIds.isNotEmpty()) scheduleTracks() }
        }
    }

    private fun scheduleTracks() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            TRACKS_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OutingTracksWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
    }

    private fun schedule(hasUpcoming: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!hasUpcoming) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OutingEstimateWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OutingEstimateWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
    }

    private companion object {
        /** Every 3 hours: the evening before and the morning of an outing both get a fresh estimate. */
        const val PERIODIC_HOURS = 3L
        const val BACKOFF_MINUTES = 15L
        const val PERIODIC_WORK_NAME = "outing-estimate-refresh"
        const val IMMEDIATE_WORK_NAME = "outing-estimate-refresh-now"
        const val TRACKS_WORK_NAME = "outing-tracks-prefetch"
    }
}
