package fr.descentecanyon.app.domain.model

data class CotationRating(val raw: String, val vertical: Int? = null, val aquatic: Int? = null, val engagement: Int? = null) {
    val isKnown: Boolean = vertical != null && aquatic != null && engagement != null

    fun difficultyProfileDescending(): List<Int>? {
        if (!isKnown) return null
        return listOfNotNull(vertical, aquatic, engagement).sortedDescending()
    }

    companion object {
        private val cotationRegex = Regex("""(?i)v\s*(\d+)\s*/?\s*a\s*(\d+)\s*/?\s*([ivx]+)""")
        private val romanToInt = mapOf(
            "I" to 1,
            "II" to 2,
            "III" to 3,
            "IV" to 4,
            "V" to 5,
            "VI" to 6,
            "VII" to 7,
            "VIII" to 8,
            "IX" to 9,
            "X" to 10,
        )

        fun parse(raw: String?): CotationRating {
            val cleaned = raw.orEmpty().trim()
            val match = cotationRegex.matchEntire(cleaned)
            if (match == null) {
                return CotationRating(raw = cleaned)
            }

            return CotationRating(
                raw = cleaned,
                vertical = match.groupValues[1].toIntOrNull(),
                aquatic = match.groupValues[2].toIntOrNull(),
                engagement = romanToInt[match.groupValues[3].uppercase()],
            )
        }

        fun engagementLabel(value: Int?): String = romanToInt.entries.firstOrNull { it.value == value }?.key.orEmpty()
    }
}

fun CotationRating.matches(criteria: SearchCriteria): Boolean {
    if (criteria.hasCotationFilter() && !isKnown) return false
    return criteria.verticalRange.matches(vertical) &&
        criteria.aquaticRange.matches(aquatic) &&
        criteria.engagementRange.matches(engagement)
}

fun compareByDifficulty(left: CotationRating, right: CotationRating): Int {
    val leftProfile = left.difficultyProfileDescending()
    val rightProfile = right.difficultyProfileDescending()

    return when {
        leftProfile == null && rightProfile == null -> 0
        leftProfile == null -> 1
        rightProfile == null -> -1
        else -> {
            leftProfile.zip(rightProfile).firstOrNull { it.first != it.second }
                ?.let { (leftValue, rightValue) -> rightValue.compareTo(leftValue) }
                ?: 0
        }
    }
}
