package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.util.Locale

/**
 * Canyons added in the app: the site takes no new canyon, so the app's users propose them and the
 * operator of the app's server publishes them after review. They are numbered from [FIRST_ID], far
 * above the site's sheets, and come in the catalog like the others, without photos nor flow reports
 * (those live on the site, which does not know them).
 */
object AppCanyons {
    const val FIRST_ID = 1_000_000

    fun isAppCanyon(canyonId: Int): Boolean = canyonId >= FIRST_ID
}

/** Added in the app rather than read from the site. */
val Canyon.isAddedInApp: Boolean get() = sourceType == CanyonSourceType.APP || AppCanyons.isAppCanyon(id)

/** The sheet of a canyon proposed in the app: the fields of a sheet of the site, in its formats. */
data class CanyonSheet(
    val nom: String = "",
    val nomComplet: String = "",
    val pays: String = "",
    val region: String = "",
    val departement: String = "",
    val commune: String = "",
    val massif: String = "",
    val bassin: String = "",
    val coursEau: String = "",
    val cotation: CotationRating? = null,
    val altitudeDepart: Int? = null,
    val denivele: Int? = null,
    val longueur: Int? = null,
    val cascadeMax: Int? = null,
    val cordeMin: Int? = null,
    /** "45min", "1h30", "20 à 45min", "néant"; empty when unknown. */
    val tempsApproche: String = "",
    val tempsDescente: String = "",
    val tempsRetour: String = "",
    /** "néant", "5.0km"; empty when unknown. */
    val navette: String = "",
    val accesAval: String = "",
    val accesAmont: String = "",
    val approche: String = "",
    val descente: String = "",
    val retour: String = "",
    val engagement: String = "",
    val periode: String = "",
    val geologie: String = "",
    val historique: String = "",
    val remarques: String = "",
    val isForbidden: Boolean = false,
    val points: List<SheetPoint> = emptyList(),
) {
    /**
     * What keeps the sheet from being sent, empty when it can be. A name, one point of any type and
     * one text are enough: the place comes from the point, the rest can come later.
     */
    fun problems(): Set<SheetProblem> = buildSet {
        if (nom.isBlank()) add(SheetProblem.NAME)
        if (points.isEmpty()) add(SheetProblem.POINT)
        if (texts().all { it.isBlank() }) add(SheetProblem.DESCRIPTION)
        if (listOf(tempsApproche, tempsDescente, tempsRetour).any { !isDuration(it) }) add(SheetProblem.DURATION)
        if (navette.isNotBlank() && navette != NO_SHUTTLE && !SHUTTLE.matches(navette)) add(SheetProblem.SHUTTLE)
        if (pointsTooFarApart()) add(SheetProblem.POINTS_TOO_FAR_APART)
        val numbers = listOf(altitudeDepart to 6_000, denivele to 3_000, longueur to 50_000, cascadeMax to 500, cordeMin to 1_000)
        if (numbers.any { (value, max) -> value != null && value !in 0..max }) add(SheetProblem.NUMBER)
        if (texts().any { it.length > MAX_TEXT_LENGTH }) add(SheetProblem.TEXT_TOO_LONG)
    }

    /** The point that places the canyon: its entry, else its first point (a parking, an exit...). */
    val anchor: SheetPoint? get() = anchorOf(points)

    private fun texts() = listOf(accesAval, accesAmont, approche, descente, retour, engagement, periode, geologie, historique, remarques)

    private fun pointsTooFarApart(): Boolean = points.withIndex().any { (index, point) ->
        points.drop(index + 1).any { other ->
            haversineKm(point.latitude, point.longitude, other.latitude, other.longitude) > MAX_SPREAD_KM
        }
    }

    companion object {
        const val NO_SHUTTLE = "néant"
        const val MAX_TEXT_LENGTH = 20_000
        const val MAX_POINTS = 30
        const val MAX_SPREAD_KM = 30.0
        private val SHUTTLE = Regex("""^\d{1,3}\.\d+km$""")

        fun anchorOf(points: List<SheetPoint>): SheetPoint? = points.firstOrNull { it.type == GeoPointType.ENTREE } ?: points.firstOrNull()

        /** Blank (unknown), "néant", or a duration the app reads, within the site's 10 characters. */
        fun isDuration(text: String): Boolean {
            val value = text.trim()
            if (value.isEmpty()) return true
            if (value.length > CanyonDuration.SITE_FIELD_LENGTH) return false
            return CanyonDuration.parse(value) != CanyonDuration.Unknown
        }

        /** The site's shuttle field for a distance typed in km: "0" is no shuttle, blank is unknown. */
        fun shuttleOf(kilometres: String): String {
            val value = kilometres.trim().replace(',', '.').toDoubleOrNull() ?: return ""
            if (value <= 0.0) return NO_SHUTTLE
            return String.format(Locale.ROOT, "%.1fkm", value)
        }

        /** The distance in km of a shuttle field, for editing ("0" for none, blank when unknown). */
        fun kilometresOf(navette: String): String = when {
            navette == NO_SHUTTLE -> "0"
            else -> navette.removeSuffix("km").takeIf { it.toDoubleOrNull() != null }?.removeSuffix(".0").orEmpty()
        }

        fun cotationText(rating: CotationRating?): String {
            val vertical = rating?.vertical ?: return ""
            val aquatic = rating.aquatic ?: return ""
            val engagement = rating.engagement ?: return ""
            return "v${vertical}a$aquatic${ROMAN[engagement - 1]}"
        }

        val ROMAN = listOf("I", "II", "III", "IV", "V", "VI")
    }
}

/** A GPS point of a proposed sheet. */
data class SheetPoint(val type: GeoPointType, val latitude: Double, val longitude: Double, val title: String = "")

enum class SheetProblem {
    NAME,

    /** No GPS point at all. */
    POINT,

    /** None of the texts (approach, descent, remarks...) is written. */
    DESCRIPTION,
    DURATION,
    SHUTTLE,
    NUMBER,
    TEXT_TOO_LONG,
    POINTS_TOO_FAR_APART,
}

/** Where a new canyon is, taken from the catalog canyon nearest to its point (same spelling as the site). */
data class CanyonPlace(
    val pays: String,
    val region: String?,
    val departement: String?,
    val commune: String?,
    val massif: String?,
    val bassin: String?,
)

enum class ProposalKind {
    NEW,
    EDIT,
}

enum class ProposalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN,
}

/** Why the operator refused a proposal. */
enum class ProposalRejectionReason {
    DUPLICATE,
    EXISTS_ON_SITE,
    FORBIDDEN,
    INCOMPLETE,
    INCORRECT,
    NOT_A_CANYON,
    OTHER,
    BLOCKED,
}

/** A canyon or a change sent from this installation, and what became of it. */
data class CanyonProposal(
    val id: String,
    val kind: ProposalKind,
    /** The canyon changed, or the canyon created once approved. */
    val canyonId: Int?,
    val name: String,
    val status: ProposalStatus,
    val createdAt: Instant,
    val decidedAt: Instant? = null,
    val reason: ProposalRejectionReason? = null,
    /** The operator's word to the author, if any. */
    val note: String? = null,
)

/** Why the app's server refused to take a proposal. */
enum class ProposalRefusal {
    INVALID,
    RATE_LIMITED,
    TOO_MANY_PENDING,
    BLOCKED,
    UNKNOWN_CANYON,
    NOT_EDITABLE,
    NO_CHANGE,
    ALREADY_DECIDED,
    TOO_LARGE,
}
