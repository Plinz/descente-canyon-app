package fr.descentecanyon.app.domain.model

data class FavoriteFolder(
    val id: Int,
    val name: String,
    val canyonCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
