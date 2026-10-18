package fr.descentecanyon.app.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Whether the runtime notification permission (Android 13+) still has to be requested. */
fun Context.needsNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/**
 * Runs a "follow" toggle, asking first for the notification permission when it is missing: a follow
 * without notifications would silently bring nothing. Unfollowing ([isFollowed] true) never asks.
 */
@Composable
fun rememberFollowWithNotificationPermission(): (isFollowed: Boolean, toggle: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingToggle by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Followed either way: the user can still read the activity in the notification center.
        pendingToggle?.invoke()
        pendingToggle = null
    }
    return { isFollowed, toggle ->
        if (!isFollowed && context.needsNotificationPermission()) {
            pendingToggle = toggle
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggle()
        }
    }
}
