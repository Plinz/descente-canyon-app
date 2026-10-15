package fr.descentecanyon.app.domain.model

import java.time.LocalDate

/**
 * Lightweight canyon info for lists and search results.
 */
data class CanyonSummary(
    val id: Int,
    val nom: String,
    val pays: String,
    val departement: String? = null,
    val cotation: String,
    val interet: Float? = null,
    val dernierDebit: NiveauDebit? = null,
    /** Date of [dernierDebit], to judge how current it is. */
    val dernierDebitDate: LocalDate? = null,
    val url: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val markerType: GeoPointType? = null,
    val isForbidden: Boolean = false,
)
