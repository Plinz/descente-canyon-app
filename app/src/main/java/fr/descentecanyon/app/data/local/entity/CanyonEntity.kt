package fr.descentecanyon.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "canyons")
data class CanyonEntity(
    @PrimaryKey val id: Int,
    val nom: String,
    val nomComplet: String,
    val pays: String,
    val region: String? = null,
    val departement: String? = null,
    val commune: String,
    val communesJson: String? = null,
    val massif: String? = null,
    val bassin: String? = null,
    val coursEau: String? = null,
    val cotation: String,
    val altitudeDepart: Int? = null,
    val denivele: Int? = null,
    val longueur: Int? = null,
    val cascadeMax: Int? = null,
    val cordeMin: Int? = null,
    val tempsApproche: String? = null,
    val tempsDescente: String? = null,
    val tempsRetour: String? = null,
    val navette: String? = null,
    val interet: Float? = null,
    val nbVotes: Int = 0,
    val url: String,
    // Detail fields (populated when full page is scraped)
    val accesAval: String? = null,
    val accesAmont: String? = null,
    val approche: String? = null,
    val descente: String? = null,
    val retour: String? = null,
    val engagement: String? = null,
    val periode: String? = null,
    val geologie: String? = null,
    val historique: String? = null,
    val remarques: String? = null,
    val hasSpecificRegulation: Boolean = false,
    val isForbidden: Boolean = false,
    // Offline management
    val isOffline: Boolean = false,
    val isFavorite: Boolean = false,
    val favoriteAddedAt: Long? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val sourceType: String = "DESCENTE_CANYON",
    val sourceKey: String = "",
)
