package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.network.DescenteCanyonWebClient
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonSummary
import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import fr.descentecanyon.app.data.remote.dto.ScrapedForumActiveTopic
import fr.descentecanyon.app.data.remote.dto.ScrapedForumCategory
import fr.descentecanyon.app.data.remote.dto.ScrapedForumUserPost
import fr.descentecanyon.app.data.remote.dto.ScrapedGeoPoint
import fr.descentecanyon.app.data.remote.dto.ScrapedPhoto
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.CanyonInterestRating
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionSessionExpiredException
import fr.descentecanyon.app.domain.model.InterestRatingSessionRequiredException
import fr.descentecanyon.app.domain.model.InterestRatingSubmission
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.RecentDebitCell
import fr.descentecanyon.app.domain.model.WaterTemperature
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrapes canyon data from descente-canyon.com HTML pages.
 * Session cookies from [SessionManager] are attached to all requests when logged in.
 */
@Singleton
class CanyonScraper @Inject constructor(
    private val webClient: DescenteCanyonWebClient,
    private val sessionManager: SessionManager,
) {

    companion object {
        const val BASE_URL = "https://www.descente-canyon.com"
        const val DEFAULT_TIMEOUT_MS = 30_000
    }

    // Rate limit: max 3 concurrent requests to avoid overloading the site
    private val semaphore = Semaphore(3)

    // --- Public API ---

    suspend fun scrapeCanyonSummary(canyonId: Int): Result<ScrapedCanyonDetail> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/canyon/$canyonId/")
                    SummaryParser.parse(doc, canyonId)
                }
            }
        }

    suspend fun scrapeCanyonDescription(canyonId: Int): Result<ScrapedCanyonDetail> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/canyon-description/$canyonId/topo.html")
                    DescriptionParser.parse(doc, canyonId)
                }
            }
        }

    suspend fun scrapeCanyonGeoPoints(canyonId: Int): Result<List<ScrapedGeoPoint>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/canyon-carte/$canyonId/carte.html")
                    GeoPointParser.parse(doc)
                }
            }
        }

    suspend fun scrapeCanyonPhotos(canyonId: Int): Result<List<ScrapedPhoto>> =
        scrapeCanyonPhotos(canyonId = canyonId, timeoutMs = DEFAULT_TIMEOUT_MS)

    suspend fun scrapeCanyonPhotos(canyonId: Int, timeoutMs: Int): Result<List<ScrapedPhoto>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument(
                        url = "$BASE_URL/canyoning/canyon-photo/$canyonId/photographie.html",
                        timeoutMs = timeoutMs,
                    )
                    PhotoParser.parse(doc, canyonId)
                }
            }
        }

    suspend fun scrapeCanyonDebits(canyonId: Int): Result<List<ScrapedDebit>> =
        scrapeCanyonDebits(canyonId = canyonId, timeoutMs = DEFAULT_TIMEOUT_MS)

    suspend fun scrapeCanyonDebits(canyonId: Int, timeoutMs: Int): Result<List<ScrapedDebit>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument(
                        url = "$BASE_URL/canyoning/canyon-debit/$canyonId/observations.html",
                        timeoutMs = timeoutMs,
                    )
                    DebitParser.parseCanyonDebits(doc, canyonId)
                }
            }
        }

    suspend fun scrapeLatestDebits(): Result<List<ScrapedDebit>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/derniers-debits")
                    DebitParser.parseLatestDebits(doc)
                }
            }
        }

    /** Day grid of recent reports of every canyon (last ~16 days), used by the debit model. */
    suspend fun scrapeLatestDebitGrid(): Result<List<RecentDebitCell>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/derniers-debits")
                    DebitParser.parseLatestDebitGrid(doc)
                }
            }
        }

    suspend fun scrapeActiveForumTopics(): Result<List<ScrapedForumActiveTopic>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/forums/search.php?search_id=active_topics")
                    ForumParser.parseActiveTopics(doc)
                }
            }
        }

    suspend fun scrapeForumCategories(): Result<List<ScrapedForumCategory>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/forums/")
                    ForumParser.parseCategories(doc)
                }
            }
        }

    suspend fun scrapeForumUserPosts(authorId: Int): Result<List<ScrapedForumUserPost>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/forums/search.php?author_id=$authorId&sr=posts&sk=t&sd=d")
                    ForumParser.parseUserPosts(doc)
                }
            }
        }

    suspend fun searchCanyons(query: String): Result<List<ScrapedCanyonSummary>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val response = webClient.postDocument(
                        url = "$BASE_URL/canyoning",
                        data = mapOf("q" to query),
                        cookies = sessionManager.getCookies(),
                    )
                    SearchParser.parse(response.document)
                }
            }
        }

    suspend fun scrapeNearbyCanyons(latitude: Double, longitude: Double): Result<List<ScrapedCanyonSummary>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val url = "$BASE_URL/canyoning/proche.html?lat=$latitude&lng=$longitude"
                    val doc = fetchDocument(url)
                    NearbyParser.parse(doc)
                }
            }
        }

    suspend fun scrapeMapIndex(): Result<List<ScrapedCanyonSummary>> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val doc = fetchDocument("$BASE_URL/canyoning/carte-des-canyons")
                    MapIndexParser.parse(doc.outerHtml())
                }
            }
        }

    suspend fun submitDebit(submission: DebitSubmission): Result<Unit> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val url = "$BASE_URL/canyoning/ajout-debit/${submission.canyonId}/formulaire-observation.html"
                    val savedCookies = sessionManager.getCookies()
                    val formResponse = webClient.getDocumentResponse(
                        url = url,
                        cookies = savedCookies,
                    )
                    val requestCookies = savedCookies + formResponse.cookies
                    val form = formResponse.document.selectFirst("form#monformulaire")
                        ?: throw IllegalStateException("Impossible de charger le formulaire de débit.")

                    if (form.isAnonymousDebitForm() && submission.observerEmail == null) {
                        sessionManager.logout()
                        throw DebitSubmissionSessionExpiredException()
                    }

                    val response = webClient.postDocument(
                        url = url,
                        data = form.toDebitSubmissionData(submission),
                        cookies = requestCookies,
                        referer = url,
                        origin = BASE_URL,
                    )

                    val finalUrl = response.finalUrl
                    val doc = response.document
                    val success = finalUrl.contains("/canyoning/canyon-debit/${submission.canyonId}/observations.html") ||
                        doc.selectFirst("form#monformulaire") == null

                    if (!success) {
                        val serverMessage = doc.extractDebitSubmissionError()
                        throw IllegalStateException(
                            serverMessage?.let { "Le formulaire de débit a été refusé par le serveur : $it" }
                                ?: "Le formulaire de débit a été refusé par le serveur.",
                        )
                    }
                }
            }
        }

    suspend fun getInterestRating(canyonId: Int): Result<CanyonInterestRating> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val url = interestRatingUrl(canyonId)
                    val response = webClient.getDocumentResponse(
                        url = url,
                        cookies = sessionManager.getCookies(),
                    )
                    val rating = InterestRatingParser.parse(response.document, canyonId)
                    if (InterestRatingParser.parseForm(response.document) == null) {
                        sessionManager.logout()
                        throw InterestRatingSessionRequiredException()
                    }
                    rating
                }
            }
        }

    suspend fun submitInterestRating(submission: InterestRatingSubmission): Result<Unit> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val url = interestRatingUrl(submission.canyonId)
                    val savedCookies = sessionManager.getCookies()
                    val formResponse = webClient.getDocumentResponse(
                        url = url,
                        cookies = savedCookies,
                    )
                    val form = InterestRatingParser.parseForm(formResponse.document)
                    if (form == null) {
                        sessionManager.logout()
                        throw InterestRatingSessionRequiredException()
                    }

                    val response = webClient.postDocument(
                        url = url,
                        data = form.toInterestRatingSubmissionData(submission),
                        cookies = savedCookies + formResponse.cookies,
                        referer = url,
                        origin = BASE_URL,
                    )

                    val serverMessage = response.document.extractDebitSubmissionError()
                    if (serverMessage != null) {
                        throw IllegalStateException("La note a été refusée par le serveur : $serverMessage")
                    }
                }
            }
        }

    /**
     * Scrape full canyon detail: summary + description + geopoints merged.
     * Acquires only one semaphore permit for the entire composite operation
     * to avoid contention with the 3-permit limit.
     */
    suspend fun scrapeFullCanyonDetail(canyonId: Int): Result<ScrapedCanyonDetail> =
        withContext(Dispatchers.IO) {
            semaphore.withPermit {
                runCatching {
                    val summaryDoc = fetchDocument("$BASE_URL/canyoning/canyon/$canyonId/")
                    val summary = SummaryParser.parse(summaryDoc, canyonId)

                    val descDoc = fetchDocument("$BASE_URL/canyoning/canyon-description/$canyonId/topo.html")
                    val description = DescriptionParser.parse(descDoc, canyonId)

                    val geoPoints = runCatching {
                        val geoDoc = fetchDocument("$BASE_URL/canyoning/canyon-carte/$canyonId/carte.html")
                        GeoPointParser.parse(geoDoc)
                    }.getOrDefault(emptyList())

                    summary.copy(
                        accesAval = description.accesAval,
                        accesAmont = description.accesAmont,
                        approche = description.approche,
                        descente = description.descente,
                        retour = description.retour,
                        engagement = description.engagement,
                        periode = description.periode,
                        geoPoints = geoPoints,
                    )
                }
            }
        }

    // --- Internal ---

    private fun fetchDocument(
        url: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Document {
        return webClient.getDocument(
            url = url,
            cookies = sessionManager.getCookies(),
            timeoutMs = timeoutMs,
        )
    }

    private fun interestRatingUrl(canyonId: Int): String =
        "$BASE_URL/canyoning/canyon-interet/$canyonId/interet.html"

}

private fun InterestRatingForm.toInterestRatingSubmissionData(
    submission: InterestRatingSubmission,
): Map<String, String> {
    return defaults.toMutableMap().apply {
        this["vote"] = String.format(Locale.US, "%.1f", submission.rating.coerceIn(0f, 4f))
        this[submitName] = submitValue
    }
}

private fun ObservationType.toFormValue(): String = when (this) {
    ObservationType.NON_PARCOURU -> "0"
    ObservationType.PARCOURU -> "1"
}

private fun Element.toDebitSubmissionData(submission: DebitSubmission): Map<String, String> {
    return readFormDefaults().toMutableMap().apply {
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
}

private fun Element.readFormDefaults(): Map<String, String> {
    return select("input[name], textarea[name], select[name]")
        .mapNotNull { element ->
            val name = element.attr("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = element.attr("type")
            if (type == "button" || type == "submit" || type == "reset") return@mapNotNull null
            val value = when (element.tagName()) {
                "textarea" -> element.text()
                "select" -> element.selectFirst("option[selected]")?.attr("value").orEmpty()
                else -> element.attr("value")
            }
            name to value
        }
        .toMap()
}

private fun MutableMap<String, String>.putIfFieldPresent(
    form: Element,
    name: String,
    value: String,
) {
    if (form.hasFormControl(name)) {
        this[name] = value
    }
}

private fun Element.hasFormControl(name: String): Boolean {
    return getElementsByAttributeValue("name", name).isNotEmpty()
}

private fun Element.isAnonymousDebitForm(): Boolean {
    return hasFormControl("groupe") && hasFormControl("emailgroupe")
}

private fun Document.extractDebitSubmissionError(): String? {
    return select(".alert-danger, .alert-error")
        .joinToString(" ") { it.text() }
        .trim()
        .takeIf { it.isNotBlank() }
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

// --- Utility extensions ---

internal fun String.extractInt(): Int? =
    replace(Regex("[^\\d]"), "").toIntOrNull()

internal fun String.extractFloat(): Float? =
    replace(",", ".").replace(Regex("[^\\d.]"), "").toFloatOrNull()

internal fun Element.badgeText(pictoClass: String): String? =
    selectFirst("li:has(span.$pictoClass) > span.badge")?.ownText()?.trim()
