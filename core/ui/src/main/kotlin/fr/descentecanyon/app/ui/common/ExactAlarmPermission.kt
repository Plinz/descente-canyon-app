package fr.descentecanyon.app.ui.common

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Whether "Alarms and reminders" (Android 12+) is missing: without it the reminders of an outing
 * (return, alert time of the relative) may come minutes late while the phone sleeps.
 */
fun Context.needsExactAlarmPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
    getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == false

/** Opens the system page where the app is allowed "Alarms and reminders". */
fun Context.openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    startActivitySafely(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
}
