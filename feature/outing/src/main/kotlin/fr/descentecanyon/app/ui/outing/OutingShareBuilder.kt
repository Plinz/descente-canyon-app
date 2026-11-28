package fr.descentecanyon.app.ui.outing

import fr.descentecanyon.app.domain.model.MessageAudience
import fr.descentecanyon.app.domain.model.MessageSection
import fr.descentecanyon.app.domain.model.SharedContent
import fr.descentecanyon.app.domain.model.SharedNextCanyon
import fr.descentecanyon.app.domain.model.SharedOuting
import fr.descentecanyon.app.domain.model.SharedOutingSummary
import fr.descentecanyon.app.domain.model.SharedPlace
import fr.descentecanyon.app.domain.model.SharedTiming

/**
 * The outing on screen as a link carries it. [asRoute]: without its day nor its times, for someone to
 * plan it on a day of their own. The starting place goes only when the team meets there: a home stays
 * on the phone.
 */
internal fun OutingPlanUiState.toSharedOuting(asRoute: Boolean = false): SharedOuting? {
    val canyon = canyon?.canyon ?: return null
    val schedule = schedule ?: return null
    val meetingPlace = place?.takeIf { !asRoute && choices.meetAtPlace && schedule.leavePlace != null }
    val meet = (if (meetingPlace != null) schedule.leavePlace else schedule.arriveParking)?.toLocalTime() ?: return null
    val back = schedule.backAtCar
    return SharedOuting(
        canyonId = canyon.id,
        next = nextCanyons.map { SharedNextCanyon(it.canyon.canyonId, it.canyon.name, it.canyon.link, it.canyon.adjustments) },
        date = choices.date.takeIf { !asRoute },
        anchorTime = meet,
        meetAtPlace = meetingPlace != null,
        place = meetingPlace?.let { SharedPlace(it.name, it.latitude, it.longitude) },
        groupSize = choices.groupSize,
        groupLevel = choices.groupLevel,
        withShuttle = choices.withShuttle,
        adjustments = choices.adjustments,
        // A route takes the pace of whoever plans it; a day out keeps this phone's times for everyone.
        timing = if (asRoute) null else SharedTiming(settings, pace.personalPace?.ratio),
        note = choices.note.trim().take(SharedOuting.MAX_NOTE),
        summary = SharedOutingSummary(
            names = listOf(canyon.nom) + nextCanyons.map { it.canyon.name },
            meet = meet.takeIf { !asRoute },
            walk = schedule.walkStart.toLocalTime().takeIf { !asRoute },
            back = back?.estimate?.toLocalTime()?.takeIf { !asRoute },
            backLatest = back?.latest?.toLocalTime()?.takeIf { !asRoute },
        ),
    )
}

/** What a link of this outing does, once it is made. */
sealed interface LinkUse {
    val asRoute: Boolean get() = false

    /** A message of the "Notify" sheet that carries the outing's link. */
    data class Message(val audience: MessageAudience, val sections: Set<MessageSection>) : LinkUse

    /** The link alone, sent through a messaging app. */
    data class Share(override val asRoute: Boolean) : LinkUse

    /** The link shown as a QR code: the outing travels whole in it, read without network. */
    data class Qr(override val asRoute: Boolean) : LinkUse

    /** The outing changed since its link was sent: the link shows the new plan. */
    data object Refresh : LinkUse
}

/** A link ready for [use]: [isShort] when the server keeps the outing, else it carries the outing whole. */
data class PreparedLink(val use: LinkUse, val url: String, val isShort: Boolean)

/** The outing added from someone's link changed there: [version] of [code] (null for a link without code). */
data class LinkUpdate(val outing: SharedOuting, val code: String?, val version: Int)

/** The fingerprint of what this outing's link would show now. */
internal fun SharedOuting.fingerprint(): String = fr.descentecanyon.app.domain.model.ShareCodec.fingerprint(SharedContent.Outing(this))
