package fr.descentecanyon.app.ui.common

import androidx.annotation.StringRes
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.toAppError

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

    AppError.DataUnavailable, is AppError.Unknown -> UiText.res(fallback)
}

fun Throwable.toUiText(@StringRes fallback: Int = R.string.error_generic, @StringRes connectivity: Int? = null): UiText =
    toAppError().toUiText(fallback, connectivity)
