package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.network.SiteHttpClient
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import fr.descentecanyon.app.data.remote.dto.ScrapedForumActiveTopic
import fr.descentecanyon.app.data.remote.dto.ScrapedForumCategory
import fr.descentecanyon.app.data.remote.dto.ScrapedForumUserPost
import fr.descentecanyon.app.data.remote.dto.ScrapedPhoto
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.RecentDebitCell
import javax.inject.Inject
import javax.inject.Singleton
import org.jsoup.nodes.Element

/**
 * Reads canyon, debit and forum pages of descente-canyon.com. The session (when the user is
 * logged in) is attached by the site client's cookie jar. Failures are typed [AppError]s.
 */
@Singleton
class CanyonScraper @Inject constructor(private val siteClient: SiteHttpClient) {

    suspend fun scrapeCanyonPhotos(canyonId: Int, timeoutMs: Long = SiteHttpClient.DEFAULT_TIMEOUT_MS): Result<List<ScrapedPhoto>> =
        suspendRunCatching {
            PhotoParser.parse(siteClient.get(SiteUrls.canyonPhotos(canyonId), timeoutMs).document, canyonId)
        }

    suspend fun scrapeCanyonDebits(canyonId: Int, timeoutMs: Long = SiteHttpClient.DEFAULT_TIMEOUT_MS): Result<List<ScrapedDebit>> =
        suspendRunCatching {
            DebitParser.parseCanyonDebits(siteClient.get(SiteUrls.canyonDebits(canyonId), timeoutMs).document, canyonId)
        }

    suspend fun scrapeLatestDebits(): Result<List<ScrapedDebit>> = suspendRunCatching {
        DebitParser.parseLatestDebits(siteClient.get(SiteUrls.LATEST_DEBITS).document)
    }

    /** Day grid of recent reports of every canyon (last ~16 days), used by the debit model. */
    suspend fun scrapeLatestDebitGrid(): Result<List<RecentDebitCell>> = suspendRunCatching {
        DebitParser.parseLatestDebitGrid(siteClient.get(SiteUrls.LATEST_DEBITS).document)
    }

    suspend fun scrapeActiveForumTopics(): Result<List<ScrapedForumActiveTopic>> = suspendRunCatching {
        ForumParser.parseActiveTopics(siteClient.get(SiteUrls.FORUM_ACTIVE_TOPICS).document)
    }

    suspend fun scrapeForumCategories(): Result<List<ScrapedForumCategory>> = suspendRunCatching {
        ForumParser.parseCategories(siteClient.get(SiteUrls.FORUM_CATEGORIES).document)
    }

    suspend fun scrapeForumUserPosts(authorId: Int): Result<List<ScrapedForumUserPost>> = suspendRunCatching {
        ForumParser.parseUserPosts(siteClient.get(SiteUrls.forumUserPosts(authorId)).document)
    }

    /** Full canyon detail: summary, description (topo) and GPS points merged. */
    suspend fun scrapeFullCanyonDetail(canyonId: Int): Result<ScrapedCanyonDetail> = suspendRunCatching {
        val summary = SummaryParser.parse(siteClient.get(SiteUrls.canyon(canyonId)).document, canyonId)
        val description = DescriptionParser.parse(siteClient.get(SiteUrls.canyonDescription(canyonId)).document, canyonId)
        // The map page is optional: a canyon without GPS points keeps the summary and topo.
        val geoPoints = suspendRunCatching {
            GeoPointParser.parse(siteClient.get(SiteUrls.canyonMap(canyonId)).document)
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

// --- Utility extensions shared by the parsers ---

internal fun String.extractInt(): Int? = replace(Regex("[^\\d]"), "").toIntOrNull()

internal fun String.extractFloat(): Float? = replace(",", ".").replace(Regex("[^\\d.]"), "").toFloatOrNull()

internal fun Element.badgeText(pictoClass: String): String? = selectFirst("li:has(span.$pictoClass) > span.badge")?.ownText()?.trim()
