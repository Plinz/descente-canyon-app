package fr.descentecanyon.app.sync

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Limits the "log in to send your reports" notification to one a day: the queue is retried on
 * every connectivity change and would otherwise remind the user again and again.
 */
@Singleton
class PendingDebitLoginReminder @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun isDue(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        nowEpochMs - prefs.getLong(KEY_LAST_SHOWN, 0L) >= REMINDER_INTERVAL_MS

    fun markShown(nowEpochMs: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(KEY_LAST_SHOWN, nowEpochMs) }
    }

    /** The queue no longer waits for a login: the next blocked sync may remind again at once. */
    fun reset() {
        prefs.edit { remove(KEY_LAST_SHOWN) }
    }

    private companion object {
        const val PREFS_NAME = "pending_debit_sync"
        const val KEY_LAST_SHOWN = "login_reminder_shown_at"
        const val REMINDER_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
