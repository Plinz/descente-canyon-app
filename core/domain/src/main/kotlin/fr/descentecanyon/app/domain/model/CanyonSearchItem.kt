package fr.descentecanyon.app.domain.model

data class CanyonSearchItem(
    val id: Int,
    val nom: String,
    val nomComplet: String,
    val pays: String,
    val countryTokens: List<String> = emptyList(),
    val region: String? = null,
    val departement: String? = null,
    val departmentTokens: List<String> = emptyList(),
    val subdivisionsByCountry: Map<String, List<String>> = emptyMap(),
    val commune: String? = null,
    val massif: String? = null,
    val bassin: String? = null,
    val coursEau: String? = null,
    val cotation: String,
    val cotationRating: CotationRating,
    val interet: Float? = null,
    val nbVotes: Int = 0,
    val altitudeDepart: Int? = null,
    val denivele: Int? = null,
    val longueur: Int? = null,
    val cascadeMax: Int? = null,
    val cordeMin: Int? = null,
    val hasSpecificRegulation: Boolean = false,
    val isForbidden: Boolean = false,
    val hasNavette: Boolean = false,
    val isFavorite: Boolean = false,
    val representativeLat: Double? = null,
    val representativeLng: Double? = null,
    val url: String,
    val searchableText: String,
    val normalizedNom: String,
    val normalizedNomComplet: String,
)

fun CanyonSearchItem.subdivisionsFor(country: String?): List<String> {
    if (country == null) {
        return subdivisionsByCountry.values.flatten().ifEmpty { departmentTokens }
    }

    val mapped = subdivisionsByCountry.entries.firstOrNull { it.key.equals(country, ignoreCase = true) }
    return when {
        mapped != null -> mapped.value
        countryTokens.size <= 1 && countryTokens.any { it.equals(country, ignoreCase = true) } -> departmentTokens
        else -> emptyList()
    }
}

fun CanyonSearchItem.toSummary(): CanyonSummary = CanyonSummary(
    id = id,
    nom = nom,
    pays = pays,
    departement = departement,
    cotation = cotation,
    interet = interet,
    url = url,
    latitude = representativeLat,
    longitude = representativeLng,
    isForbidden = isForbidden,
)

data class SearchResultSet(
    val results: List<CanyonSearchItem> = emptyList(),
    /** The areas of the canyons matching the text query, whatever the other filters. */
    val zones: List<ZoneCountry> = emptyList(),
    val totalResultsCount: Int = results.size,
    val isResultListDeferred: Boolean = false,
)
