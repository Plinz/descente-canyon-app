package fr.descentecanyon.app.data.remote.dto

/**
 * Raw scraped photo data.
 */
data class ScrapedPhoto(
    val canyonId: Int,
    val url: String,
    val thumbnailUrl: String? = null,
    val auteur: String? = null,
    val description: String? = null,
    val datePrise: String? = null,
)
