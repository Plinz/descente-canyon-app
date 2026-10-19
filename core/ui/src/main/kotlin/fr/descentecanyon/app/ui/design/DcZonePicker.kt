package fr.descentecanyon.app.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.ZoneCountry
import java.text.Normalizer

/**
 * Picks an area among [zones]: each country opens on "whole country" and its subdivisions.
 * [onZoneSelected] gets a null country for "all areas"; [totalCount] is shown on that line.
 */
@Composable
fun DcZonePickerSheet(
    zones: List<ZoneCountry>,
    selectedCountry: String?,
    selectedSubdivision: String?,
    subtitle: String,
    onZoneSelected: (country: String?, subdivision: String?) -> Unit,
    onDismiss: () -> Unit,
    totalCount: Int? = null,
) {
    var query by remember { mutableStateOf("") }
    // The chosen country is open, and the only one when there is a single country.
    var expandedCountries by remember { mutableStateOf(setOfNotNull(selectedCountry, zones.singleOrNull()?.name)) }
    val entries = zonePickerEntries(
        zones = zones,
        totalCount = totalCount,
        selectedCountry = selectedCountry,
        selectedSubdivision = selectedSubdivision,
        expandedCountries = expandedCountries,
        query = query,
        everywhereLabel = stringResource(R.string.zone_filter_all),
        wholeCountryLabel = stringResource(R.string.zone_filter_whole_country),
    )
    DcPickerSheet(
        title = stringResource(R.string.zone_filter_title),
        subtitle = subtitle,
        entries = entries,
        onPick = { pick ->
            when (pick) {
                ZonePick.Everywhere -> onZoneSelected(null, null)
                is ZonePick.Country -> onZoneSelected(pick.country, null)
                is ZonePick.Subdivision -> onZoneSelected(pick.country, pick.subdivision)
                is ZonePick.Toggle -> Unit
            }
        },
        onToggle = { pick ->
            if (pick is ZonePick.Toggle) {
                expandedCountries =
                    if (pick.country in expandedCountries) expandedCountries - pick.country else expandedCountries + pick.country
            }
        },
        onDismiss = onDismiss,
        query = query,
        onQueryChange = { query = it },
        searchPlaceholder = stringResource(R.string.zone_filter_search),
        emptyText = stringResource(R.string.search_filter_option_no_results).takeIf { entries.isEmpty() },
    )
}

/** The value of an area field: "Isère, France", "France" or "All areas". */
@Composable
fun zoneFieldValue(country: String?, subdivision: String?): String = when {
    country == null -> stringResource(R.string.zone_filter_all)
    subdivision != null -> "$subdivision, $country"
    else -> country
}

/** What a line of the area picker does. */
sealed interface ZonePick {
    data object Everywhere : ZonePick

    data class Toggle(val country: String) : ZonePick

    data class Country(val country: String) : ZonePick

    data class Subdivision(val country: String, val subdivision: String) : ZonePick
}

/**
 * Countries are groups that open on their subdivisions, under a "whole country" line. While
 * searching, every country with a match is open and shows only the matching lines.
 */
fun zonePickerEntries(
    zones: List<ZoneCountry>,
    totalCount: Int?,
    selectedCountry: String?,
    selectedSubdivision: String?,
    expandedCountries: Set<String>,
    query: String,
    everywhereLabel: String,
    wholeCountryLabel: String,
): List<DcPickerEntry<ZonePick>> {
    val searching = query.isNotBlank()
    return buildList {
        if (!searching) {
            add(
                DcPickerOption(
                    key = "all",
                    value = ZonePick.Everywhere,
                    label = everywhereLabel,
                    count = totalCount,
                    isSelected =
                        selectedCountry == null,
                ),
            )
        }
        zones.forEach { country ->
            val countryMatches = !searching || country.name.matchesPickerQuery(query)
            val subdivisions = if (searching) country.subdivisions.filter { it.name.matchesPickerQuery(query) } else country.subdivisions
            if (!countryMatches && subdivisions.isEmpty()) return@forEach
            val isSelectedCountry = selectedCountry.equals(country.name, ignoreCase = true)
            val isExpanded = searching || country.name in expandedCountries
            add(
                DcPickerOption(
                    key = "country:${country.name}",
                    value = ZonePick.Toggle(country.name),
                    label = country.name,
                    count = country.count,
                    isSelected = isSelectedCountry && !isExpanded,
                    isExpanded = isExpanded,
                ),
            )
            if (!isExpanded) return@forEach
            if (countryMatches) {
                add(
                    DcPickerOption(
                        key = "country-all:${country.name}",
                        value = ZonePick.Country(country.name),
                        label = wholeCountryLabel,
                        count = country.count,
                        isSelected = isSelectedCountry && selectedSubdivision == null,
                        isNested = true,
                    ),
                )
            }
            subdivisions.forEach { subdivision ->
                add(
                    DcPickerOption(
                        key = "subdivision:${country.name}/${subdivision.name}",
                        value = ZonePick.Subdivision(country.name, subdivision.name),
                        label = subdivision.name,
                        count = subdivision.count,
                        isSelected = isSelectedCountry && subdivision.name.equals(selectedSubdivision, ignoreCase = true),
                        isNested = true,
                    ),
                )
            }
        }
    }
}

internal fun String.matchesPickerQuery(query: String): Boolean = normalizedPickerText().contains(query.normalizedPickerText())

/** "alpes mar" finds "Alpes-Maritimes", "ariege" finds "Ariège". */
private fun String.normalizedPickerText(): String = Normalizer
    .normalize(this, Normalizer.Form.NFD)
    .replace(DiacriticsRegex, "")
    .lowercase()
    .replace(SeparatorsRegex, " ")
    .trim()

private val DiacriticsRegex = Regex("\\p{Mn}+")
private val SeparatorsRegex = Regex("[^\\p{L}\\p{N}]+")
