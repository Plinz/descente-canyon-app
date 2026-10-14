package fr.descentecanyon.app.data.local.catalog.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_index")
data class SearchIndexEntity(
    @PrimaryKey val id: Int,
    val nom: String,
    val nomComplet: String,
    val pays: String,
    val countryTokensJson: String? = null,
    val region: String? = null,
    val departement: String? = null,
    val departmentTokensJson: String? = null,
    val subdivisionsByCountryJson: String? = null,
    val commune: String? = null,
    val massif: String? = null,
    val bassin: String? = null,
    val coursEau: String? = null,
    val cotation: String,
    val cotationVertical: Int? = null,
    val cotationAquatic: Int? = null,
    val cotationEngagement: Int? = null,
    val interet: Float? = null,
    val nbVotes: Int = 0,
    val altitudeDepart: Int? = null,
    val denivele: Int? = null,
    val longueur: Int? = null,
    val cascadeMax: Int? = null,
    val cordeMin: Int? = null,
    val hasSpecificRegulation: Boolean = false,
    val isForbidden: Boolean = false,
    val hasNavette: Boolean = false,
    val representativeLat: Double? = null,
    val representativeLng: Double? = null,
    val url: String,
    val searchableText: String,
    val normalizedNom: String,
    val normalizedNomComplet: String,
)
