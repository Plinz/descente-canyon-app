package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.catalog.dao.BibliographyDao
import fr.descentecanyon.app.data.local.catalog.dao.CanyonDao
import fr.descentecanyon.app.data.local.catalog.dao.CanyonTrackDao
import fr.descentecanyon.app.data.local.catalog.dao.GeoPointDao
import fr.descentecanyon.app.data.local.catalog.dao.RegulationDao
import fr.descentecanyon.app.data.local.catalog.dao.WatershedDao
import fr.descentecanyon.app.data.local.catalog.dao.toDomain
import fr.descentecanyon.app.data.local.catalog.entity.CanyonEntity
import fr.descentecanyon.app.data.local.catalog.entity.GeoPointEntity
import fr.descentecanyon.app.data.local.user.dao.DebitDao
import fr.descentecanyon.app.data.local.user.dao.PhotoDao
import fr.descentecanyon.app.data.mapper.toDetail
import fr.descentecanyon.app.domain.model.CanyonDetail
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull

/** Reads canyon details across the catalog and user databases and writes refreshed catalog rows. */
@Singleton
class CanyonLocalStore @Inject constructor(
    private val canyonDao: CanyonDao,
    private val canyonTrackDao: CanyonTrackDao,
    private val geoPointDao: GeoPointDao,
    private val bibliographyDao: BibliographyDao,
    private val regulationDao: RegulationDao,
    private val watershedDao: WatershedDao,
    private val debitDao: DebitDao,
    private val photoDao: PhotoDao,
    private val representativePointSelector: RepresentativePointSelector,
) {

    /** Inserts a canyon fetched from the site, or merges it into the catalog row (see [mergeScrapedCanyon]). */
    suspend fun upsertScrapedCanyon(scraped: CanyonEntity) {
        val existing = canyonDao.getById(scraped.id)
        if (existing == null) {
            canyonDao.insertIgnore(scraped)
        } else {
            canyonDao.update(mergeScrapedCanyon(existing, scraped))
        }
    }

    suspend fun replaceGeoPoints(canyonId: Int, geoPointEntities: List<GeoPointEntity>) {
        geoPointDao.deleteByCanyonId(canyonId)
        if (geoPointEntities.isNotEmpty()) {
            geoPointDao.insertAll(geoPointEntities)
        }
    }

    suspend fun loadLocalDetail(canyonId: Int, canyon: CanyonEntity): CanyonDetail {
        val geoPoints = geoPointDao.getByCanyonId(canyonId)
        val bibliography = bibliographyDao.getByCanyonId(canyonId)
        val regulations = regulationDao.getByCanyonId(canyonId)
        val tracks = canyonTrackDao.getByCanyonId(canyonId)
        val photos = photoDao.getByCanyonId(canyonId)
        val debits = debitDao.getByCanyonId(canyonId).firstOrNull().orEmpty()
        val watershed = watershedDao.getMetadataByCanyonId(canyonId)?.toDomain()
        return canyon.toDetail(geoPoints, bibliography, regulations, tracks, photos, debits, watershed)
    }

    fun bestMarkerPointOrNull(points: List<GeoPointEntity>): GeoPointEntity? = representativePointSelector.bestMarkerPointOrNull(points)
}

/**
 * Catalog row updated with a fresh scrape of the canyon pages. The scraper only reads the summary
 * and topo pages: values it could not read are kept, and the fields it never reads (communes,
 * geology, history, regulation status...) always come from the catalog.
 */
internal fun mergeScrapedCanyon(existing: CanyonEntity, scraped: CanyonEntity): CanyonEntity {
    fun String?.orKeep(previous: String?): String? = this?.takeIf { it.isNotBlank() } ?: previous
    fun String.orKeep(previous: String): String = takeIf { it.isNotBlank() } ?: previous
    return existing.copy(
        nom = scraped.nom.orKeep(existing.nom),
        nomComplet = scraped.nomComplet.orKeep(existing.nomComplet),
        pays = scraped.pays.orKeep(existing.pays),
        region = scraped.region.orKeep(existing.region),
        departement = scraped.departement.orKeep(existing.departement),
        commune = scraped.commune.orKeep(existing.commune),
        massif = scraped.massif.orKeep(existing.massif),
        cotation = scraped.cotation.orKeep(existing.cotation),
        altitudeDepart = scraped.altitudeDepart ?: existing.altitudeDepart,
        denivele = scraped.denivele ?: existing.denivele,
        longueur = scraped.longueur ?: existing.longueur,
        cascadeMax = scraped.cascadeMax ?: existing.cascadeMax,
        cordeMin = scraped.cordeMin ?: existing.cordeMin,
        tempsApproche = scraped.tempsApproche.orKeep(existing.tempsApproche),
        tempsDescente = scraped.tempsDescente.orKeep(existing.tempsDescente),
        tempsRetour = scraped.tempsRetour.orKeep(existing.tempsRetour),
        navette = scraped.navette.orKeep(existing.navette),
        interet = scraped.interet ?: existing.interet,
        nbVotes = if (scraped.nbVotes > 0) scraped.nbVotes else existing.nbVotes,
        url = scraped.url.orKeep(existing.url),
        accesAval = scraped.accesAval.orKeep(existing.accesAval),
        accesAmont = scraped.accesAmont.orKeep(existing.accesAmont),
        approche = scraped.approche.orKeep(existing.approche),
        descente = scraped.descente.orKeep(existing.descente),
        retour = scraped.retour.orKeep(existing.retour),
        engagement = scraped.engagement.orKeep(existing.engagement),
        periode = scraped.periode.orKeep(existing.periode),
        lastUpdated = scraped.lastUpdated,
    )
}
