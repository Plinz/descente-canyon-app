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
import kotlinx.coroutines.flow.first

/** Posts the end-of-outing reminder, unless the outing was ended or changed meanwhile. */
class OutingReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, OutingWorkerEntryPoint::class.java)
        val canyonId = inputData.getInt(KEY_CANYON_ID, 0)
        val expectedBack = inputData.getLong(KEY_EXPECTED_BACK, 0L)
        val current = entryPoint.outingRepository().observeCurrent().first()
        if (current == null || current.canyonId != canyonId || current.expectedBack.toEpochMilli() != expectedBack) {
            return Result.success()
        }
        entryPoint.notificationPublisher().publishOutingEndReminder(canyonId, inputData.getString(KEY_CANYON_NAME).orEmpty())
        return Result.success()
    }

    companion object {
        const val KEY_CANYON_ID = "canyon_id"
        const val KEY_CANYON_NAME = "canyon_name"
        const val KEY_EXPECTED_BACK = "expected_back"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OutingWorkerEntryPoint {
    fun outingRepository(): OutingRepository

    fun notificationPublisher(): AppNotificationPublisher
}
