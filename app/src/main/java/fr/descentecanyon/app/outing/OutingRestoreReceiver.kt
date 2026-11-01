package fr.descentecanyon.app.outing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A reboot or an app update removes the notifications and the alarms: the notification of the outing
 * under way, which gives access to the emergency screen from the lock screen, comes back without
 * opening the app, and the reminders are set again. So are they once "alarms and reminders" is granted.
 */
@AndroidEntryPoint
class OutingRestoreReceiver : BroadcastReceiver() {
    @Inject lateinit var outingReminderScheduler: OutingReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                outingReminderScheduler.restore()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // Granted later: the alarms set inexact are set again at the minute.
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
