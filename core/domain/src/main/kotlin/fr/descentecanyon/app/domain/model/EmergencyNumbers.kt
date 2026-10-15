package fr.descentecanyon.app.domain.model

/** What an emergency number reaches. */
enum class EmergencyService {
    /** 112 and the like: every emergency, with a call routed to the right service. */
    GENERAL,
    MEDICAL,
    FIRE,
    MOUNTAIN_RESCUE,
}

data class EmergencyNumber(val number: String, val service: EmergencyService)

/**
 * Emergency numbers by country, keyed by the country names of the catalog. 112 works from any
 * mobile phone in Europe, even without a SIM card or with another operator's network; only numbers
 * known for sure are listed, other countries get 112 and a hint to check the local number.
 */
object EmergencyNumbers {
    val EUROPEAN = EmergencyNumber("112", EmergencyService.GENERAL)

    private val byCountry: Map<String, List<EmergencyNumber>> = mapOf(
        "france" to listOf(EUROPEAN, EmergencyNumber("15", EmergencyService.MEDICAL), EmergencyNumber("18", EmergencyService.FIRE)),
        "suisse" to
            listOf(EUROPEAN, EmergencyNumber("1414", EmergencyService.MOUNTAIN_RESCUE), EmergencyNumber("144", EmergencyService.MEDICAL)),
        "italie" to listOf(EUROPEAN, EmergencyNumber("118", EmergencyService.MEDICAL)),
        "autriche" to
            listOf(EUROPEAN, EmergencyNumber("140", EmergencyService.MOUNTAIN_RESCUE), EmergencyNumber("144", EmergencyService.MEDICAL)),
        "grece" to listOf(EUROPEAN, EmergencyNumber("166", EmergencyService.MEDICAL)),
        "espagne" to listOf(EUROPEAN),
        "portugal" to listOf(EUROPEAN),
        "allemagne" to listOf(EUROPEAN),
        "slovenie" to listOf(EUROPEAN),
        "andorre" to listOf(EUROPEAN),
        "croatie" to listOf(EUROPEAN),
        "bulgarie" to listOf(EUROPEAN),
        "montenegro" to listOf(EUROPEAN),
        "macedoine" to listOf(EUROPEAN),
        "turquie" to listOf(EUROPEAN),
        "nouvelle zelande" to listOf(EmergencyNumber("111", EmergencyService.GENERAL)),
        "australie" to listOf(EmergencyNumber("000", EmergencyService.GENERAL), EUROPEAN),
    )

    /** Numbers for the first country of [countries] ("France , Espagne" lists both); 112 at least. */
    fun forCountries(countries: String): List<EmergencyNumber> = countries.split(",")
        .map { it.normalizeForSearch().trim() }
        .firstNotNullOfOrNull { byCountry[it] }
        ?: listOf(EUROPEAN)

    /** Whether the numbers of [countries] are known for sure (otherwise the local number must be checked). */
    fun isKnown(countries: String): Boolean = countries.split(",").any { it.normalizeForSearch().trim() in byCountry }
}
