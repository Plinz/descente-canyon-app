package fr.descentecanyon.app.domain.model

import java.time.ZoneId

/**
 * The time zone of a canyon, found offline from the country of the catalogue and, where a country
 * spans several zones (France and its overseas territories, the Canaries, Madeira and the Azores,
 * Australia), from its position. An outing is planned in the canyon's hours: a canyon of La Réunion
 * prepared from Lyon starts at 9:00 in La Réunion, as the sunset it is compared with.
 */
object CanyonTimeZone {

    /** The zone of a canyon of [country] ("France", "France , Espagne"...) at that position, null if unknown. */
    fun of(country: String?, latitude: Double?, longitude: Double?): ZoneId? {
        val first = country?.split(',')?.firstOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val id = when (first) {
            "france" -> france(latitude, longitude)
            "espagne" -> if (longitude != null && longitude < CANARIES_EAST_LONGITUDE) "Atlantic/Canary" else "Europe/Madrid"
            "portugal" -> portugal(longitude)
            "australie" -> australia(latitude, longitude)
            else -> SINGLE_ZONE_COUNTRIES[first]
        } ?: return null
        return runCatching { ZoneId.of(id) }.getOrNull()
    }

    private fun france(latitude: Double?, longitude: Double?): String {
        if (latitude == null || longitude == null) return "Europe/Paris"
        return when {
            longitude in 54.0..56.5 && latitude in -22.0..-20.0 -> "Indian/Reunion"
            longitude in 44.8..45.5 && latitude in -13.2..-12.4 -> "Indian/Mayotte"
            longitude in -62.0..-60.9 && latitude in 15.8..16.6 -> "America/Guadeloupe"
            longitude in -61.3..-60.7 && latitude in 14.3..14.95 -> "America/Martinique"
            longitude in -155.0..-134.0 -> "Pacific/Tahiti"
            longitude in 163.0..169.0 && latitude in -23.0..-19.0 -> "Pacific/Noumea"
            longitude in -178.5..-175.8 -> "Pacific/Wallis"
            else -> "Europe/Paris"
        }
    }

    private fun portugal(longitude: Double?): String = when {
        longitude == null -> "Europe/Lisbon"
        longitude < AZORES_EAST_LONGITUDE -> "Atlantic/Azores"
        longitude < MADEIRA_EAST_LONGITUDE -> "Atlantic/Madeira"
        else -> "Europe/Lisbon"
    }

    /** By state, roughly: the borders the canyons of the catalogue are far from. */
    private fun australia(latitude: Double?, longitude: Double?): String = when {
        latitude == null || longitude == null -> "Australia/Sydney"
        longitude < 129.0 -> "Australia/Perth"
        longitude < 138.0 && latitude > -26.0 -> "Australia/Darwin"
        longitude < 141.0 -> "Australia/Adelaide"
        latitude > -29.0 -> "Australia/Brisbane"
        latitude < -39.5 -> "Australia/Hobart"
        else -> "Australia/Sydney"
    }

    private const val CANARIES_EAST_LONGITUDE = -12.0
    private const val MADEIRA_EAST_LONGITUDE = -15.0
    private const val AZORES_EAST_LONGITUDE = -24.0

    /** Countries of the catalogue with one time zone, by the name the site gives them. */
    private val SINGLE_ZONE_COUNTRIES = mapOf(
        "italie" to "Europe/Rome",
        "suisse" to "Europe/Zurich",
        "autriche" to "Europe/Vienna",
        "grèce" to "Europe/Athens",
        "allemagne" to "Europe/Berlin",
        "nouvelle zélande" to "Pacific/Auckland",
        "slovénie" to "Europe/Ljubljana",
        "dominique (île)" to "America/Dominica",
        "jordanie" to "Asia/Amman",
        "nepal" to "Asia/Kathmandu",
        "népal" to "Asia/Kathmandu",
        "oman" to "Asia/Muscat",
        "maroc" to "Africa/Casablanca",
        "cap-vert" to "Atlantic/Cape_Verde",
        "chili" to "America/Santiago",
        "turquie" to "Europe/Istanbul",
        "monténégro" to "Europe/Podgorica",
        "bulgarie" to "Europe/Sofia",
        "madagascar" to "Indian/Antananarivo",
        "bolivie" to "America/La_Paz",
        "andorre" to "Europe/Andorra",
        "venezuela" to "America/Caracas",
        "macédoine" to "Europe/Skopje",
        "djibouti" to "Africa/Djibouti",
        "croatie" to "Europe/Zagreb",
        "maurice (île)" to "Indian/Mauritius",
        "chine" to "Asia/Shanghai",
        "belgique" to "Europe/Brussels",
        "luxembourg" to "Europe/Luxembourg",
        "royaume-uni" to "Europe/London",
        "norvège" to "Europe/Oslo",
        "slovaquie" to "Europe/Bratislava",
        "bosnie-herzégovine" to "Europe/Sarajevo",
        "albanie" to "Europe/Tirane",
        "roumanie" to "Europe/Bucharest",
        "pologne" to "Europe/Warsaw",
        "tchéquie" to "Europe/Prague",
        "costa rica" to "America/Costa_Rica",
        "colombie" to "America/Bogota",
        "pérou" to "America/Lima",
        "japon" to "Asia/Tokyo",
    )
}
