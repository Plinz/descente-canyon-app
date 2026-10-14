package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.domain.model.normalizeForSearch
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object E2eFixtureState {
    val favoriteIds = MutableStateFlow(emptySet<Int>())
    val authState = MutableStateFlow<AuthState>(AuthState.Disconnected)
    val latestDebits = MutableStateFlow(sampleLatestDebits())
    val canyonDetails = MutableStateFlow(sampleDetails())
    val queuedSubmissions = MutableStateFlow<List<DebitSubmission>>(emptyList())

    fun reset() {
        favoriteIds.value = emptySet()
        authState.value = AuthState.Disconnected
        latestDebits.value = sampleLatestDebits()
        canyonDetails.value = sampleDetails()
        queuedSubmissions.value = emptyList()
    }

    fun catalogItems(): List<CanyonSearchItem> {
        val favorites = favoriteIds.value
        return canyonDetails.value.values.map { detail ->
            val canyon = detail.canyon
            CanyonSearchItem(
                id = canyon.id,
                nom = canyon.nom,
                nomComplet = canyon.nomComplet,
                pays = canyon.pays,
                countryTokens = listOf(canyon.pays),
                departement = canyon.departement,
                departmentTokens = listOfNotNull(canyon.departement),
                subdivisionsByCountry = canyon.departement?.let { mapOf(canyon.pays to listOf(it)) }.orEmpty(),
                commune = canyon.commune,
                cotation = canyon.cotation,
                cotationRating = CotationRating.parse(canyon.cotation),
                interet = canyon.interet,
                url = canyon.url,
                searchableText = listOf(canyon.nom, canyon.nomComplet, canyon.pays, canyon.departement.orEmpty())
                    .joinToString(" ")
                    .normalizeForSearch(),
                normalizedNom = canyon.nom.normalizeForSearch(),
                normalizedNomComplet = canyon.nomComplet.normalizeForSearch(),
                isFavorite = favorites.contains(canyon.id),
                representativeLat = sampleSummary(canyon.id)?.latitude,
                representativeLng = sampleSummary(canyon.id)?.longitude,
            )
        }
    }

    fun summaries(): List<CanyonSummary> = canyonDetails.value.keys.mapNotNull(::sampleSummary)

    fun favorites(): List<CanyonSummary> = favoriteIds.value.mapNotNull(::sampleSummary)

    fun isFavorite(canyonId: Int): Boolean = favoriteIds.value.contains(canyonId)

    fun toggleFavorite(canyonId: Int) {
        favoriteIds.update { ids -> if (ids.contains(canyonId)) ids - canyonId else ids + canyonId }
    }

    fun updatePhotoLocalPath(photoId: Long, localPath: String?) {
        canyonDetails.update { details ->
            details.mapValues { (_, detail) ->
                detail.copy(
                    photos = detail.photos.map { photo ->
                        if (photo.id == photoId) photo.copy(localPath = localPath) else photo
                    },
                )
            }
        }
    }

    private fun sampleSummary(canyonId: Int): CanyonSummary? = when (canyonId) {
        101 -> CanyonSummary(
            id = 101,
            nom = "Riolan",
            pays = "France",
            departement = "06",
            cotation = "v3a3III",
            interet = 4.2f,
            dernierDebit = NiveauDebit.CORRECT,
            url = "/canyoning/canyon/101/Riolan.html",
            latitude = 43.85,
            longitude = 6.79,
            markerType = GeoPointType.ENTREE,
        )

        102 -> CanyonSummary(
            id = 102,
            nom = "Aiglun",
            pays = "France",
            departement = "06",
            cotation = "v4a4III",
            interet = 4.5f,
            dernierDebit = NiveauDebit.GROS,
            url = "/canyoning/canyon/102/Aiglun.html",
            latitude = 43.78,
            longitude = 6.65,
            markerType = GeoPointType.PARKING_AMONT,
        )

        else -> null
    }

    private fun sampleDetails(): Map<Int, CanyonDetail> {
        val riolan = Canyon(
            id = 101,
            nom = "Riolan",
            nomComplet = "Canyon du Riolan",
            pays = "France",
            departement = "06",
            commune = "Sigale",
            cotation = "v3a3III",
            interet = 4.2f,
            url = "/canyoning/canyon/101/Riolan.html",
        )
        val aiglun = Canyon(
            id = 102,
            nom = "Aiglun",
            nomComplet = "Clue d'Aiglun",
            pays = "France",
            departement = "06",
            commune = "Aiglun",
            cotation = "v4a4III",
            interet = 4.5f,
            url = "/canyoning/canyon/102/Aiglun.html",
        )

        return mapOf(
            101 to CanyonDetail(
                canyon = riolan,
                approche = "20 min",
                descente = "2 h",
                retour = "10 min",
                photos = listOf(
                    CanyonPhoto(
                        id = 501,
                        canyonId = 101,
                        url = "https://example.com/riolan.jpg",
                        thumbnailUrl = "https://example.com/riolan-thumb.jpg",
                        description = "Vasque principale",
                    ),
                ),
                debits = listOf(
                    Debit(
                        id = 9001,
                        canyonId = 101,
                        canyonNom = "Riolan",
                        date = LocalDate.of(2026, 3, 20),
                        niveau = NiveauDebit.CORRECT,
                        auteur = "Alice",
                        isDescended = true,
                        waterTemperature = "Douce",
                        airTemperature = "Bon",
                        commentaire = "Conditions parfaites",
                    ),
                ),
            ),
            102 to CanyonDetail(
                canyon = aiglun,
                approche = "30 min",
                descente = "3 h",
                retour = "15 min",
            ),
        )
    }

    private fun sampleLatestDebits(): List<Debit> = listOf(
        Debit(
            id = 8001,
            canyonId = 101,
            canyonNom = "Riolan",
            date = LocalDate.of(2026, 3, 20),
            niveau = NiveauDebit.CORRECT,
            auteur = "Alice",
            isDescended = true,
            waterTemperature = WaterTemperature.DOUCE.name,
            airTemperature = AirTemperature.BON.name,
            commentaire = "Conditions parfaites",
        ),
    )
}
