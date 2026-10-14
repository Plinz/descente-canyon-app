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
import fr.descentecanyon.app.domain.model.RejectedDebitSubmission
import fr.descentecanyon.app.domain.model.TrackedActivityEvent
import fr.descentecanyon.app.ui.MainActivity
import fr.descentecanyon.app.ui.navigation.AppLaunchTarget
import fr.descentecanyon.app.ui.navigation.putLaunchTarget
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
            ),
        )
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

    /** Tells the user that debit reports stored offline were refused by the site and dropped. */
    fun publishRejectedDebitSubmissions(rejected: List<RejectedDebitSubmission>): Boolean {
        if (rejected.isEmpty()) return true
        if (!canPostNotifications(DEBIT_CHANNEL_ID)) return false
        val first = rejected.first()
        val body = if (rejected.size == 1) {
            val date = first.observationDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            val reason = (first.error as? AppError.Rejected)?.serverMessage
            listOfNotNull(context.getString(R.string.notification_debit_rejected_body, date), reason).joinToString("\n")
        } else {
            context.resources.getQuantityString(R.plurals.notification_debit_rejected_group_body, rejected.size, rejected.size)
        }
        val notification = NotificationCompat.Builder(context, DEBIT_CHANNEL_ID)
            .setSmallIcon(fr.descentecanyon.app.R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_debit_rejected_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(canyonDetailPendingIntent(first.canyonId))
            .build()
        notifySafely(DEBIT_REJECTED_NOTIFICATION_ID, notification)
        return true
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
        const val DEBIT_NOTIFICATION_ID = 2001
        const val FORUM_NOTIFICATION_ID = 2002
        const val DEBIT_REJECTED_NOTIFICATION_ID = 2003
    }
}
