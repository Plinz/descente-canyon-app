package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.network.SiteHttpClient
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import fr.descentecanyon.app.data.remote.dto.ScrapedForumActiveTopic
import fr.descentecanyon.app.data.remote.dto.ScrapedForumCategory
import fr.descentecanyon.app.data.remote.dto.ScrapedForumUserPost
import fr.descentecanyon.app.data.remote.dto.ScrapedGeoPoint
import fr.descentecanyon.app.data.remote.dto.ScrapedPhoto
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.RecentDebitCell
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

/**
 * Reads canyon, debit and forum pages of descente-canyon.com. The session (when the user is
 * logged in) is attached by the site client's cookie jar. Failures are typed [AppError]s.
 */
@Singleton
class CanyonScraper @Inject constructor(private val siteClient: SiteHttpClient) {

    suspend fun scrapeCanyonPhotos(canyonId: Int, timeoutMs: Long = SiteHttpClient.DEFAULT_TIMEOUT_MS): Result<List<ScrapedPhoto>> =
        suspendRunCatching {
            val document = siteClient.get(SiteUrls.canyonPhotos(canyonId), timeoutMs).document
            parsing { PhotoParser.parse(document, canyonId) }
        }

    suspend fun scrapeCanyonDebits(canyonId: Int, timeoutMs: Long = SiteHttpClient.DEFAULT_TIMEOUT_MS): Result<List<ScrapedDebit>> =
        suspendRunCatching {
            val document = siteClient.get(SiteUrls.canyonDebits(canyonId), timeoutMs).document
            parsing { DebitParser.parseCanyonDebits(document, canyonId) }
        }

    suspend fun scrapeLatestDebits(): Result<List<ScrapedDebit>> = suspendRunCatching {
        val document = siteClient.get(SiteUrls.LATEST_DEBITS).document
        parsing {
            // The same page gives the grid of the debit model: kept to spare a second download.
            latestDebitGrid = System.currentTimeMillis() to DebitParser.parseLatestDebitGrid(document)
            DebitParser.parseLatestDebits(document)
        }
    }

    /**
     * Day grid of recent reports of every canyon (last ~16 days), used by the debit model; the one
     * read with the latest reports is reused when it is less than [maxAgeMs] old.
     */
    suspend fun scrapeLatestDebitGrid(maxAgeMs: Long = LATEST_DEBIT_GRID_MAX_AGE_MS): Result<List<RecentDebitCell>> = suspendRunCatching {
        latestDebitGrid?.takeIf { System.currentTimeMillis() - it.first < maxAgeMs }?.let { return@suspendRunCatching it.second }
        val document = siteClient.get(SiteUrls.LATEST_DEBITS).document
        parsing { DebitParser.parseLatestDebitGrid(document) }.also { latestDebitGrid = System.currentTimeMillis() to it }
    }

    @Volatile
    private var latestDebitGrid: Pair<Long, List<RecentDebitCell>>? = null

    suspend fun scrapeActiveForumTopics(): Result<List<ScrapedForumActiveTopic>> = suspendRunCatching {
        val document = siteClient.get(SiteUrls.FORUM_ACTIVE_TOPICS).document
        parsing { ForumParser.parseActiveTopics(document) }
    }

    suspend fun scrapeForumCategories(): Result<List<ScrapedForumCategory>> = suspendRunCatching {
        val document = siteClient.get(SiteUrls.FORUM_CATEGORIES).document
        parsing { ForumParser.parseCategories(document) }
    }

    suspend fun scrapeForumUserPosts(authorId: Int): Result<List<ScrapedForumUserPost>> = suspendRunCatching {
        val document = siteClient.get(SiteUrls.forumUserPosts(authorId)).document
        parsing { ForumParser.parseUserPosts(document) }
    }

    /** Full canyon detail: summary, description (topo) and GPS points merged; the three pages load together. */
    suspend fun scrapeFullCanyonDetail(canyonId: Int): Result<ScrapedCanyonDetail> = suspendRunCatching {
        coroutineScope {
            val summaryPage = async { siteClient.get(SiteUrls.canyon(canyonId)).document }
            val descriptionPage = async { siteClient.get(SiteUrls.canyonDescription(canyonId)).document }
            // The map page is optional: a canyon without GPS points keeps the summary and topo.
            val geoPoints = async {
                suspendRunCatching {
                    val mapPage = siteClient.get(SiteUrls.canyonMap(canyonId)).document
                    parsing { GeoPointParser.parse(mapPage) }
                }.getOrDefault(emptyList())
            }
            val summary = summaryPage.await().let { page -> parsing { SummaryParser.parse(page, canyonId) } }
            val description = descriptionPage.await().let { page -> parsing { DescriptionParser.parse(page, canyonId) } }
            summary.withDescription(description, geoPoints.await())
        }
    }

    private companion object {
        const val LATEST_DEBIT_GRID_MAX_AGE_MS = 30L * 60 * 1000
    }

    private fun ScrapedCanyonDetail.withDescription(description: ScrapedCanyonDetail, geoPoints: List<ScrapedGeoPoint>) = copy(
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

// --- Utility extensions shared by the parsers ---

internal fun String.extractInt(): Int? = replace(Regex("[^\\d]"), "").toIntOrNull()

internal fun String.extractFloat(): Float? = replace(",", ".").replace(Regex("[^\\d.]"), "").toFloatOrNull()

internal fun Element.badgeText(pictoClass: String): String? = selectFirst("li:has(span.$pictoClass) > span.badge")?.ownText()?.trim()
