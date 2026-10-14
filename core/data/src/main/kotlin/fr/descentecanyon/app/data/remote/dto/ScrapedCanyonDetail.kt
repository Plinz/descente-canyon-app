package fr.descentecanyon.app.data.remote.dto

/**
 * Raw scraped data from a canyon page, before mapping to domain model.
 */
data class ScrapedCanyonDetail(
    val id: Int,
    val nom: String = "",
    val nomComplet: String = "",
    val pays: String = "",
    val region: String? = null,
    val departement: String? = null,
    val commune: String = "",
    val massif: String? = null,
    val cotation: String = "",
    val altitudeDepart: Int? = null,
    val denivele: Int? = null,
    val longueur: Int? = null,
    val cascadeMax: Int? = null,
    val cordeMin: Int? = null,
    val tempsApproche: String? = null,
    val tempsDescente: String? = null,
    val tempsRetour: String? = null,
    val navette: String? = null,
    val interet: Float? = null,
    val nbVotes: Int = 0,
    val url: String = "",
    // Description fields
    val accesAval: String? = null,
    val accesAmont: String? = null,
    val approche: String? = null,
    val descente: String? = null,
    val retour: String? = null,
    val engagement: String? = null,
    val periode: String? = null,
    // Geo points
    val geoPoints: List<ScrapedGeoPoint> = emptyList(),
)

data class ScrapedGeoPoint(
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val title: String? = null,
    val remark: String? = null,
)
