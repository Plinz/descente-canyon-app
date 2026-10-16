package fr.descentecanyon.app.domain.model

/** What an emergency number reaches. */
enum class EmergencyService {
    /** 112, 911 and the like: every emergency, with a call routed to the right service. */
    GENERAL,
    MEDICAL,
    FIRE,
    POLICE,

    /** Police of the countryside where it is separate (Morocco, Madagascar, Algeria, Tunisia...). */
    GENDARMERIE,

    /** Police for travellers, usually speaking English. */
    TOURIST_POLICE,
    MOUNTAIN_RESCUE,
}

data class EmergencyNumber(val number: String, val service: EmergencyService)

/**
 * Emergency numbers of the countries with canyons, the first one to call first. Taken from the
 * travel advice of the UK Foreign Office (October 2026), checked against the French one when they
 * differ; a number the sources disagree on is left out. Numbers are the ones to dial from a mobile.
 * Countries are found by their ISO 3166 code (the mobile network) or their name in the catalog.
 */
object EmergencyNumbers {
    val EUROPEAN = EmergencyNumber("112", EmergencyService.GENERAL)

    /** Numbers of [countries] ("France , Espagne" gives the French ones); 112 when none is known. */
    fun forCountries(countries: String): List<EmergencyNumber> = isoCodeOf(countries)?.let(byIsoCode::get) ?: listOf(EUROPEAN)

    /** Whether the numbers of [countries] are known (otherwise the local number must be checked). */
    fun isKnown(countries: String): Boolean = isoCodeOf(countries) != null

    /** Numbers of the country with this ISO 3166 code ("fr", "CO"), such as the mobile network's one. */
    fun forIsoCode(isoCode: String?): List<EmergencyNumber>? = isoCode?.trim()?.lowercase()?.let(byIsoCode::get)

    /** ISO code of the first country of [countries] whose numbers are known. */
    private fun isoCodeOf(countries: String): String? = countries.split(",")
        .map { it.normalizeForSearch() }
        .firstNotNullOfOrNull { name -> isoCodeByCatalogName[name]?.takeIf { it in byIsoCode } }

    private fun numbers(vararg entries: Pair<String, EmergencyService>): List<EmergencyNumber> =
        entries.map { (number, service) -> EmergencyNumber(number, service) }

    private val GENERAL = EmergencyService.GENERAL
    private val MEDICAL = EmergencyService.MEDICAL
    private val FIRE = EmergencyService.FIRE
    private val POLICE = EmergencyService.POLICE
    private val GENDARMERIE = EmergencyService.GENDARMERIE
    private val TOURIST_POLICE = EmergencyService.TOURIST_POLICE
    private val MOUNTAIN = EmergencyService.MOUNTAIN_RESCUE

    private val FRANCE = numbers("112" to GENERAL, "15" to MEDICAL, "18" to FIRE, "17" to POLICE)
    private val FRENCH_PACIFIC = numbers("15" to MEDICAL, "18" to FIRE, "17" to POLICE)
    private val SINGLE_112 = numbers("112" to GENERAL)
    private val SINGLE_911 = numbers("911" to GENERAL)
    private val BALKANS = numbers("112" to GENERAL, "194" to MEDICAL, "193" to FIRE, "192" to POLICE)
    private val CENTRAL_ASIA = numbers("112" to GENERAL, "103" to MEDICAL, "101" to FIRE, "102" to POLICE)
    private val SWISS = numbers("112" to GENERAL, "1414" to MOUNTAIN, "144" to MEDICAL, "117" to POLICE, "118" to FIRE)

    private val byIsoCode: Map<String, List<EmergencyNumber>> = mapOf(
        // Europe: 112 works from any mobile, with any network available.
        "fr" to FRANCE,
        "mc" to numbers("112" to GENERAL, "18" to FIRE),
        "ad" to numbers("112" to GENERAL, "116" to MEDICAL, "118" to FIRE, "110" to POLICE),
        "es" to SINGLE_112,
        "pt" to SINGLE_112,
        "it" to numbers("112" to GENERAL, "118" to MEDICAL, "115" to FIRE),
        "sm" to numbers("112" to GENERAL, "118" to MEDICAL, "115" to FIRE),
        "ch" to SWISS,
        "li" to SWISS,
        "at" to numbers("112" to GENERAL, "140" to MOUNTAIN, "144" to MEDICAL, "133" to POLICE, "122" to FIRE),
        "de" to numbers("112" to GENERAL, "110" to POLICE),
        "be" to SINGLE_112,
        "nl" to SINGLE_112,
        "lu" to numbers("112" to GENERAL, "113" to POLICE),
        "gb" to numbers("999" to GENERAL, "112" to GENERAL),
        "ie" to numbers("112" to GENERAL, "999" to GENERAL),
        "is" to SINGLE_112,
        "no" to numbers("112" to POLICE, "113" to MEDICAL, "110" to FIRE),
        "se" to SINGLE_112,
        "fi" to SINGLE_112,
        "dk" to SINGLE_112,
        "ee" to SINGLE_112,
        "lv" to numbers("112" to GENERAL, "113" to MEDICAL, "110" to POLICE),
        "lt" to SINGLE_112,
        "pl" to numbers("112" to GENERAL, "985" to MOUNTAIN, "999" to MEDICAL, "998" to FIRE, "997" to POLICE),
        "cz" to numbers("112" to GENERAL, "1210" to MOUNTAIN, "155" to MEDICAL, "150" to FIRE, "158" to POLICE),
        "sk" to numbers("112" to GENERAL, "18300" to MOUNTAIN, "155" to MEDICAL, "150" to FIRE, "158" to POLICE),
        "hu" to SINGLE_112,
        "ro" to SINGLE_112,
        "bg" to SINGLE_112,
        "gr" to numbers("112" to GENERAL, "166" to MEDICAL, "199" to FIRE, "100" to POLICE),
        "cy" to SINGLE_112,
        "mt" to SINGLE_112,
        "si" to numbers("112" to GENERAL, "113" to POLICE),
        "hr" to BALKANS,
        "rs" to BALKANS,
        "xk" to BALKANS,
        "mk" to BALKANS,
        "me" to numbers("112" to GENERAL, "124" to MEDICAL, "123" to FIRE, "122" to POLICE),
        "ba" to numbers("124" to MEDICAL, "123" to FIRE, "122" to POLICE),
        "al" to numbers("112" to GENERAL, "127" to MEDICAL, "128" to FIRE),
        "tr" to SINGLE_112,
        "ge" to SINGLE_112,
        "am" to SINGLE_911,
        // Africa and Indian Ocean.
        "ma" to numbers("15" to FIRE, "177" to GENDARMERIE, "19" to POLICE),
        "dz" to numbers("14" to FIRE, "1055" to GENDARMERIE, "1548" to POLICE),
        "tn" to numbers("190" to MEDICAL, "198" to FIRE, "193" to GENDARMERIE, "197" to POLICE),
        "eg" to numbers("123" to MEDICAL, "122" to POLICE, "180" to FIRE, "126" to TOURIST_POLICE),
        "cv" to numbers("130" to MEDICAL, "131" to FIRE, "132" to POLICE),
        "dj" to numbers("18" to FIRE, "17" to POLICE),
        "mg" to numbers("118" to FIRE, "117" to POLICE, "119" to GENDARMERIE),
        "mu" to numbers("999" to GENERAL, "114" to MEDICAL, "115" to FIRE, "112" to POLICE),
        "re" to FRANCE,
        "yt" to FRANCE,
        "za" to numbers("112" to GENERAL, "10177" to MEDICAL, "10111" to POLICE),
        "na" to numbers("10111" to GENERAL, "112" to GENERAL),
        "ke" to numbers("999" to GENERAL),
        "tz" to SINGLE_112,
        // Americas and Caribbean.
        "us" to SINGLE_911,
        "ca" to SINGLE_911,
        "mx" to SINGLE_911,
        "bz" to SINGLE_911,
        "gt" to numbers("122" to MEDICAL, "110" to POLICE, "1500" to TOURIST_POLICE),
        "sv" to SINGLE_911,
        "hn" to SINGLE_911,
        "ni" to SINGLE_911,
        "cr" to SINGLE_911,
        "pa" to SINGLE_911,
        "co" to numbers("123" to GENERAL, "119" to FIRE),
        "ve" to SINGLE_911,
        "ec" to SINGLE_911,
        "pe" to numbers("106" to MEDICAL, "116" to FIRE, "105" to POLICE),
        "bo" to numbers("118" to MEDICAL, "119" to FIRE, "110" to POLICE),
        "cl" to numbers("133" to POLICE, "131" to MEDICAL, "136" to MOUNTAIN, "132" to FIRE),
        "ar" to numbers("911" to GENERAL, "107" to MEDICAL, "100" to FIRE),
        "br" to numbers("192" to MEDICAL, "193" to FIRE, "190" to POLICE),
        "py" to numbers("911" to GENERAL, "141" to MEDICAL, "132" to FIRE),
        "uy" to SINGLE_911,
        "cu" to numbers("104" to MEDICAL, "105" to FIRE, "106" to POLICE),
        "do" to SINGLE_911,
        "jm" to numbers("110" to MEDICAL, "119" to POLICE),
        "dm" to numbers("999" to GENERAL),
        "lc" to SINGLE_911,
        "vc" to numbers("911" to GENERAL, "999" to POLICE),
        "gd" to SINGLE_911,
        "tt" to numbers("811" to MEDICAL, "990" to FIRE, "999" to POLICE),
        "gp" to FRANCE,
        "mq" to FRANCE,
        "gf" to FRANCE,
        "bl" to FRANCE,
        "mf" to FRANCE,
        "pm" to FRANCE,
        // Asia.
        "jo" to SINGLE_911,
        "om" to numbers("9999" to GENERAL),
        "lb" to numbers("140" to MEDICAL, "175" to FIRE, "112" to POLICE),
        "il" to numbers("101" to MEDICAL, "102" to FIRE, "100" to POLICE),
        "ir" to numbers("115" to MEDICAL, "125" to FIRE, "110" to POLICE),
        "np" to numbers("100" to POLICE, "1144" to TOURIST_POLICE, "102" to MEDICAL, "101" to FIRE),
        "in" to SINGLE_112,
        "lk" to numbers("1990" to MEDICAL, "119" to POLICE),
        "cn" to numbers("120" to MEDICAL, "110" to POLICE, "119" to FIRE),
        "tw" to numbers("119" to MEDICAL, "110" to POLICE),
        "jp" to numbers("119" to MEDICAL, "110" to POLICE),
        "kr" to numbers("119" to MEDICAL, "112" to POLICE),
        "ph" to SINGLE_911,
        "th" to numbers("1669" to MEDICAL, "191" to POLICE, "1155" to TOURIST_POLICE, "199" to FIRE),
        "vn" to numbers("115" to MEDICAL, "114" to FIRE, "113" to POLICE),
        "my" to numbers("999" to GENERAL, "994" to FIRE),
        "id" to numbers("112" to GENERAL, "118" to MEDICAL, "110" to POLICE),
        "mn" to numbers("103" to MEDICAL, "101" to FIRE, "102" to POLICE),
        "kg" to CENTRAL_ASIA,
        "kz" to CENTRAL_ASIA,
        "uz" to CENTRAL_ASIA,
        "tj" to numbers("103" to MEDICAL, "101" to FIRE, "102" to POLICE),
        // Oceania.
        "au" to numbers("000" to GENERAL, "112" to GENERAL),
        "nz" to numbers("111" to GENERAL),
        "pf" to FRENCH_PACIFIC,
        "nc" to FRENCH_PACIFIC,
    )

    /** Country names of descente-canyon.com (normalized), with their ISO code. */
    private val isoCodeByCatalogName: Map<String, String> = mapOf(
        "france" to "fr",
        "espagne" to "es",
        "italie" to "it",
        "suisse" to "ch",
        "autriche" to "at",
        "portugal" to "pt",
        "grece" to "gr",
        "allemagne" to "de",
        "nouvelle zelande" to "nz",
        "slovenie" to "si",
        "dominique ile" to "dm",
        "dominique" to "dm",
        "jordanie" to "jo",
        "nepal" to "np",
        "australie" to "au",
        "oman" to "om",
        "maroc" to "ma",
        "cap vert" to "cv",
        "chili" to "cl",
        "turquie" to "tr",
        "bulgarie" to "bg",
        "montenegro" to "me",
        "bolivie" to "bo",
        "madagascar" to "mg",
        "andorre" to "ad",
        "venezuela" to "ve",
        "croatie" to "hr",
        "macedoine" to "mk",
        "macedoine du nord" to "mk",
        "djibouti" to "dj",
        "maurice ile" to "mu",
        "maurice" to "mu",
        "chine" to "cn",
        // Other countries with canyons, should the site add them.
        "colombie" to "co",
        "costa rica" to "cr",
        "mexique" to "mx",
        "etats unis" to "us",
        "canada" to "ca",
        "bresil" to "br",
        "perou" to "pe",
        "equateur" to "ec",
        "guatemala" to "gt",
        "argentine" to "ar",
        "cuba" to "cu",
        "republique dominicaine" to "do",
        "guadeloupe" to "gp",
        "martinique" to "mq",
        "guyane" to "gf",
        "reunion" to "re",
        "la reunion" to "re",
        "reunion ile" to "re",
        "mayotte" to "yt",
        "polynesie francaise" to "pf",
        "nouvelle caledonie" to "nc",
        "royaume uni" to "gb",
        "irlande" to "ie",
        "norvege" to "no",
        "islande" to "is",
        "belgique" to "be",
        "luxembourg" to "lu",
        "pologne" to "pl",
        "republique tcheque" to "cz",
        "tchequie" to "cz",
        "slovaquie" to "sk",
        "hongrie" to "hu",
        "roumanie" to "ro",
        "serbie" to "rs",
        "bosnie herzegovine" to "ba",
        "albanie" to "al",
        "kosovo" to "xk",
        "chypre" to "cy",
        "malte" to "mt",
        "monaco" to "mc",
        "liechtenstein" to "li",
        "georgie" to "ge",
        "armenie" to "am",
        "algerie" to "dz",
        "tunisie" to "tn",
        "egypte" to "eg",
        "afrique du sud" to "za",
        "namibie" to "na",
        "kenya" to "ke",
        "tanzanie" to "tz",
        "liban" to "lb",
        "israel" to "il",
        "iran" to "ir",
        "inde" to "in",
        "sri lanka" to "lk",
        "japon" to "jp",
        "coree du sud" to "kr",
        "taiwan" to "tw",
        "philippines" to "ph",
        "thailande" to "th",
        "vietnam" to "vn",
        "malaisie" to "my",
        "indonesie" to "id",
        "mongolie" to "mn",
        "kirghizistan" to "kg",
        "kazakhstan" to "kz",
        "ouzbekistan" to "uz",
        "tadjikistan" to "tj",
        "jamaique" to "jm",
        "sainte lucie" to "lc",
        "saint vincent et les grenadines" to "vc",
        "grenade" to "gd",
        "trinite et tobago" to "tt",
        "belize" to "bz",
        "honduras" to "hn",
        "salvador" to "sv",
        "nicaragua" to "ni",
        "panama" to "pa",
        "paraguay" to "py",
        "uruguay" to "uy",
    )
}
