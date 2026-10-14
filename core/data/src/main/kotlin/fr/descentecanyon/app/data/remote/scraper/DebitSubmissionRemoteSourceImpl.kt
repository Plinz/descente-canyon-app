package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.mapper.DateParser
import fr.descentecanyon.app.data.network.SiteHttpClient
import fr.descentecanyon.app.data.network.SitePage
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.DebitDelivery
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import javax.inject.Inject
import javax.inject.Singleton
import org.jsoup.nodes.Element

/**
 * Posts a debit report through the site form (`form#monformulaire`).
 *
 * The site gives no explicit acknowledgement, so the outcome is read from where it leads:
 * the observations page means published, the form shown again means refused, any other regular
 * page is checked by reading the observations back. A page that is not a regular site page
 * (maintenance, proxy error...) is a failure, so a queued report is retried later.
 */
@Singleton
class DebitSubmissionRemoteSourceImpl @Inject constructor(
    private val siteClient: SiteHttpClient,
    private val sessionManager: SessionManager,
) : DebitSubmissionRemoteSource {

    override suspend fun submit(submission: DebitSubmission): Result<DebitDelivery> = suspendRunCatching {
        val url = SiteUrls.debitForm(submission.canyonId)
        val formPage = siteClient.get(url)
        val form = formPage.document.selectFirst(FORM_SELECTOR)
            ?: if (formPage.document.isRegularSitePage()) appError(AppError.SiteFormatChanged) else appError(AppError.UnexpectedResponse)

        if (form.isAnonymousDebitForm() && submission.observerEmail == null) {
            // Reports of logged-in users carry no e-mail: an anonymous form means the session is gone.
            sessionManager.failAsAnonymous()
        }

        val response = siteClient.postForm(
            url = url,
            fields = form.toDebitSubmissionData(submission),
            referer = url,
            origin = SiteUrls.BASE_URL,
        )
        when {
            response.isObservationsPageOf(submission.canyonId) -> DebitDelivery.CONFIRMED
            !response.document.isRegularSitePage() -> appError(AppError.UnexpectedResponse)
            isPublished(submission) -> DebitDelivery.CONFIRMED
            response.document.selectFirst(FORM_SELECTOR) != null || response.document.extractSiteErrorMessage() != null ->
                appError(AppError.Rejected(response.document.extractSiteErrorMessage()))
            else -> DebitDelivery.UNCONFIRMED
        }
    }

    private fun SitePage.isObservationsPageOf(canyonId: Int): Boolean =
        finalUrl.contains("/canyoning/canyon-debit/$canyonId/observations.html")

    /** Reads the canyon observations back and looks for the report. */
    private suspend fun isPublished(submission: DebitSubmission): Boolean {
        val observations = suspendRunCatching {
            DebitParser.parseCanyonDebits(siteClient.get(SiteUrls.canyonDebits(submission.canyonId)).document, submission.canyonId)
        }.getOrNull() ?: return false
        val authors = setOfNotNull(submission.observerName.trim(), sessionManager.loggedInUsername?.trim())
            .filter { it.isNotEmpty() }
        return observations.any { observation ->
            DateParser.parseToLocalDate(observation.date) == submission.observationDate &&
                observation.niveauRaw == submission.debitLevel.name &&
                authors.any { author -> observation.auteur?.trim()?.equals(author, ignoreCase = true) == true }
        }
    }

    private companion object {
        const val FORM_SELECTOR = "form#monformulaire"
    }
}

private fun Element.isAnonymousDebitForm(): Boolean = hasFormControl("groupe") && hasFormControl("emailgroupe")

private fun Element.toDebitSubmissionData(submission: DebitSubmission): Map<String, String> = readFormDefaults().toMutableMap().apply {
    putIfFieldPresent(this@toDebitSubmissionData, "groupe", submission.observerName)
    putIfFieldPresent(this@toDebitSubmissionData, "emailgroupe", submission.observerEmail.orEmpty())
    putIfFieldPresent(this@toDebitSubmissionData, "date_mesure", submission.observationDate.toString())
    putIfFieldPresent(this@toDebitSubmissionData, "parcouru", submission.observationType.toFormValue())
    putIfFieldPresent(this@toDebitSubmissionData, "debit", submission.debitLevel.toFormValue())
    putIfFieldPresent(this@toDebitSubmissionData, "eau", submission.waterTemperature.toFormValue())
    putIfFieldPresent(this@toDebitSubmissionData, "air", submission.airTemperature.toFormValue())
    putIfFieldPresent(this@toDebitSubmissionData, "remarque", submission.comment)
    putIfFieldPresent(this@toDebitSubmissionData, "perso", submission.personalComment)
}

private fun MutableMap<String, String>.putIfFieldPresent(form: Element, name: String, value: String) {
    if (form.hasFormControl(name)) {
        this[name] = value
    }
}

private fun ObservationType.toFormValue(): String = when (this) {
    ObservationType.NON_PARCOURU -> "0"
    ObservationType.PARCOURU -> "1"
}

private fun NiveauDebit.toFormValue(): String = when (this) {
    NiveauDebit.CRUE -> "1"
    NiveauDebit.TRES_GROS -> "2"
    NiveauDebit.GROS -> "3"
    NiveauDebit.CORRECT -> "4"
    NiveauDebit.FILET -> "5"
    NiveauDebit.SEC -> "6"
    NiveauDebit.INCONNU -> ""
}

private fun WaterTemperature.toFormValue(): String = when (this) {
    WaterTemperature.CHAUDE -> "1"
    WaterTemperature.DOUCE -> "2"
    WaterTemperature.FROIDE -> "3"
    WaterTemperature.TRES_FROIDE -> "4"
    WaterTemperature.GLACEE -> "5"
    WaterTemperature.INCONNUE -> ""
}

private fun AirTemperature.toFormValue(): String = when (this) {
    AirTemperature.SUPER_CHAUD -> "1"
    AirTemperature.CHAUD -> "2"
    AirTemperature.BON -> "3"
    AirTemperature.FRISQUET -> "4"
    AirTemperature.FROID -> "5"
    AirTemperature.INCONNUE -> ""
}
