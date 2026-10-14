package fr.descentecanyon.app.domain.model

/**
 * A photo associated with a canyon.
 */
data class CanyonPhoto(
    val id: Long = 0,
    val canyonId: Int,
    val url: String,
    val thumbnailUrl: String? = null,
    val auteur: String? = null,
    val description: String? = null,
    val localPath: String? = null,
)
