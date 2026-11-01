package fr.descentecanyon.app.outing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** An alarm of an outing rang: its notification is posted, unless the outing ended or changed since. */
class OutingAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val kind = intent.getStringExtra(OutingReminderDelivery.KEY_KIND) ?: return
        val outingId = intent.getLongExtra(OutingReminderDelivery.KEY_OUTING_ID, 0L)
        val reference = intent.getLongExtra(OutingReminderDelivery.KEY_REFERENCE, 0L)
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, OutingAlarmEntryPoint::class.java)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                OutingReminderDelivery.deliver(entryPoint.outingRepository(), entryPoint.notificationPublisher(), kind, outingId, reference)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION = "fr.descentecanyon.app.action.OUTING_REMINDER"
    }
}

/** What each reminder of an outing shows, and when it no longer applies. */
internal object OutingReminderDelivery {
    const val KEY_KIND = "kind"
    const val KEY_OUTING_ID = "outing_id"

    /** The time of the outing the notification depends on, as it was when scheduled. */
    const val KEY_REFERENCE = "reference"
    const val KIND_ONGOING = "ongoing"
    const val KIND_END = "end"
    const val KIND_ALERT_SOON = "alert_soon"

    /**
     * The alert time the relative holds: the one of the last message confirmed gone. A later time
     * not confirmed yet (no signal in the canyon) does not count: the relative would call at this one.
     */
    fun relativeAlertAt(outing: PlannedOuting): Instant? = outing.informedAlertAt

    fun referenceTime(kind: String, outing: PlannedOuting): Long = when (kind) {
        KIND_ALERT_SOON -> relativeAlertAt(outing) ?: outing.alertAt
        else -> outing.latestBack
    }.toEpochMilli()

    suspend fun deliver(
        outingRepository: OutingRepository,
        publisher: AppNotificationPublisher,
        kind: String,
        outingId: Long,
        reference: Long,
    ) {
        // Removed since: nothing to show.
        val outing = outingRepository.get(outingId) ?: return
        // Planned for other times: the outing changed since, its new alarms are set.
        if (referenceTime(kind, outing) != reference) return
        when (kind) {
            KIND_ONGOING -> publisher.publishOutingOngoing(outing)
            KIND_END -> publisher.publishOutingEndReminder(outing)
            KIND_ALERT_SOON -> if (outing.relativeInformed) publisher.publishOutingAlertSoon(outing)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OutingAlarmEntryPoint {
    fun outingRepository(): OutingRepository

    fun notificationPublisher(): AppNotificationPublisher
}
