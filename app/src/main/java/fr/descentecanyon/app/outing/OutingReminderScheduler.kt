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
 * Follows the planned outings whose follow-up is on. From the drive to the canyon and during each one, a silent
 * notification keeps the emergency screen one tap away, lock screen included. At its latest return,
 * a reminder asks how it went and offers to report the flow (a fresh report is the best input of
 * everyone's flow estimates). When a relative received the plan, a reminder comes shortly before the
 * alert time.
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
        scope.launch { outingRepository.observeAll().collect(::schedule) }
    }

    /** Schedules the outings once, when the app is not running (after a reboot or an update). */
    suspend fun restore() = schedule(outingRepository.observeAll().first())

    private fun schedule(planned: List<PlannedOuting>) {
        // Drafts are only kept: the user has not turned their follow-up on.
        val outings = planned.filter { it.tracked }
        val workManager = WorkManager.getInstance(context)
        // Outings ended, cancelled or back to drafts: nothing about them is left, scheduled or shown.
        workManager.cancelAllWorkByTag(OUTING_WORK_TAG)
        notificationPublisher.cancelOutingNotificationsExcept(outings.mapTo(mutableSetOf()) { it.id })
        val now = clock.instant()
        outings.forEach { outing ->
            // Past reminders are not replayed: the outing is over or was planned after the fact. A
            // reminder already shown stays until the user is back.
            if (now.isBefore(outing.latestBack)) {
                enqueue(OutingReminderWorker.KIND_ONGOING, outing, at = outing.departure ?: outing.start.minus(ONGOING_LEAD), now = now)
                enqueue(OutingReminderWorker.KIND_END, outing, at = outing.latestBack, now = now)
            }
            val alertSoon = outing.alertAt.minus(ALERT_LEAD)
            if (outing.relativeInformed && now.isBefore(alertSoon)) {
                enqueue(OutingReminderWorker.KIND_ALERT_SOON, outing, at = alertSoon, now = now)
            }
        }
    }

    private fun enqueue(kind: String, outing: PlannedOuting, at: Instant, now: Instant) {
        val delay = Duration.between(now, at).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<OutingReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(OUTING_WORK_TAG)
            .setInputData(
                workDataOf(
                    OutingReminderWorker.KEY_KIND to kind,
                    OutingReminderWorker.KEY_OUTING_ID to outing.id,
                    OutingReminderWorker.KEY_REFERENCE to OutingReminderWorker.referenceTime(kind, outing),
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("outing-$kind-${outing.id}", ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val OUTING_WORK_TAG = "outing"

        /** Without a known drive, the notification is there an hour before the walk. */
        val ONGOING_LEAD: Duration = Duration.ofHours(1)

        /** Time to call or text the relative before they worry. */
        val ALERT_LEAD: Duration = Duration.ofMinutes(30)
    }
}
