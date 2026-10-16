package fr.descentecanyon.app.outing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Follows the planned outing. On the way and during it, a silent notification keeps the emergency
 * screen one tap away, lock screen included. At the expected return, a reminder asks how it went
 * and offers to report the flow (a fresh report is the best input of everyone's flow estimates).
 */
@Singleton
class OutingReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val outingRepository: OutingRepository,
    private val notificationPublisher: AppNotificationPublisher,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            outingRepository.observeCurrent().collect { outing ->
                if (outing == null) cancel() else schedule(outing)
            }
        }
    }

    /** Schedules the current outing once, when the app is not running (after a reboot or an update). */
    suspend fun restore() {
        val outing = outingRepository.observeCurrent().first()
        if (outing == null) cancel() else schedule(outing)
    }

    private fun schedule(outing: PlannedOuting) {
        val now = clock.instant()
        // Past reminders are not replayed: the outing is over or was planned after the fact. The end
        // reminder already shown stays until the user is back.
        if (!now.isBefore(outing.expectedBack)) return cancelWork()
        enqueue(ONGOING_WORK_NAME, OutingReminderWorker.KIND_ONGOING, outing, at = outing.start.minus(ONGOING_LEAD), now = now)
        enqueue(END_WORK_NAME, OutingReminderWorker.KIND_END, outing, at = outing.expectedBack, now = now)
    }

    private fun enqueue(name: String, kind: String, outing: PlannedOuting, at: Instant, now: Instant) {
        val delay = Duration.between(now, at).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<OutingReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    OutingReminderWorker.KEY_KIND to kind,
                    OutingReminderWorker.KEY_CANYON_ID to outing.canyonId,
                    OutingReminderWorker.KEY_CANYON_NAME to outing.canyonName,
                    OutingReminderWorker.KEY_EXPECTED_BACK to outing.expectedBack.toEpochMilli(),
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    /** No outing any more: nothing about it is left, scheduled or shown. */
    private fun cancel() {
        cancelWork()
        notificationPublisher.cancelOutingNotifications()
    }

    private fun cancelWork() {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(ONGOING_WORK_NAME)
            cancelUniqueWork(END_WORK_NAME)
        }
    }

    private companion object {
        const val ONGOING_WORK_NAME = "outing-ongoing"
        const val END_WORK_NAME = "outing-end-reminder"

        /** The notification is there from the drive to the canyon. */
        val ONGOING_LEAD: Duration = Duration.ofHours(1)
    }
}
