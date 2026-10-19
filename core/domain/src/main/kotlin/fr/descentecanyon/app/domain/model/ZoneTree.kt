package fr.descentecanyon.app.domain.model

import java.text.Collator
import java.util.Locale

/** A country offered by an area filter, with how many items lie there, and its subdivisions. */
data class ZoneCountry(val name: String, val count: Int, val subdivisions: List<ZoneSubdivision>)

data class ZoneSubdivision(val name: String, val count: Int)

/**
 * The countries and subdivisions of [items], in alphabetical order (accents ignored, as the names
 * come from the French site). An item counts in every country and subdivision it lies in, since
 * some canyons are on a border; pass an item several times to count it several times.
 */
fun buildZoneTree(items: Iterable<CanyonSearchItem>): List<ZoneCountry> {
    val countryCounts = mutableMapOf<String, Int>()
    val subdivisionCounts = mutableMapOf<String, MutableMap<String, Int>>()
    items.forEach { item ->
        item.countryTokens.distinct().forEach { country ->
            countryCounts.merge(country, 1, Int::plus)
            val counts = subdivisionCounts.getOrPut(country) { mutableMapOf() }
            item.subdivisionsFor(country).distinct().forEach { subdivision -> counts.merge(subdivision, 1, Int::plus) }
        }
    }
    val collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }
    return countryCounts.entries
        .sortedWith(compareBy(collator) { it.key })
        .map { (country, count) ->
            ZoneCountry(
                name = country,
                count = count,
                subdivisions = subdivisionCounts[country].orEmpty().entries
                    .sortedWith(compareBy(collator) { it.key })
                    .map { (subdivision, subdivisionCount) -> ZoneSubdivision(subdivision, subdivisionCount) },
            )
        }
}
