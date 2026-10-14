package fr.descentecanyon.app.data.local.catalog.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watersheds")
data class WatershedEntity(
    @PrimaryKey val canyonId: Int,
    val areaKm2: Double? = null,
    val geometryJson: String? = null,
    val bboxMinLongitude: Double? = null,
    val bboxMinLatitude: Double? = null,
    val bboxMaxLongitude: Double? = null,
    val bboxMaxLatitude: Double? = null,
)
