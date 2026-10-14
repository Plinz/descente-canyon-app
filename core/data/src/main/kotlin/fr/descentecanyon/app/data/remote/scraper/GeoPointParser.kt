package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedGeoPoint
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parses geolocated points from the canyon map page.
 * Points are embedded in JavaScript (Google Maps API calls), not in DOM elements.
 */
internal object GeoPointParser {

    private val ICONS_BLOCK_REGEX = Regex(
        """var\s+icons\s*=\s*\{(.*?)};var\s+markerBounds""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val ICON_ENTRY_SPLIT_REGEX = Regex("""}\s*,\s*(?=[a-z_]+\s*:)""")

    private val ICON_KEY_REGEX = Regex("""^\s*([a-z_]+)\s*:""")
    private val ICON_TITLE_REGEX = Regex("""title:\s*'((?:\\'|[^'])*)'""")

    private val POINT_BLOCK_REGEX = Regex(
        """var\s+point\s*=\s*\{(.*?)};addMarker\(point\);""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val LAT_LNG_REGEX = Regex("""LatLng\((-?[\d.]+),(-?[\d.]+)\)""")
    private val TYPE_REGEX = Regex("""type:\s*'([a-z_]+)'""")
    private val REMARK_REGEX = Regex(
        """remarque:\s*'(.*?)',\s*auteur:""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    // Map site type names to our GeoPointType enum names
    private val TYPE_MAP = mapOf(
        "parking" to "PARKING_AVAL",
        "parking_aval" to "PARKING_AVAL",
        "parking_amont" to "PARKING_AMONT",
        "depart" to "ENTREE",
        "arrivee" to "SORTIE",
        "point_externe" to "POINT_REMARQUABLE",
        "point_interne" to "POINT_REMARQUABLE",
    )

    fun parse(doc: Document): List<ScrapedGeoPoint> {
        val scriptElement = doc.selectFirst("script:containsData(initMap)")
            ?: return emptyList()

        val jsCode = scriptElement.data()
        val iconsBlock = ICONS_BLOCK_REGEX.find(jsCode)?.groupValues?.get(1).orEmpty()
        val titlesByRawType = parseIconTitles(iconsBlock)
        val results = mutableListOf<ScrapedGeoPoint>()

        for (pointMatch in POINT_BLOCK_REGEX.findAll(jsCode)) {
            val pointBlock = pointMatch.groupValues[1]
            val latLngMatch = LAT_LNG_REGEX.find(pointBlock) ?: continue
            val typeMatch = TYPE_REGEX.find(pointBlock) ?: continue

            val lat = latLngMatch.groupValues[1].toDoubleOrNull() ?: continue
            val lng = latLngMatch.groupValues[2].toDoubleOrNull() ?: continue
            val rawType = typeMatch.groupValues[1]
            val remark = REMARK_REGEX.find(pointBlock)
                ?.groupValues
                ?.get(1)
                ?.let(::decodeJsString)
                ?.ifBlank { null }

            results.add(
                ScrapedGeoPoint(
                    type = TYPE_MAP[rawType] ?: "UNKNOWN",
                    latitude = lat,
                    longitude = lng,
                    title = titlesByRawType[rawType]?.ifBlank { null },
                    remark = remark,
                ),
            )
        }

        return results
    }

    private fun parseIconTitles(iconsBlock: String): Map<String, String> {
        if (iconsBlock.isBlank()) return emptyMap()

        return ICON_ENTRY_SPLIT_REGEX.split(iconsBlock)
            .mapNotNull { entry ->
                val key = ICON_KEY_REGEX.find(entry)?.groupValues?.get(1) ?: return@mapNotNull null
                val title = ICON_TITLE_REGEX.find(entry)?.groupValues?.get(1) ?: return@mapNotNull null
                key to decodeJsString(title)
            }
            .toMap()
    }

    private fun decodeJsString(value: String): String = Jsoup.parse(
        value
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", ""),
    ).text().trim()
}
