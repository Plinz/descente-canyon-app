package fr.descentecanyon.app.data.local.catalog.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "canyon_regulations",
    primaryKeys = ["canyonId", "regulationId"],
    foreignKeys = [
        ForeignKey(
            entity = CanyonEntity::class,
            parentColumns = ["id"],
            childColumns = ["canyonId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RegulationTextEntity::class,
            parentColumns = ["id"],
            childColumns = ["regulationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("canyonId"), Index("regulationId")],
)
data class CanyonRegulationEntity(val canyonId: Int, val regulationId: Int)
