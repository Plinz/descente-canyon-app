package fr.descentecanyon.app.data.local.catalog.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "canyon_bibliography",
    primaryKeys = ["canyonId", "bibliographyId"],
    foreignKeys = [
        ForeignKey(
            entity = CanyonEntity::class,
            parentColumns = ["id"],
            childColumns = ["canyonId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BibliographyEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["bibliographyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("canyonId"), Index("bibliographyId")],
)
data class CanyonBibliographyEntity(val canyonId: Int, val bibliographyId: String)
