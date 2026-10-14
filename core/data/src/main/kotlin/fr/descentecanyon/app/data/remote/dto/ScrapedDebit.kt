package fr.descentecanyon.app.data.remote.dto

/**
 * Raw scraped debit observation.
 */
data class ScrapedDebit(
    val canyonId: Int,
    val canyonNom: String = "",
    val date: String = "", // raw date string from page
    val niveauRaw: String = "", // raw level indicator (color/class)
    val auteur: String? = null,
    val isDescended: Boolean? = null,
    val waterTemperature: String? = null,
    val airTemperature: String? = null,
    val commentaire: String? = null,
)
