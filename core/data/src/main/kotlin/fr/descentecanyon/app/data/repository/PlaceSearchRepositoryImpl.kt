package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.remote.geocoding.PhotonFeatureDto
import fr.descentecanyon.app.data.remote.geocoding.PhotonRemoteSource
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.FoundPlace
import fr.descentecanyon.app.domain.model.FoundPlaceKind
import fr.descentecanyon.app.domain.repository.PlaceSearchRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaceSearchRepositoryImpl @Inject constructor(private val remote: PhotonRemoteSource) : PlaceSearchRepository {

    override suspend fun search(query: String, nearLatitude: Double?, nearLongitude: Double?, language: String?): Result<List<FoundPlace>> =
        suspendRunCatching {
            remote.search(query.trim(), nearLatitude, nearLongitude, language?.takeIf { it in PhotonRemoteSource.LANGUAGES })
                .features
                .mapNotNull { it.toFoundPlace() }
                // A long street comes as several pieces with the same name and town.
                .distinctBy { Triple(it.name, it.detail, it.kind) }
        }
}

/** "Les Merveilles", "Route de Pelasque, 06450 Lantosque, France"; null without a position or a name. */
internal fun PhotonFeatureDto.toFoundPlace(): FoundPlace? {
    val longitude = geometry.coordinates.getOrNull(0) ?: return null
    val latitude = geometry.coordinates.getOrNull(1) ?: return null
    val properties = properties
    val street = listOfNotNull(properties.housenumber, properties.street).joinToString(" ").ifBlank { null }
    val name = properties.name ?: street ?: properties.city ?: return null
    val town = (properties.city ?: properties.locality)?.takeIf { it != name }
    val detail = buildList {
        street?.takeIf { it != name }?.let(::add)
        listOfNotNull(properties.postcode, town).joinToString(" ").takeIf { it.isNotBlank() }?.let(::add)
        if (town == null) properties.county?.takeIf { it != name }?.let(::add)
        properties.country?.takeIf { it != name }?.let(::add)
    }.joinToString(", ")
    return FoundPlace(name, detail, latitude, longitude, kindOf(properties.osmKey, properties.osmValue))
}

private fun kindOf(key: String?, value: String?): FoundPlaceKind = when {
    key == "tourism" && value in CAMPSITES -> FoundPlaceKind.CAMPSITE
    key == "tourism" && value in LODGINGS -> FoundPlaceKind.LODGING
    key == "place" && value !in ADDRESS_PLACES -> FoundPlaceKind.LOCALITY
    key == "boundary" -> FoundPlaceKind.LOCALITY
    key == "highway" || key == "building" || key == "place" -> FoundPlaceKind.ADDRESS
    else -> FoundPlaceKind.OTHER
}

private val CAMPSITES = setOf("camp_site", "caravan_site", "camp_pitch")
private val LODGINGS = setOf("hotel", "motel", "guest_house", "hostel", "chalet", "apartment", "alpine_hut", "wilderness_hut")
private val ADDRESS_PLACES = setOf("house", "street")
