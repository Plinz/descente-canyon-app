package fr.descentecanyon.app.data.mapper

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Utility for parsing various date formats found on descente-canyon.com
 * into ISO-8601 date strings suitable for Room storage.
 *
 * Common formats encountered:
 * - French long:   "dim. 22 mars 2026"
 * - French short:  "22/03" (day/month, current year assumed)
 * - ISO-like:      "22-03-2026" or "2026-03-22"
 */
object DateParser {

    private val FRENCH_MONTHS = mapOf(
        "janvier" to 1, "janv" to 1,
        "février" to 2, "fevrier" to 2, "févr" to 2, "fevr" to 2,
        "mars" to 3,
        "avril" to 4, "avr" to 4,
        "mai" to 5,
        "juin" to 6,
        "juillet" to 7, "juil" to 7,
        "août" to 8, "aout" to 8,
        "septembre" to 9, "sept" to 9,
        "octobre" to 10, "oct" to 10,
        "novembre" to 11, "nov" to 11,
        "décembre" to 12, "decembre" to 12, "déc" to 12, "dec" to 12,
    )

    // Matches: "dim. 22 mars 2026", "lun 1 juin 2025", "mar. 15 janvier 25"
    private val FRENCH_LONG_REGEX = Regex(
        """(?:\w+\.?\s+)?(\d{1,2})\s+(\p{L}+\.?)(?:\s+(\d{2,4}))""",
    )

    private val DAY_MONTH_REGEX = Regex("""(\d{1,2})/(\d{1,2})""")

    private val FRENCH_DAY_MONTH_REGEX = Regex("""(?:\p{L}+\.?\s+)?(\d{1,2})/(\d{1,2})""")

    private val DMY_SLASH_REGEX = Regex("""(?:\p{L}+\.?\s+)?(\d{1,2})/(\d{1,2})/(\d{2,4})""")

    private val DMY_DASH_REGEX = Regex("""(\d{2})-(\d{2})-(\d{4})""")

    /**
     * Attempts to parse a raw date string from the website into an ISO date string (yyyy-MM-dd).
     * Returns null if no known format matches.
     */
    fun parseToIsoString(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        return tryParseIso(trimmed)
            ?: tryParseFrenchLong(trimmed)
            ?: tryParseDmySlash(trimmed)
            ?: tryParseFrenchDayMonth(trimmed)
            ?: tryParseDayMonthSlash(trimmed)
            ?: tryParseDmyDash(trimmed)
    }

    /**
     * Attempts to parse a raw date string into a [LocalDate].
     * Returns null if no known format matches.
     */
    fun parseToLocalDate(raw: String): LocalDate? {
        val iso = parseToIsoString(raw) ?: return null
        return try {
            LocalDate.parse(iso)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    // --- Private parsers ---

    private fun tryParseIso(value: String): String? = try {
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
        value
    } catch (_: DateTimeParseException) {
        null
    }

    /**
     * "dim. 22 mars 2026", "lun 1 juin 2025", etc.
     * Uses regex + month lookup to avoid DateTimeFormatter locale issues.
     */
    private fun tryParseFrenchLong(value: String): String? {
        val normalized = value
            .replace('\u00A0', ' ')
            .lowercase(Locale.FRENCH)
            .replace(Regex("\\s+"), " ")
            .trim()
        val match = FRENCH_LONG_REGEX.find(normalized) ?: return null

        val day = match.groupValues[1].toIntOrNull() ?: return null
        val monthName = match.groupValues[2].trim().trimEnd('.')
        val year = normalizeYear(match.groupValues[3].toIntOrNull() ?: return null)
        val month = FRENCH_MONTHS[monthName] ?: return null

        return try {
            LocalDate.of(year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * "dim. 22/03/2026", "22/03/24"
     */
    private fun tryParseDmySlash(value: String): String? {
        val match = DMY_SLASH_REGEX.matchEntire(value.trim()) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val year = normalizeYear(match.groupValues[3].toIntOrNull() ?: return null)
        return try {
            LocalDate.of(year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * "dim. 22/03" -> ignores weekday, assumes current year.
     */
    private fun tryParseFrenchDayMonth(value: String): String? {
        val match = FRENCH_DAY_MONTH_REGEX.matchEntire(value.trim()) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        return try {
            LocalDate.of(LocalDate.now().year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * "22/03" -> assumes current year
     */
    private fun tryParseDayMonthSlash(value: String): String? {
        val match = DAY_MONTH_REGEX.matchEntire(value.trim()) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        return try {
            LocalDate.of(LocalDate.now().year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * "22-03-2026" (dd-MM-yyyy)
     */
    private fun tryParseDmyDash(value: String): String? {
        val match = DMY_DASH_REGEX.matchEntire(value.trim()) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val year = match.groupValues[3].toIntOrNull() ?: return null
        return try {
            LocalDate.of(year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeYear(year: Int): Int = if (year in 0..99) 2000 + year else year
}
