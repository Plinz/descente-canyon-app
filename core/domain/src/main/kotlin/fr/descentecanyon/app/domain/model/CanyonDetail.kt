package fr.descentecanyon.app.domain.model

/**
 * Full canyon description including topo details.
 * Extends [Canyon] with approach, descent, return descriptions, etc.
 */
data class CanyonDetail(
    val canyon: Canyon,
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
    val geoPoints: List<GeoPoint> = emptyList(),
    val bibliography: List<BibliographyEntry> = emptyList(),
    val regulations: List<Regulation> = emptyList(),
    val tracks: List<CanyonTrack> = emptyList(),
    val photos: List<CanyonPhoto> = emptyList(),
    val debits: List<Debit> = emptyList(),
    val watershed: CanyonWatershed? = null,
)
