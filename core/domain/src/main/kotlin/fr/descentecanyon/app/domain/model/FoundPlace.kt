package fr.descentecanyon.app.domain.model

/** What a place found by its name is, to tell a campsite from a street of the same name. */
enum class FoundPlaceKind {
    CAMPSITE,
    LODGING,
    LOCALITY,
    ADDRESS,
    OTHER,
}

/** A place found by its name or address: a campsite, a gîte, a village, a street. */
data class FoundPlace(
    val name: String,
    /** Where it is: street, postcode and town, country. */
    val detail: String,
    val latitude: Double,
    val longitude: Double,
    val kind: FoundPlaceKind = FoundPlaceKind.OTHER,
)
