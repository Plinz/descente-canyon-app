package fr.descentecanyon.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.RejectedDebitSubmission
import fr.descentecanyon.app.domain.model.SentDebitSubmission
import fr.descentecanyon.app.domain.model.TrackedActivityEvent
import fr.descentecanyon.app.ui.EmergencyActivity
import fr.descentecanyon.app.ui.MainActivity
import fr.descentecanyon.app.ui.navigation.AppLaunchTarget
import fr.descentecanyon.app.ui.navigation.LaunchSection
import fr.descentecanyon.app.ui.navigation.putLaunchTarget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppNotificationPublisher @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun ensureChannelsCreated() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    DEBIT_CHANNEL_ID,
                    context.getString(R.string.notification_channel_debits_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_debits_body)
                },
                NotificationChannel(
                    FORUM_CHANNEL_ID,
                    context.getString(R.string.notification_channel_forum_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_forum_body)
                },
                NotificationChannel(
                    CONTRIBUTIONS_CHANNEL_ID,
                    context.getString(R.string.notification_channel_contributions_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_contributions_body)
                },
                NotificationChannel(
                    OUTINGS_CHANNEL_ID,
                    context.getString(R.string.notification_channel_outings_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_outings_body)
                },
            ),
        )
    }

    /**
     * During an outing: a silent notification that opens the emergency screen, over the lock screen
     * too, and ends the outing. The lock screen hides the canyon name when content is hidden there.
     */
    fun publishOutingOngoing(outing: PlannedOuting): Boolean {
        if (!canPostNotifications(OUTINGS_CHANNEL_ID)) return false
        val name = outing.canyonName.ifBlank { context.getString(R.string.canyon_fallback_title, outing.canyonId) }
        val back = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(outing.expectedBack.atZone(ZoneId.systemDefault()))
        val body = context.getString(R.string.notification_outing_ongoing_body, back)
        val emergency = emergencyPendingIntent(outing.canyonId)
        val publicVersion = NotificationCompat.Builder(context, OUTINGS_CHANNEL_ID)
            .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_outing_ongoing_public_title))
            .setContentText(body)
            .setContentIntent(emergency)
            .addAction(emergencyAction(emergency))
            .build()
        val notification = NotificationCompat.Builder(context, OUTINGS_CHANNEL_ID)
            .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_outing_ongoing_title, name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(emergency)
            .addAction(emergencyAction(emergency))
            .addAction(outingBackAction(outing))
            .build()
        notifySafely(outingNotificationId(OUTING_ONGOING_NOTIFICATION_BASE, outing.id), notification)
        return true
    }

    /** At the latest return of an outing: back safely? A report of the flow helps everyone. */
    fun publishOutingEndReminder(outing: PlannedOuting): Boolean {
        NotificationManagerCompat.from(context).cancel(outingNotificationId(OUTING_ONGOING_NOTIFICATION_BASE, outing.id))
        if (!canPostNotifications(OUTINGS_CHANNEL_ID)) return false
        val name = outing.canyonName.ifBlank { context.getString(R.string.canyon_fallback_title, outing.canyonId) }
        val body = context.getString(R.string.notification_outing_end_body, name)
        val notification = NotificationCompat.Builder(context, OUTINGS_CHANNEL_ID)
            .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_outing_end_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(debitFormPendingIntent(outing.canyonId))
            .addAction(outingBackAction(outing))
            .addAction(emergencyAction(emergencyPendingIntent(outing.canyonId)))
            .build()
        notifySafely(outingNotificationId(OUTING_END_NOTIFICATION_BASE, outing.id), notification)
        return true
    }

    /**
     * Shortly before the alert time of an outing whose plan went to a relative: back already, or late?
     * The notification opens the plan, where the alert can be pushed back and the relative told.
     */
    fun publishOutingAlertSoon(outing: PlannedOuting): Boolean {
        if (!canPostNotifications(OUTINGS_CHANNEL_ID)) return false
        val alert = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(outing.alertAt.atZone(ZoneId.systemDefault()))
        val body = context.getString(R.string.notification_outing_alert_body, alert)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putLaunchTarget(AppLaunchTarget.OutingPlan(outing.id, outing.canyonId))
        val planIntent = PendingIntent.getActivity(
            context,
            OUTING_PLAN_REQUEST_CODE_BASE + outing.id.toInt() % OUTING_ID_RANGE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, OUTINGS_CHANNEL_ID)
            .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_outing_alert_title, alert))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(planIntent)
            .addAction(outingBackAction(outing))
            .build()
        notifySafely(outingNotificationId(OUTING_ALERT_NOTIFICATION_BASE, outing.id), notification)
        return true
    }

    /** The outing was ended or cancelled: nothing about it stays in the notification shade. */
    fun cancelOutingNotifications(outingId: Long) {
        NotificationManagerCompat.from(context).run {
            cancel(outingNotificationId(OUTING_ONGOING_NOTIFICATION_BASE, outingId))
            cancel(outingNotificationId(OUTING_END_NOTIFICATION_BASE, outingId))
            cancel(outingNotificationId(OUTING_ALERT_NOTIFICATION_BASE, outingId))
        }
    }

    /** Removes the notifications of outings that no longer exist (ended elsewhere, or before an update). */
    fun cancelOutingNotificationsExcept(outingIds: Set<Long>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val kept = outingIds.flatMap { id ->
            listOf(OUTING_ONGOING_NOTIFICATION_BASE, OUTING_END_NOTIFICATION_BASE, OUTING_ALERT_NOTIFICATION_BASE).map {
                outingNotificationId(it, id)
            }
        }.toSet()
        manager.activeNotifications
            .filter { it.id in LEGACY_OUTING_NOTIFICATION_IDS || (isOutingNotificationId(it.id) && it.id !in kept) }
            .forEach { manager.cancel(it.id) }
    }

    fun publishDebitEvents(events: List<TrackedActivityEvent>): Boolean {
        if (events.isEmpty()) return true
        if (!canPostNotifications(DEBIT_CHANNEL_ID)) return false
        val notification = if (events.size == 1) {
            buildSingleDebitNotification(events.first())
        } else {
            buildGroupedDebitNotification(events)
        }
        notifySafely(DEBIT_NOTIFICATION_ID, notification)
        return true
    }

    fun publishForumEvents(events: List<TrackedActivityEvent>): Boolean {
        if (events.isEmpty()) return true
        if (!canPostNotifications(FORUM_CHANNEL_ID)) return false
        val notification = if (events.size == 1) {
            buildSingleForumNotification(events.first())
        } else {
            buildGroupedForumNotification(events)
        }
        notifySafely(FORUM_NOTIFICATION_ID, notification)
        return true
    }

    /** Confirms that debit reports stored offline reached the site. */
    fun publishSentDebitSubmissions(sent: List<SentDebitSubmission>, canyonNames: Map<Int, String>): Boolean {
        if (sent.isEmpty()) return true
        if (!canPostNotifications(CONTRIBUTIONS_CHANNEL_ID)) return false
        val builder = NotificationCompat.Builder(context, CONTRIBUTIONS_CHANNEL_ID)
            .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
        val notification = if (sent.size == 1) {
            val report = sent.single()
            val line = reportLine(report.canyonId, report.observationDate, canyonNames)
            val body = if (report.confirmed) {
                line
            } else {
                "$line\n${context.getString(R.string.notification_debit_sent_unconfirmed)}"
            }
            builder
                .setContentTitle(context.getString(R.string.notification_debit_sent_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(canyonDetailPendingIntent(report.canyonId))
                .build()
        } else {
            val lines = sent.map { reportLine(it.canyonId, it.observationDate, canyonNames) }
            builder
                .setContentTitle(context.resources.getQuantityString(R.plurals.notification_debit_sent_group_title, sent.size, sent.size))
                .setContentText(lines.joinToString(separator = " • "))
                .setStyle(NotificationCompat.InboxStyle().also { style -> lines.take(5).forEach(style::addLine) })
                .setContentIntent(appPendingIntent())
                .build()
        }
        notifySafely(DEBIT_SENT_NOTIFICATION_ID, notification)
        return true
    }

    /** Tells the user that debit reports stored offline were refused by the site; they stay in the queue screen. */
    fun publishRejectedDebitSubmissions(rejected: List<RejectedDebitSubmission>, canyonNames: Map<Int, String> = emptyMap()): Boolean {
        if (rejected.isEmpty()) return true
        if (!canPostNotifications(CONTRIBUTIONS_CHANNEL_ID)) return false
        val first = rejected.first()
        val summary = if (rejected.size == 1) {
            val date = first.observationDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            val reason = (first.error as? AppError.Rejected)?.serverMessage
            listOfNotNull(canyonNames[first.canyonId], context.getString(R.string.notification_debit_rejected_body, date), reason)
                .joinToString("\n")
        } else {
            context.resources.getQuantityString(R.plurals.notification_debit_rejected_group_body, rejected.size, rejected.size)
        }
        val body = "$summary\n${context.getString(R.string.notification_debit_rejected_hint)}"
        val notification = NotificationCompat.Builder(context, CONTRIBUTIONS_CHANNEL_ID)
            .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_debit_rejected_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(sectionPendingIntent(LaunchSection.PENDING_DEBITS))
            .build()
        notifySafely(DEBIT_REJECTED_NOTIFICATION_ID, notification)
        return true
    }

    /** Debit reports written with an account wait for a login: opens the queue, which offers it. */
    fun publishPendingDebitsLoginReminder(pendingCount: Int): Boolean {
        if (!canPostNotifications(CONTRIBUTIONS_CHANNEL_ID)) return false
        val body = context.resources.getQuantityString(R.plurals.notification_pending_login_body, pendingCount, pendingCount)
        val notification = NotificationCompat.Builder(context, CONTRIBUTIONS_CHANNEL_ID)
            .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_pending_login_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(sectionPendingIntent(LaunchSection.PENDING_DEBITS))
            .build()
        notifySafely(PENDING_LOGIN_NOTIFICATION_ID, notification)
        return true
    }

    fun cancelPendingDebitsLoginReminder() {
        NotificationManagerCompat.from(context).cancel(PENDING_LOGIN_NOTIFICATION_ID)
    }

    private fun reportLine(canyonId: Int, observationDate: LocalDate, canyonNames: Map<Int, String>): String {
        val canyon = canyonNames[canyonId] ?: context.getString(R.string.canyon_fallback_title, canyonId)
        return "$canyon · ${observationDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
    }

    private fun buildSingleDebitNotification(event: TrackedActivityEvent) = NotificationCompat.Builder(context, DEBIT_CHANNEL_ID)
        .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
        .setContentTitle(event.title)
        .setContentText(event.body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
        .setAutoCancel(true)
        .apply {
            event.canyonId?.let { setContentIntent(canyonDetailPendingIntent(it)) }
        }
        .build()

    private fun buildGroupedDebitNotification(events: List<TrackedActivityEvent>) = NotificationCompat.Builder(context, DEBIT_CHANNEL_ID)
        .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
        .setContentTitle(context.resources.getQuantityString(R.plurals.notification_debit_group_title, events.size, events.size))
        .setContentText(events.joinToString(separator = " • ") { it.title })
        .setStyle(
            NotificationCompat.InboxStyle().also { style ->
                events.take(5).forEach { event ->
                    style.addLine("${event.title} • ${event.body}")
                }
            },
        )
        .setAutoCancel(true)
        .setContentIntent(notificationCenterPendingIntent())
        .build()

    private fun buildSingleForumNotification(event: TrackedActivityEvent) = NotificationCompat.Builder(context, FORUM_CHANNEL_ID)
        .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
        .setContentTitle(event.title)
        .setContentText(event.body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
        .setAutoCancel(true)
        .apply {
            event.externalUrl?.let { setContentIntent(browserPendingIntent(it)) }
        }
        .build()

    private fun buildGroupedForumNotification(events: List<TrackedActivityEvent>) = NotificationCompat.Builder(context, FORUM_CHANNEL_ID)
        .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
        .setContentTitle(context.resources.getQuantityString(R.plurals.notification_forum_group_title, events.size, events.size))
        .setContentText(events.joinToString(separator = " • ") { it.forumName ?: it.title })
        .setStyle(
            NotificationCompat.InboxStyle().also { style ->
                events.take(5).forEach { event ->
                    style.addLine("${event.forumName ?: context.getString(R.string.home_feed_forum)} • ${event.title}")
                }
            },
        )
        .setAutoCancel(true)
        .setContentIntent(notificationCenterPendingIntent())
        .build()

    private fun canyonDetailPendingIntent(canyonId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putLaunchTarget(AppLaunchTarget.CanyonDetail(canyonId = canyonId, openDebitsTab = true, clearBackStack = true))
        return PendingIntent.getActivity(
            context,
            canyonId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun debitFormPendingIntent(canyonId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putLaunchTarget(AppLaunchTarget.DebitForm(canyonId))
        return PendingIntent.getActivity(
            context,
            DEBIT_FORM_REQUEST_CODE_BASE + canyonId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun emergencyPendingIntent(canyonId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        EMERGENCY_REQUEST_CODE_BASE + canyonId,
        EmergencyActivity.intent(context, canyonId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun emergencyAction(intent: PendingIntent): NotificationCompat.Action = NotificationCompat.Action.Builder(
        fr.descentecanyon.app.R.drawable.ic_shortcut_emergency,
        context.getString(R.string.outing_emergency_action),
        intent,
    ).build()

    /** "I'm back": ends the outing and opens the flow report form. */
    private fun outingBackAction(outing: PlannedOuting): NotificationCompat.Action {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putLaunchTarget(AppLaunchTarget.OutingBack(outing.id, outing.canyonId))
        val pendingIntent = PendingIntent.getActivity(
            context,
            OUTING_BACK_REQUEST_CODE_BASE + outing.id.toInt() % OUTING_ID_RANGE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            fr.descentecanyon.app.R.drawable.ic_launcher_foreground,
            context.getString(R.string.outing_end_action),
            pendingIntent,
        ).build()
    }

    private fun notificationCenterPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putLaunchTarget(AppLaunchTarget.Notifications(clearBackStack = true))
        return PendingIntent.getActivity(
            context,
            10_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun sectionPendingIntent(section: LaunchSection): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putLaunchTarget(AppLaunchTarget.Section(section))
        return PendingIntent.getActivity(
            context,
            SECTION_REQUEST_CODE_BASE + section.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun appPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            APP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun browserPendingIntent(url: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        return PendingIntent.getActivity(
            context,
            url.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(channelId: String): Boolean {
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(channelId)
            ?.importance != NotificationManager.IMPORTANCE_NONE
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(notificationId: Int, notification: android.app.Notification) {
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }.getOrElse { throwable ->
            if (throwable !is SecurityException) {
                throw throwable
            }
        }
    }

    private companion object {
        const val DEBIT_CHANNEL_ID = "debit_updates"
        const val FORUM_CHANNEL_ID = "forum_updates"
        const val CONTRIBUTIONS_CHANNEL_ID = "my_contributions"
        const val OUTINGS_CHANNEL_ID = "outings"
        const val DEBIT_NOTIFICATION_ID = 2001
        const val FORUM_NOTIFICATION_ID = 2002
        const val DEBIT_REJECTED_NOTIFICATION_ID = 2003
        const val DEBIT_SENT_NOTIFICATION_ID = 2004
        const val PENDING_LOGIN_NOTIFICATION_ID = 2005

        /** The single outing notifications of the versions before several outings. */
        val LEGACY_OUTING_NOTIFICATION_IDS = setOf(2006, 2007)

        /** Each outing has its notifications: base + outing id within [OUTING_ID_RANGE]. */
        const val OUTING_ONGOING_NOTIFICATION_BASE = 3_000_000
        const val OUTING_END_NOTIFICATION_BASE = 3_100_000
        const val OUTING_ALERT_NOTIFICATION_BASE = 3_200_000
        const val OUTING_ID_RANGE = 100_000
        const val OUTING_PLAN_REQUEST_CODE_BASE = 50_000_000

        fun outingNotificationId(base: Int, outingId: Long): Int = base + (outingId % OUTING_ID_RANGE).toInt()

        fun isOutingNotificationId(id: Int): Boolean =
            id in OUTING_ONGOING_NOTIFICATION_BASE until OUTING_ALERT_NOTIFICATION_BASE + OUTING_ID_RANGE
        const val DEBIT_FORM_REQUEST_CODE_BASE = 20_000_000
        const val EMERGENCY_REQUEST_CODE_BASE = 30_000_000
        const val OUTING_BACK_REQUEST_CODE_BASE = 40_000_000
        const val APP_REQUEST_CODE = 10_001
        const val SECTION_REQUEST_CODE_BASE = 10_100
    }
}
