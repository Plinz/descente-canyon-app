package fr.descentecanyon.app.domain.model

/** A duration given as a range on the canyon sheets ("20 à 45min", "2-3h"), in minutes. */
data class DurationRange(val minMinutes: Int, val maxMinutes: Int) {
    init {
        require(minMinutes in 0..maxMinutes) { "Invalid duration range $minMinutes..$maxMinutes" }
    }

    companion object {
        val NONE = DurationRange(0, 0)
    }
}

/** How a duration field of a canyon sheet reads. */
sealed interface CanyonDuration {
    /** "néant": no approach or no return walk. */
    data object None : CanyonDuration

    /** "??", "variable" or text that is not a duration. */
    data object Unknown : CanyonDuration

    data class Known(val range: DurationRange) : CanyonDuration

    companion object {
        private val NONE_WORDS = setOf("néant", "neant", "aucun", "aucune", "0", "0min")
        private val PART_SEPARATORS = Regex("""\s+(?:à|ou|a)\s+|\s*[-/,–—]\s*""")

        /** The site keeps these fields to this many characters: a longer duration is cut, "1h15 à 1h4" for "1h15 à 1h45". */
        const val SITE_FIELD_LENGTH = 10
        private val COMPLETE_END = Regex("""(?:min|mn|h|h\d{2}|jours?|j|')$""")
        private val STARTED_MINUTES = Regex("""^(\d{1,3})\s*h\s*(\d)$""")

        /** Cut by the site, its last part unfinished: "1h15 à 1h4", "45min ou 1". */
        fun isCut(raw: String?): Boolean {
            val text = raw?.trim() ?: return false
            return text.length == SITE_FIELD_LENGTH && !COMPLETE_END.containsMatchIn(text.lowercase())
        }

        private val PART = Regex("""^(\d{1,3})\s*(?:(h|heures?|j|jours?)\s*(\d{1,2})?\s*(?:mins?|mn|mi|m)?|(mins?|mn|mi|m|'))?$""")

        /** The parts of an outing (approach, descent, return) added up; null when one of them is unknown. */
        fun total(vararg parts: CanyonDuration): DurationRange? {
            var low = 0
            var high = 0
            for (part in parts) {
                when (part) {
                    None -> Unit

                    Unknown -> return null

                    is Known -> {
                        low += part.range.minMinutes
                        high += part.range.maxMinutes
                    }
                }
            }
            return DurationRange(low, high).takeIf { high > 0 }
        }

        /** Reads a duration of a canyon sheet; a bare number is a number of minutes ("5" for an approach). */
        fun parse(raw: String?): CanyonDuration {
            val text = raw?.trim()?.lowercase()?.trimEnd('.')?.replace(Regex("""\s+"""), " ").orEmpty()
            if (text.isEmpty()) return Unknown
            if (text in NONE_WORDS) return None
            val parts = text.split(PART_SEPARATORS).filter { it.isNotBlank() }.let { parts ->
                if (isCut(raw) && parts.isNotEmpty()) parts.dropLast(1) + listOfNotNull(finished(parts.last().trim())) else parts
            }
            if (parts.isEmpty() || parts.size > 2) return Unknown
            val parsed = parts.map { PART.matchEntire(it.trim()) ?: return Unknown }
            val units = parsed.map { match -> unitOf(match) }
            val minutes = parsed.mapIndexed { index, match ->
                val number = match.groupValues[1].toInt()
                // "2 à 3h", "20 à 45min": a bare number takes the unit of the next part.
                val unit = units[index] ?: units.drop(index + 1).firstOrNull { it != null } ?: Unit.MINUTE
                when (unit) {
                    Unit.DAY -> number * MINUTES_PER_DAY
                    Unit.HOUR -> number * 60 + (match.groupValues[3].toIntOrNull() ?: 0)
                    Unit.MINUTE -> number
                }
            }
            val low = minutes.min()
            val high = minutes.max()
            return if (high == 0) None else Known(DurationRange(low, high))
        }

        /**
         * The last part of a duration cut by the site: minutes started count in tens ("1h4" is at least
         * 1 h 40), a number cut from its unit is left out.
         */
        private fun finished(part: String): String? {
            STARTED_MINUTES.matchEntire(part)?.let { return "${it.groupValues[1]}h${it.groupValues[2]}0" }
            return part.takeUnless { it.all(Char::isDigit) }
        }

        private fun unitOf(match: MatchResult): Unit? {
            val hourOrDay = match.groupValues[2]
            return when {
                hourOrDay.startsWith("h") -> Unit.HOUR
                hourOrDay.startsWith("j") -> Unit.DAY
                match.groupValues[4].isNotEmpty() -> Unit.MINUTE
                else -> null
            }
        }

        private const val MINUTES_PER_DAY = 24 * 60
    }

    private enum class Unit { MINUTE, HOUR, DAY }
}
