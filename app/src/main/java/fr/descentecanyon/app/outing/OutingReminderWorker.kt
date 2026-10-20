package fr.descentecanyon.app.outing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher

/**
 * Posts a notification of an outing (under way, end reminder, alert soon) unless the outing was
 * ended or its times changed meanwhile.
 */
class OutingReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, OutingWorkerEntryPoint::class.java)
        val outing = entryPoint.outingRepository().get(inputData.getLong(KEY_OUTING_ID, 0L)) ?: return Result.success()
        val kind = inputData.getString(KEY_KIND) ?: return Result.success()
        // Planned for other times: the outing changed since, its new notifications are scheduled.
        if (referenceTime(kind, outing) != inputData.getLong(KEY_REFERENCE, 0L)) return Result.success()
        val publisher = entryPoint.notificationPublisher()
        when (kind) {
            KIND_ONGOING -> publisher.publishOutingOngoing(outing)
            KIND_END -> publisher.publishOutingEndReminder(outing)
            KIND_ALERT_SOON -> if (outing.relativeInformed) publisher.publishOutingAlertSoon(outing)
        }
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_OUTING_ID = "outing_id"

        /** The time of the outing the notification depends on, as it was when scheduled. */
        const val KEY_REFERENCE = "reference"
        const val KIND_ONGOING = "ongoing"
        const val KIND_END = "end"
        const val KIND_ALERT_SOON = "alert_soon"

        fun referenceTime(kind: String, outing: PlannedOuting): Long = when (kind) {
            KIND_ALERT_SOON -> outing.alertAt
            else -> outing.latestBack
        }.toEpochMilli()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OutingWorkerEntryPoint {
    fun outingRepository(): OutingRepository

    fun notificationPublisher(): AppNotificationPublisher
}
