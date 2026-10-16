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
 * A reboot or an app update removes the notifications: the one of the outing under way, which gives
 * access to the emergency screen from the lock screen, comes back without opening the app.
 */
@AndroidEntryPoint
class OutingRestoreReceiver : BroadcastReceiver() {
    @Inject lateinit var outingReminderScheduler: OutingReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                outingReminderScheduler.restore()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
