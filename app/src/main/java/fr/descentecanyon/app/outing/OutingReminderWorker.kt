package fr.descentecanyon.app.outing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Posts the notification of the outing under way, or the end-of-outing reminder that replaces it,
 * unless the outing was ended or changed meanwhile.
 */
class OutingReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, OutingWorkerEntryPoint::class.java)
        val canyonId = inputData.getInt(KEY_CANYON_ID, 0)
        val expectedBack = inputData.getLong(KEY_EXPECTED_BACK, 0L)
        val current = entryPoint.outingRepository().observeCurrent().first()
        if (current == null || current.canyonId != canyonId || current.expectedBack.toEpochMilli() != expectedBack) {
            return Result.success()
        }
        val publisher = entryPoint.notificationPublisher()
        val canyonName = inputData.getString(KEY_CANYON_NAME).orEmpty()
        when (inputData.getString(KEY_KIND)) {
            KIND_ONGOING -> publisher.publishOutingOngoing(canyonId, canyonName, Instant.ofEpochMilli(expectedBack))
            else -> publisher.publishOutingEndReminder(canyonId, canyonName)
        }
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_CANYON_ID = "canyon_id"
        const val KEY_CANYON_NAME = "canyon_name"
        const val KEY_EXPECTED_BACK = "expected_back"
        const val KIND_ONGOING = "ongoing"
        const val KIND_END = "end"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OutingWorkerEntryPoint {
    fun outingRepository(): OutingRepository

    fun notificationPublisher(): AppNotificationPublisher
}
