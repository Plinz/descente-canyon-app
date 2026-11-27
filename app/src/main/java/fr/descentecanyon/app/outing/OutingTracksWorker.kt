package fr.descentecanyon.app.outing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.repository.OutingTracksPrefetcher
import fr.descentecanyon.app.domain.error.isTransient
import fr.descentecanyon.app.domain.error.toAppError

/**
 * Reads the shared GPS tracks of the canyons of the outings to come as soon as there is network
 * after the outings change (see [OutingTracksPrefetcher]), "leaving now" included.
 */
class OutingTracksWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefetcher = EntryPointAccessors.fromApplication(
            applicationContext,
            OutingTracksEntryPoint::class.java,
        ).outingTracksPrefetcher()
        return prefetcher.refresh().fold(
            onSuccess = { Result.success() },
            onFailure = { if (it.toAppError().isTransient() && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success() },
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OutingTracksEntryPoint {
    fun outingTracksPrefetcher(): OutingTracksPrefetcher
}
