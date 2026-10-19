package fr.descentecanyon.app.ui.design

import fr.descentecanyon.app.domain.model.ZoneCountry
import fr.descentecanyon.app.domain.model.ZoneSubdivision
import org.junit.Assert.assertEquals
import org.junit.Test

class DcZonePickerTest {

    private val zones = listOf(
        ZoneCountry("Espagne", 5, listOf(ZoneSubdivision("Huesca", 5))),
        ZoneCountry("France", 22, listOf(ZoneSubdivision("Ain", 2), ZoneSubdivision("Alpes-Maritimes", 19), ZoneSubdivision("Ariège", 1))),
    )

    @Test
    fun `the area picker lists the countries closed under every area`() {
        assertEquals(listOf("Toutes 27 selected", "Espagne 5 closed", "France 22 closed"), entries().describe())
    }

    @Test
    fun `an open country shows the whole country then its subdivisions with the selection`() {
        val entries = entries(country = "France", subdivision = "Ain", expanded = setOf("France"))

        assertEquals(
            listOf(
                "Toutes 27",
                "Espagne 5 closed",
                "France 22 open",
                "  Tout 22",
                "  Ain 2 selected",
                "  Alpes-Maritimes 19",
                "  Ariège 1",
            ),
            entries.describe(),
        )
        assertEquals(ZonePick.Country("France"), (entries[3] as DcPickerOption).value)
        assertEquals(ZonePick.Subdivision("France", "Ain"), (entries[4] as DcPickerOption).value)
    }

    @Test
    fun `a closed country holding the selection is highlighted`() {
        assertEquals(
            listOf("Toutes 27", "Espagne 5 closed", "France 22 closed selected"),
            entries(country = "France", subdivision = "Ain").describe(),
        )
    }

    @Test
    fun `searching opens the countries with a match and ignores accents and dashes`() {
        assertEquals(listOf("France 22 open", "  Alpes-Maritimes 19"), entries(query = "alpes mar").describe())
        assertEquals(listOf("France 22 open", "  Ariège 1"), entries(query = "ARIEGE").describe())
        assertEquals(listOf("Espagne 5 open", "  Tout 5"), entries(query = "espa").describe())
        assertEquals(emptyList<String>(), entries(query = "Valais").describe())
    }

    @Test
    fun `without a total the every area line has no count`() {
        assertEquals("Toutes selected", zonePickerEntries(zones, null, null, null, emptySet(), "", "Toutes", "Tout").describe().first())
    }

    private fun entries(country: String? = null, subdivision: String? = null, expanded: Set<String> = emptySet(), query: String = "") =
        zonePickerEntries(
            zones = zones,
            totalCount = 27,
            selectedCountry = country,
            selectedSubdivision = subdivision,
            expandedCountries = expanded,
            query = query,
            everywhereLabel = "Toutes",
            wholeCountryLabel = "Tout",
        )
}

/** One line per entry: "# heading", or the label, count, open/closed and selection, nested ones indented. */
internal fun List<DcPickerEntry<*>>.describe(): List<String> = map { entry ->
    when (entry) {
        is DcPickerHeading -> "# ${entry.title}"

        is DcPickerOption -> buildString {
            if (entry.isNested) append("  ")
            append(entry.label)
            entry.count?.let { append(" $it") }
            when (entry.isExpanded) {
                true -> append(" open")
                false -> append(" closed")
                null -> Unit
            }
            if (entry.isSelected) append(" selected")
        }
    }
}
