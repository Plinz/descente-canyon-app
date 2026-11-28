package fr.descentecanyon.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.repository.FeedbackOutboxScheduler
import fr.descentecanyon.app.domain.error.isTransient
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.answeredSince
import fr.descentecanyon.app.domain.repository.FeedbackRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** The messages of the idea box written without network leave as soon as the phone is online again. */
@Singleton
class WorkManagerFeedbackOutboxScheduler @Inject constructor(@param:ApplicationContext private val context: Context) :
    FeedbackOutboxScheduler {

    override fun scheduleSend() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FeedbackOutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
    }

    private companion object {
        const val WORK_NAME = "feedback-outbox"
        const val BACKOFF_MINUTES = 15L
    }
}

/**
 * Tells the author when a message of the idea box was answered or decided: checked with the server's
 * manifest (twice a day), only while a message is open. An answer already seen in the app is not
 * told again.
 */
@Singleton
class FeedbackReplyCheck @Inject constructor(private val feedback: FeedbackRepository, private val publisher: AppNotificationPublisher) {
    suspend fun run() {
        val before = feedback.observeMine().first()
        if (before.none { !it.isQueued && it.status.isOpen }) return
        val after = feedback.refreshMine().getOrNull() ?: return
        publisher.publishFeedbackAnswers(answeredSince(before, after))
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeedbackWorkerEntryPoint {
    fun feedbackRepository(): FeedbackRepository
}

class FeedbackOutboxWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(applicationContext, FeedbackWorkerEntryPoint::class.java).feedbackRepository()
        return repository.sendQueued().fold(
            onSuccess = { Result.success() },
            onFailure = { if (it.toAppError().isTransient() && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure() },
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FeedbackSyncModule {
    @Binds
    @Singleton
    abstract fun bindFeedbackOutboxScheduler(impl: WorkManagerFeedbackOutboxScheduler): FeedbackOutboxScheduler
}
