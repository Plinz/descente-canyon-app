package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.network.SiteHttpClient
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonInterestRating
import fr.descentecanyon.app.domain.model.InterestRatingSubmission
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Reads and posts the personal interest rating of a canyon. The page only shows the voting form
 * to identified users; a vote counts as saved once the page shows it back.
 */
@Singleton
class InterestRatingRemoteSourceImpl @Inject constructor(
    private val siteClient: SiteHttpClient,
    private val sessionManager: SessionManager,
) : InterestRatingRemoteSource {

    override suspend fun get(canyonId: Int): Result<CanyonInterestRating> = suspendRunCatching {
        val page = siteClient.get(SiteUrls.interestRating(canyonId))
        if (InterestRatingParser.parseForm(page.document) == null) sessionManager.failAsAnonymous()
        InterestRatingParser.parse(page.document, canyonId)
    }

    override suspend fun submit(submission: InterestRatingSubmission): Result<Unit> = suspendRunCatching {
        val url = SiteUrls.interestRating(submission.canyonId)
        val formPage = siteClient.get(url)
        val form = InterestRatingParser.parseForm(formPage.document) ?: sessionManager.failAsAnonymous()

        val response = siteClient.postForm(
            url = url,
            fields = form.toSubmissionData(submission),
            referer = url,
            origin = SiteUrls.BASE_URL,
        )
        response.document.extractSiteErrorMessage()?.let { message -> appError(AppError.Rejected(message)) }
        if (!response.document.isRegularSitePage()) appError(AppError.UnexpectedResponse)

        val expected = submission.normalizedRating()
        if (!InterestRatingParser.parse(response.document, submission.canyonId).shows(expected)) {
            // The answer may be another page: read the rating back before concluding.
            val readBack = InterestRatingParser.parse(siteClient.get(url).document, submission.canyonId)
            if (!readBack.shows(expected)) appError(AppError.UnexpectedResponse)
        }
    }

    private fun CanyonInterestRating.shows(rating: Float): Boolean = personalRating?.let { abs(it - rating) <= RATING_TOLERANCE } == true

    private fun InterestRatingForm.toSubmissionData(submission: InterestRatingSubmission): Map<String, String> =
        defaults.toMutableMap().apply {
            this["vote"] = String.format(Locale.US, "%.1f", submission.normalizedRating())
            this[submitName] = submitValue
        }

    private fun InterestRatingSubmission.normalizedRating(): Float = rating.coerceIn(0f, 4f)

    private companion object {
        const val RATING_TOLERANCE = 0.051f
    }
}
