package fr.descentecanyon.app.data.local.catalog.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "geo_points",
    foreignKeys = [
        ForeignKey(
            entity = CanyonEntity::class,
            parentColumns = ["id"],
            childColumns = ["canyonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("canyonId")],
)
data class GeoPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canyonId: Int,
    val type: String, // GeoPointType name
    val latitude: Double,
    val longitude: Double,
    val title: String? = null,
    val remark: String? = null,
)
