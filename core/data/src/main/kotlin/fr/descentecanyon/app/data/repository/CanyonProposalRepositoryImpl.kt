package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.BackendConfig
import fr.descentecanyon.app.data.local.catalog.toInstantOrNull
import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.remote.backend.BackendClient
import fr.descentecanyon.app.data.remote.backend.CanyonSheetDto
import fr.descentecanyon.app.data.remote.backend.ProposalDto
import fr.descentecanyon.app.data.remote.backend.ProposalListDto
import fr.descentecanyon.app.data.remote.backend.ProposalRequestDto
import fr.descentecanyon.app.data.remote.backend.SheetPointDto
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.AppCanyons
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonPlace
import fr.descentecanyon.app.domain.model.CanyonProposal
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSheet
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NearbyCanyon
import fr.descentecanyon.app.domain.model.ProposalKind
import fr.descentecanyon.app.domain.model.ProposalRejectionReason
import fr.descentecanyon.app.domain.model.ProposalStatus
import fr.descentecanyon.app.domain.model.SheetPoint
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.model.toSummary
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.CanyonProposalRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Proposed canyons, sent to the app's server with the key of this installation. The list of this
 * installation's proposals is kept in the user database so the user space shows it offline.
 */
@Singleton
class CanyonProposalRepositoryImpl @Inject constructor(
    private val client: BackendClient,
    private val config: BackendConfig,
    private val installKeys: InstallKeyStore,
    private val keyValueDao: KeyValueDao,
    private val canyonRepository: CanyonRepository,
    private val appServer: AppServerRepository,
    private val json: Json,
) : CanyonProposalRepository {

    override fun observeAvailable(): Flow<Boolean> = appServer.observeState().map { it.canyonProposalsAvailable }.distinctUntilChanged()

    /** Sending needs the server switched on; reading what became of earlier proposals only needs it built in. */
    private suspend fun canSend(): Boolean = config.isConfigured && observeAvailable().first()

    override fun observeMine(): Flow<List<CanyonProposal>> = keyValueDao.observe(KEY_MINE).map { decodeMine(it) }

    override suspend fun refreshMine(): Result<List<CanyonProposal>> = suspendRunCatching {
        if (!config.isConfigured ||
            !appServer.observeState().first().isActive
        ) {
            return@suspendRunCatching decodeMine(keyValueDao.get(KEY_MINE))
        }
        val list = client.myProposals(installKeys.get())
        saveMine(list)
        list.proposals.mapNotNull { it.toDomainOrNull() }
    }

    override suspend fun propose(sheet: CanyonSheet, canyonId: Int?): Result<CanyonProposal> = suspendRunCatching {
        if (!canSend()) appError(AppError.DataUnavailable)
        if (canyonId != null && !AppCanyons.isAppCanyon(canyonId)) appError(AppError.DataUnavailable)
        val request = ProposalRequestDto(
            kind = if (canyonId == null) ProposalKind.NEW.name else ProposalKind.EDIT.name,
            canyonId = canyonId,
            content = sheet.toDto(),
            licence = LICENCE,
        )
        val sent = client.submitProposal(request, installKeys.get())
        val current = decodeList(keyValueDao.get(KEY_MINE))
        saveMine(ProposalListDto(listOf(sent) + current.proposals.filterNot { it.id == sent.id }))
        sent.toDomainOrNull() ?: appError(AppError.UnexpectedResponse)
    }

    override suspend fun withdraw(proposal: CanyonProposal): Result<Unit> = suspendRunCatching {
        client.withdrawProposal(proposal.id, installKeys.get())
        val current = decodeList(keyValueDao.get(KEY_MINE))
        saveMine(
            ProposalListDto(
                current.proposals.map { if (it.id == proposal.id) it.copy(status = ProposalStatus.WITHDRAWN.name) else it },
            ),
        )
    }

    override suspend fun sheetOf(canyonId: Int): Result<CanyonSheet> = canyonRepository.getCanyonPreview(canyonId).map { it.toSheet() }

    override suspend fun placeNear(latitude: Double, longitude: Double): CanyonPlace? {
        val nearest = located(canyonRepository.observeSearchCatalog().first())
            .minByOrNull { (_, lat, lon) -> haversineKm(latitude, longitude, lat, lon) }
            ?: return null
        val (item, lat, lon) = nearest
        if (haversineKm(latitude, longitude, lat, lon) > PLACE_MAX_DISTANCE_KM) return null
        return CanyonPlace(
            pays = item.pays,
            region = item.region,
            departement = item.departement,
            commune = item.commune,
            massif = item.massif,
            bassin = item.bassin,
        )
    }

    override suspend fun canyonsNear(latitude: Double, longitude: Double, maxDistanceKm: Double): List<NearbyCanyon> =
        located(canyonRepository.observeSearchCatalog().first())
            .mapNotNull { (item, lat, lon) ->
                val distance = haversineKm(latitude, longitude, lat, lon)
                if (distance <= maxDistanceKm) NearbyCanyon(item.toSummary(), distance) else null
            }
            .sortedBy { it.distanceKm }
            .take(MAX_NEARBY)

    private fun located(items: List<CanyonSearchItem>): List<Triple<CanyonSearchItem, Double, Double>> = items.mapNotNull { item ->
        val lat = item.representativeLat ?: return@mapNotNull null
        val lon = item.representativeLng ?: return@mapNotNull null
        Triple(item, lat, lon)
    }

    private suspend fun saveMine(list: ProposalListDto) {
        keyValueDao.put(KeyValueEntity(KEY_MINE, json.encodeToString(ProposalListDto.serializer(), list)))
    }

    private fun decodeList(payload: String?): ProposalListDto = payload?.takeIf { it.isNotBlank() }
        ?.let { runCatching { json.decodeFromString(ProposalListDto.serializer(), it) }.getOrNull() }
        ?: ProposalListDto()

    private fun decodeMine(payload: String?): List<CanyonProposal> = decodeList(payload).proposals.mapNotNull { it.toDomainOrNull() }

    companion object {
        private const val KEY_MINE = "canyon_proposals_mine"
        private const val LICENCE = "CC0-1.0"
        private const val PLACE_MAX_DISTANCE_KM = 25.0
        private const val MAX_NEARBY = 40
    }
}

internal fun ProposalDto.toDomainOrNull(): CanyonProposal? = CanyonProposal(
    id = id,
    kind = ProposalKind.entries.firstOrNull { it.name == kind } ?: return null,
    canyonId = canyonId,
    name = name,
    status = ProposalStatus.entries.firstOrNull { it.name == status } ?: return null,
    createdAt = createdAt.toInstantOrNull() ?: Instant.EPOCH,
    decidedAt = decidedAt?.toInstantOrNull(),
    reason = reason?.let { code -> ProposalRejectionReason.entries.firstOrNull { it.name == code } ?: ProposalRejectionReason.OTHER },
    note = note,
)

internal fun CanyonSheet.toDto(): CanyonSheetDto = CanyonSheetDto(
    nom = nom.orNull(),
    nomComplet = nomComplet.orNull(),
    pays = pays.orNull(),
    region = region.orNull(),
    departement = departement.orNull(),
    commune = commune.orNull(),
    massif = massif.orNull(),
    bassin = bassin.orNull(),
    coursEau = coursEau.orNull(),
    cotation = CanyonSheet.cotationText(cotation).orNull(),
    altitudeDepart = altitudeDepart,
    denivele = denivele,
    longueur = longueur,
    cascadeMax = cascadeMax,
    cordeMin = cordeMin,
    tempsApproche = tempsApproche.orNull(),
    tempsDescente = tempsDescente.orNull(),
    tempsRetour = tempsRetour.orNull(),
    navette = navette.orNull(),
    accesAval = accesAval.orNull(),
    accesAmont = accesAmont.orNull(),
    approche = approche.orNull(),
    descente = descente.orNull(),
    retour = retour.orNull(),
    engagement = engagement.orNull(),
    periode = periode.orNull(),
    geologie = geologie.orNull(),
    historique = historique.orNull(),
    remarques = remarques.orNull(),
    isForbidden = isForbidden.takeIf { it },
    points = points.map { SheetPointDto(it.type.name, it.latitude, it.longitude, it.title.orNull()) },
)

/** The sheet of a canyon of the catalog, as the editor starts from it. */
internal fun CanyonDetail.toSheet(): CanyonSheet {
    val canyon = canyon
    val rating = CotationRating.parse(canyon.cotation).takeIf { it.isKnown }
    return CanyonSheet(
        nom = canyon.nom,
        nomComplet = canyon.nomComplet.takeIf { it != canyon.nom }.orEmpty(),
        pays = canyon.pays,
        region = canyon.region.orEmpty(),
        departement = canyon.departement.orEmpty(),
        commune = canyon.commune,
        massif = canyon.massif.orEmpty(),
        bassin = canyon.bassin.orEmpty(),
        coursEau = canyon.coursEau.orEmpty(),
        cotation = rating,
        altitudeDepart = canyon.altitudeDepart,
        denivele = canyon.denivele,
        longueur = canyon.longueur,
        cascadeMax = canyon.cascadeMax,
        cordeMin = canyon.cordeMin,
        tempsApproche = canyon.tempsApproche.known(),
        tempsDescente = canyon.tempsDescente.known(),
        tempsRetour = canyon.tempsRetour.known(),
        navette = canyon.navette.known(),
        accesAval = accesAval.orEmpty(),
        accesAmont = accesAmont.orEmpty(),
        approche = approche.orEmpty(),
        descente = descente.orEmpty(),
        retour = retour.orEmpty(),
        engagement = engagement.orEmpty(),
        periode = periode.orEmpty(),
        geologie = geologie.orEmpty(),
        historique = historique.orEmpty(),
        remarques = remarques.orEmpty(),
        isForbidden = canyon.isForbidden,
        points = geoPoints.filter {
            it.type != GeoPointType.UNKNOWN
        }.map { SheetPoint(it.type, it.latitude, it.longitude, it.title.orEmpty()) },
    )
}

private fun String.orNull(): String? = trim().ifEmpty { null }

private fun String?.known(): String = this?.trim()?.takeIf { it != "??" }.orEmpty()
