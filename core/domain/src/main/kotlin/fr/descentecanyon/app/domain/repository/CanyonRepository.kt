package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonChain
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.CanyonWatershed
import kotlinx.coroutines.flow.Flow

/**
 * Canyon data: the catalog embedded in the app, refreshed from descente-canyon.com when needed.
 */
interface CanyonRepository {
    /** The whole catalog with searchable fields, favorites flagged. */
    fun observeSearchCatalog(): Flow<List<CanyonSearchItem>>

    /**
     * Full canyon detail. Canyons missing from the catalog are fetched from the site; a detail whose
     * content is older than a month is refreshed when online (the catalog copy is kept on failure).
     */
    suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail>

    /** Detail from the catalog only, without network, to display something immediately. */
    suspend fun getCanyonPreview(canyonId: Int): Result<CanyonDetail>

    /** Catalog summaries of the given canyons; ids missing from the catalog are skipped. */
    suspend fun getCanyonSummaries(ids: Collection<Int>): List<CanyonSummary>

    /** Number of canyons available offline in the embedded catalog. */
    suspend fun getCatalogSize(): Int

    /** Watershed metadata of a canyon. */
    fun observeWatershed(canyonId: Int): Flow<CanyonWatershed?>

    /** Watershed polygon, loaded only when a map screen needs it. */
    suspend fun getWatershedGeometry(canyonId: Int): String?

    /** What the topos say of going on from this canyon to the ones after it (reviewed with the catalog). */
    suspend fun getChainsFrom(canyonId: Int): List<CanyonChain>
}
