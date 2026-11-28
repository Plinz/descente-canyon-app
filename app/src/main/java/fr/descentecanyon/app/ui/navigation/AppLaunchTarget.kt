package fr.descentecanyon.app.ui.navigation

import android.content.Intent
import android.net.Uri
import fr.descentecanyon.app.domain.model.AppLink
import fr.descentecanyon.app.domain.model.AppLinks
import fr.descentecanyon.app.domain.model.CanyonSection

sealed interface AppLaunchTarget {
    data object None : AppLaunchTarget
    data class Notifications(val clearBackStack: Boolean = false) : AppLaunchTarget

    /** A canyon page, on its flow reports (notification) or on the [section] a link names. */
    data class CanyonDetail(
        val canyonId: Int,
        val openDebitsTab: Boolean,
        val clearBackStack: Boolean = false,
        val section: CanyonSection? = null,
    ) : AppLaunchTarget

    /** The map of a canyon, over its page, on a point or a shared GPS track (a link). */
    data class CanyonMap(val canyonId: Int, val latitude: Double? = null, val longitude: Double? = null, val trackId: String? = null) :
        AppLaunchTarget

    /** Something shared through the server, by its code (a link): an outing, a stay, a list of canyons. */
    data class Share(val code: String) : AppLaunchTarget

    /** An outing carried whole by a link (a QR code). */
    data class OutingInLink(val canyonId: Int, val payload: String) : AppLaunchTarget

    /** The flow report form of a canyon, over its page (end-of-outing reminder). */
    data class DebitForm(val canyonId: Int) : AppLaunchTarget

    /** "I'm back" from an outing notification: the outing ends, then the flow seen is reported. */
    data class OutingBack(val outingId: Long, val canyonId: Int) : AppLaunchTarget

    /** The plan of a saved outing, to push its alert back or end it (notification before the alert). */
    data class OutingPlan(val outingId: Long, val canyonId: Int) : AppLaunchTarget

    /** A screen without arguments, opened from a notification or a launcher shortcut. */
    data class Section(val section: LaunchSection, val clearBackStack: Boolean = true) : AppLaunchTarget
}

enum class LaunchSection {
    SEARCH,
    MAP,
    FAVORITES,
    PENDING_DEBITS,

    /** The user space (proposed canyons, logbook), from a notification about a proposal. */
    MY_ACCOUNT,
}

fun Intent.putLaunchTarget(target: AppLaunchTarget): Intent = when (target) {
    AppLaunchTarget.None -> this

    // Links: the intent's own address carries them.
    is AppLaunchTarget.CanyonMap, is AppLaunchTarget.Share, is AppLaunchTarget.OutingInLink -> this

    is AppLaunchTarget.Notifications -> putExtra(EXTRA_LAUNCH_TARGET_KIND, TARGET_NOTIFICATIONS)
        .putExtra(EXTRA_CLEAR_BACKSTACK, target.clearBackStack)

    is AppLaunchTarget.CanyonDetail -> putExtra(EXTRA_LAUNCH_TARGET_KIND, TARGET_CANYON_DETAIL)
        .putExtra(EXTRA_CANYON_ID, target.canyonId)
        .putExtra(EXTRA_OPEN_DEBITS_TAB, target.openDebitsTab)
        .putExtra(EXTRA_CLEAR_BACKSTACK, target.clearBackStack)

    is AppLaunchTarget.DebitForm -> putExtra(EXTRA_LAUNCH_TARGET_KIND, TARGET_DEBIT_FORM)
        .putExtra(EXTRA_CANYON_ID, target.canyonId)

    is AppLaunchTarget.OutingBack -> putExtra(EXTRA_LAUNCH_TARGET_KIND, TARGET_OUTING_BACK)
        .putExtra(EXTRA_CANYON_ID, target.canyonId)
        .putExtra(EXTRA_OUTING_ID, target.outingId)

    is AppLaunchTarget.OutingPlan -> putExtra(EXTRA_LAUNCH_TARGET_KIND, TARGET_OUTING_PLAN)
        .putExtra(EXTRA_CANYON_ID, target.canyonId)
        .putExtra(EXTRA_OUTING_ID, target.outingId)

    is AppLaunchTarget.Section -> putExtra(EXTRA_LAUNCH_TARGET_KIND, TARGET_SECTION)
        .putExtra(EXTRA_SECTION, target.section.name)
        .putExtra(EXTRA_CLEAR_BACKSTACK, target.clearBackStack)
}

/** The target of [intent], read once: its extras are removed (the link stays, see MainScreen). */
fun consumeLaunchTarget(intent: Intent?, appLinks: AppLinks = AppLinks.NONE): AppLaunchTarget {
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

        TARGET_DEBIT_FORM -> intent.getIntExtra(EXTRA_CANYON_ID, 0)
            .takeIf { it > 0 }
            ?.let { AppLaunchTarget.DebitForm(it) }
            ?: AppLaunchTarget.None

        TARGET_OUTING_BACK -> outingTarget(intent, AppLaunchTarget::OutingBack)

        TARGET_OUTING_PLAN -> outingTarget(intent, AppLaunchTarget::OutingPlan)

        TARGET_SECTION -> LaunchSection.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SECTION) }
            ?.let { section ->
                AppLaunchTarget.Section(section = section, clearBackStack = intent.getBooleanExtra(EXTRA_CLEAR_BACKSTACK, true))
            }
            ?: AppLaunchTarget.None

        else -> parseLaunchTargetFromUri(intent.data, appLinks)
    }
    intent.removeExtra(EXTRA_LAUNCH_TARGET_KIND)
    intent.removeExtra(EXTRA_CANYON_ID)
    intent.removeExtra(EXTRA_OPEN_DEBITS_TAB)
    intent.removeExtra(EXTRA_CLEAR_BACKSTACK)
    intent.removeExtra(EXTRA_SECTION)
    intent.removeExtra(EXTRA_OUTING_ID)
    return target
}

private fun outingTarget(intent: Intent, target: (Long, Int) -> AppLaunchTarget): AppLaunchTarget {
    val canyonId = intent.getIntExtra(EXTRA_CANYON_ID, 0)
    val outingId = intent.getLongExtra(EXTRA_OUTING_ID, 0L)
    return if (canyonId > 0 && outingId > 0) target(outingId, canyonId) else AppLaunchTarget.None
}

internal fun parseLaunchTargetFromUri(uri: Uri?, appLinks: AppLinks = AppLinks.NONE): AppLaunchTarget =
    parseLaunchTargetFromUrl(uri?.toString(), appLinks)

/** A canyon page of the site, or a link of the app's own domain ([appLinks]). */
internal fun parseLaunchTargetFromUrl(url: String?, appLinks: AppLinks = AppLinks.NONE): AppLaunchTarget {
    val normalizedUrl = url.orEmpty()
    appLinks.parse(normalizedUrl)?.let { return it.toLaunchTarget() }
    val match = DESCENTE_CANYON_DETAIL_URL_REGEX.matchEntire(normalizedUrl) ?: return AppLaunchTarget.None
    val canyonId = match.groupValues[1].toIntOrNull() ?: return AppLaunchTarget.None
    return AppLaunchTarget.CanyonDetail(
        canyonId = canyonId,
        openDebitsTab = false,
    )
}

internal fun AppLink.toLaunchTarget(): AppLaunchTarget = when (this) {
    is AppLink.Canyon -> AppLaunchTarget.CanyonDetail(canyonId, openDebitsTab = false, section = section)
    is AppLink.CanyonMap -> AppLaunchTarget.CanyonMap(canyonId, latitude, longitude, trackId)
    is AppLink.Share -> AppLaunchTarget.Share(code)
    is AppLink.OutingInLink -> AppLaunchTarget.OutingInLink(canyonId, payload)
}

private const val EXTRA_LAUNCH_TARGET_KIND = "launch_target_kind"
private const val EXTRA_CANYON_ID = "launch_target_canyon_id"
private const val EXTRA_OPEN_DEBITS_TAB = "launch_target_open_debits_tab"
private const val EXTRA_CLEAR_BACKSTACK = "launch_target_clear_backstack"
private const val EXTRA_SECTION = "launch_target_section"
private const val EXTRA_OUTING_ID = "launch_target_outing_id"
private const val TARGET_NOTIFICATIONS = "notifications"
private const val TARGET_CANYON_DETAIL = "canyon_detail"
private const val TARGET_SECTION = "section"
private const val TARGET_DEBIT_FORM = "debit_form"
private const val TARGET_OUTING_BACK = "outing_back"
private const val TARGET_OUTING_PLAN = "outing_plan"
private val DESCENTE_CANYON_DETAIL_URL_REGEX = Regex(
    pattern = """https://(?:www\.)?descente-canyon\.com/canyoning/canyon/(\d+)(?:[/?#].*)?""",
    option = RegexOption.IGNORE_CASE,
)
