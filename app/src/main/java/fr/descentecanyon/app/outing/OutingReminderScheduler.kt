package fr.descentecanyon.app.outing

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Follows the planned outings. From the drive to the canyon and during each one, a silent
 * notification keeps the emergency screen one tap away, lock screen included. At its latest return,
 * a reminder asks how it went and offers to report the flow (a fresh report is the best input of
 * everyone's flow estimates). When a relative received the plan, a reminder comes shortly before the
 * alert time they hold.
 *
 * The reminders are alarms, not background jobs: a phone left still in the car or in a dry bag goes
 * into deep sleep, where a job can wait for hours while the relative's alert time passes. With the
 * "alarms and reminders" permission they ring at the minute; without it they still wake the phone,
 * within the few minutes the system allows.
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

    /** Schedules the outings once, when the app is not running (reboot, update, permission granted). */
    suspend fun restore() = schedule(outingRepository.observeAll().first())

    private fun schedule(outings: List<PlannedOuting>) {
        // Versions up to 2.8 queued the reminders as background jobs: they would come twice.
        WorkManager.getInstance(context).cancelAllWorkByTag(LEGACY_WORK_TAG)
        // Outings ended or removed: nothing about them is shown. Their alarms may still ring: the
        // receiver finds the outing gone (or its times changed) and shows nothing.
        notificationPublisher.cancelOutingNotificationsExcept(outings.mapTo(mutableSetOf()) { it.id })
        val now = clock.instant()
        outings.forEach { outing ->
            // Past reminders are not replayed: the outing is over or was planned after the fact. A
            // reminder already shown stays until the user is back.
            if (now.isBefore(outing.latestBack)) {
                setAlarm(OutingReminderDelivery.KIND_ONGOING, outing, at = outing.departure ?: outing.start.minus(ONGOING_LEAD), now = now)
                setAlarm(OutingReminderDelivery.KIND_END, outing, at = outing.latestBack, now = now)
            }
            val heldAlert = OutingReminderDelivery.relativeAlertAt(outing) ?: return@forEach
            val alertSoon = heldAlert.minus(ALERT_LEAD)
            if (now.isBefore(alertSoon)) setAlarm(OutingReminderDelivery.KIND_ALERT_SOON, outing, at = alertSoon, now = now)
        }
    }

    private fun setAlarm(kind: String, outing: PlannedOuting, at: Instant, now: Instant) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = maxOf(at, now).toEpochMilli()
        // The same request code for an outing and a kind: setting it again replaces the previous one.
        val intent = Intent(context, OutingAlarmReceiver::class.java)
            .setAction(OutingAlarmReceiver.ACTION)
            .setData("descentecanyon://outing/${outing.id}/$kind".toUri())
            .putExtra(OutingReminderDelivery.KEY_KIND, kind)
            .putExtra(OutingReminderDelivery.KEY_OUTING_ID, outing.id)
            .putExtra(OutingReminderDelivery.KEY_REFERENCE, OutingReminderDelivery.referenceTime(kind, outing))
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (canScheduleExactAlarms(alarmManager)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            // Inexact, but it still fires while the phone sleeps, within a window after the time asked.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, inexactTriggerAt(triggerAt, now.toEpochMilli()), pendingIntent)
        }
    }

    private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private companion object {
        const val LEGACY_WORK_TAG = "outing"

        /** Without a known drive, the notification is there an hour before the walk. */
        val ONGOING_LEAD: Duration = Duration.ofHours(1)

        /** Time to call or text the relative before they worry. */
        val ALERT_LEAD: Duration = Duration.ofMinutes(30)
    }
}

/** The longest delay Android gives an inexact alarm (Android 12 and later). */
private const val INEXACT_MAX_WINDOW_MS = 60 * 60 * 1000L

/**
 * Android delivers an inexact alarm up to three quarters of the wait after the time asked, an hour
 * at most: asked that much earlier, a reminder comes before its time (the relative's alert) rather
 * than after it.
 */
internal fun inexactTriggerAt(triggerAt: Long, now: Long): Long {
    val wait = (triggerAt - now).coerceAtLeast(0)
    return triggerAt - minOf(wait * 3 / 4, INEXACT_MAX_WINDOW_MS)
}
