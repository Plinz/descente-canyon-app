package fr.descentecanyon.app.domain.model

data class CanyonInterestRating(
    val canyonId: Int,
    val personalRating: Float? = null,
    val averageRating: Float? = null,
    val medianRating: Float? = null,
    val voteCount: Int? = null,
)

data class InterestRatingSubmission(val canyonId: Int, val rating: Float)
