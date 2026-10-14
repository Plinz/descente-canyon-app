package fr.descentecanyon.app.ui.navigation

import android.content.Intent
import android.net.Uri

sealed interface AppLaunchTarget {
    data object None : AppLaunchTarget
    data class Notifications(val clearBackStack: Boolean = false) : AppLaunchTarget
    data class CanyonDetail(val canyonId: Int, val openDebitsTab: Boolean, val clearBackStack: Boolean = false) : AppLaunchTarget
}

fun Intent.putLaunchTarget(target: AppLaunchTarget): Intent = when (target) {
    AppLaunchTarget.None -> this
    is AppLaunchTarget.Notifications -> putExtra(EXTRA_LAUNCH_TARGET_KIND, TARGET_NOTIFICATIONS)
        .putExtra(EXTRA_CLEAR_BACKSTACK, target.clearBackStack)
    is AppLaunchTarget.CanyonDetail -> putExtra(EXTRA_LAUNCH_TARGET_KIND, TARGET_CANYON_DETAIL)
        .putExtra(EXTRA_CANYON_ID, target.canyonId)
        .putExtra(EXTRA_OPEN_DEBITS_TAB, target.openDebitsTab)
        .putExtra(EXTRA_CLEAR_BACKSTACK, target.clearBackStack)
}

fun consumeLaunchTarget(intent: Intent?): AppLaunchTarget {
    if (intent == null) return AppLaunchTarget.None
    val target = when (intent.getStringExtra(EXTRA_LAUNCH_TARGET_KIND)) {
        TARGET_NOTIFICATIONS -> AppLaunchTarget.Notifications(
            clearBackStack = intent.getBooleanExtra(EXTRA_CLEAR_BACKSTACK, false),
        )
        TARGET_CANYON_DETAIL -> {
            val canyonId = intent.getIntExtra(EXTRA_CANYON_ID, 0)
            if (canyonId > 0) {
                AppLaunchTarget.CanyonDetail(
                    canyonId = canyonId,
                    openDebitsTab = intent.getBooleanExtra(EXTRA_OPEN_DEBITS_TAB, false),
                    clearBackStack = intent.getBooleanExtra(EXTRA_CLEAR_BACKSTACK, false),
                )
            } else {
                AppLaunchTarget.None
            }
        }

        else -> parseLaunchTargetFromUri(intent.data)
    }
    intent.removeExtra(EXTRA_LAUNCH_TARGET_KIND)
    intent.removeExtra(EXTRA_CANYON_ID)
    intent.removeExtra(EXTRA_OPEN_DEBITS_TAB)
    intent.removeExtra(EXTRA_CLEAR_BACKSTACK)
    return target
}

internal fun parseLaunchTargetFromUri(uri: Uri?): AppLaunchTarget = parseLaunchTargetFromUrl(uri?.toString())

internal fun parseLaunchTargetFromUrl(url: String?): AppLaunchTarget {
    val normalizedUrl = url.orEmpty()
    val match = DESCENTE_CANYON_DETAIL_URL_REGEX.matchEntire(normalizedUrl) ?: return AppLaunchTarget.None
    val canyonId = match.groupValues[1].toIntOrNull() ?: return AppLaunchTarget.None
    return AppLaunchTarget.CanyonDetail(
        canyonId = canyonId,
        openDebitsTab = false,
    )
}

private const val EXTRA_LAUNCH_TARGET_KIND = "launch_target_kind"
private const val EXTRA_CANYON_ID = "launch_target_canyon_id"
private const val EXTRA_OPEN_DEBITS_TAB = "launch_target_open_debits_tab"
private const val EXTRA_CLEAR_BACKSTACK = "launch_target_clear_backstack"
private const val TARGET_NOTIFICATIONS = "notifications"
private const val TARGET_CANYON_DETAIL = "canyon_detail"
private val DESCENTE_CANYON_DETAIL_URL_REGEX = Regex(
    pattern = """https://(?:www\.)?descente-canyon\.com/canyoning/canyon/(\d+)(?:[/?#].*)?""",
    option = RegexOption.IGNORE_CASE,
)
