package fr.descentecanyon.app.ui.common

import androidx.annotation.StringRes
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.FeedbackRefusal
import fr.descentecanyon.app.domain.model.ProposalRefusal
import fr.descentecanyon.app.domain.model.TrackRejection

/**
 * User message for a typed error.
 *
 * @param fallback message for failures without a more precise explanation, usually phrased for
 * the screen section ("Météo indisponible pour le moment").
 * @param connectivity message for connectivity failures when the section has its own wording.
 */
fun AppError.toUiText(@StringRes fallback: Int = R.string.error_generic, @StringRes connectivity: Int? = null): UiText = when (this) {
    AppError.Offline -> UiText.res(connectivity ?: R.string.error_offline)

    AppError.Timeout -> UiText.res(connectivity ?: R.string.error_timeout)

    AppError.NetworkFailure -> UiText.res(connectivity ?: R.string.error_network)

    AppError.NotFound -> UiText.res(R.string.error_not_found)

    is AppError.Http -> UiText.res(R.string.error_http, statusCode)

    AppError.SiteFormatChanged -> UiText.res(R.string.error_site_changed)

    AppError.UnexpectedResponse -> UiText.res(R.string.error_unexpected_response)

    is AppError.Rejected -> serverMessage?.let { UiText.res(R.string.error_rejected_with_reason, it) }
        ?: UiText.res(R.string.error_rejected)

    AppError.AuthenticationRequired -> UiText.res(R.string.error_authentication_required)

    AppError.SessionExpired -> UiText.res(R.string.error_session_expired)

    AppError.InvalidCredentials -> UiText.res(R.string.error_invalid_credentials)

    AppError.StorageFailure -> UiText.res(R.string.error_storage)

    is AppError.TrackRejected -> UiText.res(reason.messageRes())

    is AppError.ProposalRefused -> UiText.res(reason.messageRes())

    is AppError.FeedbackRefused -> UiText.res(reason.messageRes())

    AppError.DataUnavailable, is AppError.Unknown -> UiText.res(fallback)
}

fun Throwable.toUiText(@StringRes fallback: Int = R.string.error_generic, @StringRes connectivity: Int? = null): UiText =
    toAppError().toUiText(fallback, connectivity)

@StringRes
private fun TrackRejection.messageRes(): Int = when (this) {
    TrackRejection.INVALID_GPX -> R.string.track_error_invalid_gpx
    TrackRejection.TOO_FEW_POINTS -> R.string.track_error_too_few_points
    TrackRejection.TOO_MANY_POINTS -> R.string.track_error_too_many_points
    TrackRejection.TOO_SHORT -> R.string.track_error_too_short
    TrackRejection.TOO_LONG -> R.string.track_error_too_long
    TrackRejection.TOO_FAR -> R.string.track_error_too_far
    TrackRejection.UNLOCATED_CANYON -> R.string.track_error_unlocated_canyon
    TrackRejection.DUPLICATE -> R.string.track_error_duplicate
    TrackRejection.TOO_LARGE -> R.string.track_error_too_large
    TrackRejection.RATE_LIMITED -> R.string.track_error_rate_limited
    TrackRejection.OWN_TRACK -> R.string.track_error_own_track
    TrackRejection.NOT_AUTHOR -> R.string.track_error_not_author
}

@StringRes
private fun FeedbackRefusal.messageRes(): Int = when (this) {
    FeedbackRefusal.TEXT_TOO_SHORT -> R.string.feedback_error_too_short
    FeedbackRefusal.TEXT_TOO_LONG -> R.string.feedback_error_too_long
    FeedbackRefusal.BAD_EMAIL -> R.string.feedback_error_bad_email
    FeedbackRefusal.RATE_LIMITED -> R.string.feedback_error_rate_limited
    FeedbackRefusal.TOO_MANY_OPEN -> R.string.feedback_error_too_many_open
    FeedbackRefusal.BLOCKED -> R.string.feedback_error_blocked
    FeedbackRefusal.INVALID -> R.string.feedback_error_invalid
}

@StringRes
private fun ProposalRefusal.messageRes(): Int = when (this) {
    ProposalRefusal.INVALID -> R.string.proposal_error_invalid
    ProposalRefusal.RATE_LIMITED -> R.string.proposal_error_rate_limited
    ProposalRefusal.TOO_MANY_PENDING -> R.string.proposal_error_too_many_pending
    ProposalRefusal.BLOCKED -> R.string.proposal_error_blocked
    ProposalRefusal.UNKNOWN_CANYON -> R.string.proposal_error_unknown_canyon
    ProposalRefusal.NOT_EDITABLE -> R.string.proposal_error_not_editable
    ProposalRefusal.NO_CHANGE -> R.string.proposal_error_no_change
    ProposalRefusal.ALREADY_DECIDED -> R.string.proposal_error_already_decided
    ProposalRefusal.TOO_LARGE -> R.string.proposal_error_too_large
}
