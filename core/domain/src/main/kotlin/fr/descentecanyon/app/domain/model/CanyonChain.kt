package fr.descentecanyon.app.domain.model

/**
 * What the topos say of going from the end of a canyon to the start of another one, reviewed by hand
 * over the whole catalog (data-overlay/canyon-chains.json, from the GPS points and the topos).
 */
data class CanyonChain(
    val fromCanyonId: Int,
    val toCanyonId: Int,
    val way: Way,
    /** From the end of one descent to the start of the other, when the topos give it. */
    val minutes: Int? = null,
) {
    enum class Way {
        /** Straight on in the stream, at most a short walk in its bed: no way out, no way in. */
        STRAIGHT_ON,

        /** Out of one and on foot to the other, the walk given by the topos. */
        ON_FOOT,

        /** One after the other, but the topos forbid going on in the stream (a ban, a compulsory exit). */
        NOT_IN_STREAM,
    }

    /** The link the topos describe; null when they forbid going on in the stream. */
    val link: CanyonLink? get() = when (way) {
        Way.STRAIGHT_ON -> CanyonLink.CONTINUOUS
        Way.ON_FOOT -> CanyonLink.ON_FOOT
        Way.NOT_IN_STREAM -> null
    }

    /** The time from one to the other the topos give for [link], replacing a way out and a way in. */
    fun minutesFor(link: CanyonLink): Int? = minutes?.takeIf { this.link == link }
}
