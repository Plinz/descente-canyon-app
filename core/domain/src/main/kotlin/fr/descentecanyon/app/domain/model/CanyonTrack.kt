package fr.descentecanyon.app.domain.model

data class CanyonTrack(
    val id: String,
    val name: String,
    val role: String? = null,
    val isPrimary: Boolean = false,
    val sourceFile: String? = null,
    val pointCount: Int? = null,
    val geometryJson: String? = null,
    val bounds: GeoBounds? = null,
)
