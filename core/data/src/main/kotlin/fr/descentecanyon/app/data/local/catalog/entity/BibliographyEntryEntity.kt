package fr.descentecanyon.app.data.local.catalog.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bibliography_entries")
data class BibliographyEntryEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val resourceType: String? = null,
    val title: String,
    val authorsJson: String? = null,
    val publicationYear: Int? = null,
    val reference: String? = null,
    val editor: String? = null,
    val status: String? = null,
    val scale: String? = null,
    val detailUrl: String? = null,
    val url: String? = null,
)
