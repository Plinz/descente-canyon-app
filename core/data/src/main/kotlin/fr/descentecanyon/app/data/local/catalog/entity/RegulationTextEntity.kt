package fr.descentecanyon.app.data.local.catalog.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "regulation_texts")
data class RegulationTextEntity(
    @PrimaryKey val id: Int,
    val status: String? = null,
    val action: String? = null,
    val title: String,
    val summary: String? = null,
    val remark: String? = null,
    val details: String? = null,
    val effectiveDate: String? = null,
    val textUrl: String,
    val attachmentsJson: String? = null,
)
