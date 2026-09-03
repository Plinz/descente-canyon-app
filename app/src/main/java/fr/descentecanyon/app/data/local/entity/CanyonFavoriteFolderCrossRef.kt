package fr.descentecanyon.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "canyon_favorite_folder_cross_ref",
    primaryKeys = ["canyonId", "folderId"],
    indices = [
        Index(value = ["folderId"]),
        Index(value = ["canyonId"]),
    ]
)
data class CanyonFavoriteFolderCrossRef(
    val canyonId: Int,
    val folderId: Int,
)
