package fr.descentecanyon.app.domain.model

/**
 * Core domain model representing a canyon.
 * Maps to a fiche-canyon on descente-canyon.com.
 */
data class Canyon(
    val id: Int,
    val nom: String,
    val nomComplet: String,
    val pays: String,
    val region: String? = null,
    val departement: String? = null,
    val commune: String,
    val communes: List<String> = emptyList(),
    val massif: String? = null,
    val bassin: String? = null,
    val coursEau: String? = null,
    val cotation: String,
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
    val url: String,
    val hasSpecificRegulation: Boolean = false,
    val isForbidden: Boolean = false,
    val lastUpdated: Long = 0L,
    val sourceType: CanyonSourceType = CanyonSourceType.DESCENTE_CANYON,
    val sourceKey: String = "",
)
