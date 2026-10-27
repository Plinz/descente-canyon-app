package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.FoundPlace

/** Finds places by their name or address, on OpenStreetMap data. */
interface PlaceSearchRepository {
    /**
     * The places matching [query], those around [nearLatitude], [nearLongitude] first; names in
     * [language] when the service has it, else as written locally.
     */
    suspend fun search(query: String, nearLatitude: Double?, nearLongitude: Double?, language: String?): Result<List<FoundPlace>>
}
