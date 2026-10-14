package fr.descentecanyon.app.data.local.catalog.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "canyon_tracks",
    primaryKeys = ["canyonId", "trackId"],
    indices = [Index("canyonId")],
)
data class CanyonTrackEntity(
    val canyonId: Int,
    val trackId: String,
    val name: String,
    val role: String? = null,
    val isPrimary: Boolean = false,
    val sourceFile: String? = null,
    val pointCount: Int? = null,
    val geometryJson: String? = null,
    val bboxMinLongitude: Double? = null,
    val bboxMinLatitude: Double? = null,
    val bboxMaxLongitude: Double? = null,
    val bboxMaxLatitude: Double? = null,
)
