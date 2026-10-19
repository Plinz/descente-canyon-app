package fr.descentecanyon.app.ui.home

import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.ui.design.DcPickerEntry
import fr.descentecanyon.app.ui.design.DcPickerHeading
import fr.descentecanyon.app.ui.design.DcPickerOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeFiltersTest {

    private val zones = HomeDebitGeoFilterState(
        countries = listOf(
            HomeZoneCountry("Espagne", 5, listOf(HomeZoneSubdivision("Huesca", 5))),
            HomeZoneCountry(
                "France",
                22,
                listOf(HomeZoneSubdivision("Ain", 2), HomeZoneSubdivision("Alpes-Maritimes", 19), HomeZoneSubdivision("Ariège", 1)),
            ),
        ),
        totalCount = 27,
    )

    @Test
    fun `the area picker lists the countries closed under every area`() {
        val entries =
            zonePickerEntries(zones, expandedCountries = emptySet(), query = "", everywhereLabel = "Toutes", wholeCountryLabel = "Tout")

        assertEquals(listOf("Toutes 27 selected", "Espagne 5 closed", "France 22 closed"), entries.describe())
    }

    @Test
    fun `an open country shows the whole country then its subdivisions with the selection`() {
        val filter = zones.copy(selectedCountry = "France", selectedDepartment = "Ain")

        val entries =
            zonePickerEntries(
                filter,
                expandedCountries = setOf("France"),
                query = "",
                everywhereLabel = "Toutes",
                wholeCountryLabel = "Tout",
            )

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
        assertEquals(ZonePick.Subdivision("France", "Ain"), (entries[4] as DcPickerOption).value)
        assertEquals(ZonePick.Country("France"), (entries[3] as DcPickerOption).value)
    }

    @Test
    fun `a closed country holding the selection is highlighted`() {
        val filter = zones.copy(selectedCountry = "France", selectedDepartment = "Ain")

        val entries =
            zonePickerEntries(filter, expandedCountries = emptySet(), query = "", everywhereLabel = "Toutes", wholeCountryLabel = "Tout")

        assertEquals(listOf("Toutes 27", "Espagne 5 closed", "France 22 closed selected"), entries.describe())
    }

    @Test
    fun `searching opens the countries with a match and ignores accents and dashes`() {
        assertEquals(
            listOf("France 22 open", "  Alpes-Maritimes 19"),
            zonePickerEntries(zones, emptySet(), query = "alpes mar", everywhereLabel = "Toutes", wholeCountryLabel = "Tout").describe(),
        )
        assertEquals(
            listOf("France 22 open", "  Ariège 1"),
            zonePickerEntries(zones, emptySet(), query = "ARIEGE", everywhereLabel = "Toutes", wholeCountryLabel = "Tout").describe(),
        )
        assertEquals(
            listOf("Espagne 5 open", "  Tout 5"),
            zonePickerEntries(zones, emptySet(), query = "espa", everywhereLabel = "Toutes", wholeCountryLabel = "Tout").describe(),
        )
        assertEquals(
            emptyList<String>(),
            zonePickerEntries(zones, emptySet(), query = "Valais", everywhereLabel = "Toutes", wholeCountryLabel = "Tout").describe(),
        )
    }

    @Test
    fun `the forum picker keeps the forum index order under its headings`() {
        val materiel = category(3, "Matériel", "Les forums généralistes")
        val annonces = category(21, "Petites annonces", "Les forums généralistes")
        val alpesSud = category(6, "FR - Alpes du Sud", "Régions & canyons")
        val suisse = category(16, "SUISSE", "Régions & canyons")
        val filter = HomeForumFilterState(
            selectedCategoryKey = annonces.key(),
            availableCategories = listOf(materiel, annonces, alpesSud, suisse),
            topicCounts = mapOf(annonces.key() to 5, suisse.key() to 1),
            totalTopicCount = 12,
        )

        val entries = forumPickerEntries(filter, followedCategoryKeys = setOf(suisse.key()), allLabel = "Toutes", followedLabel = "Suivie")

        assertEquals(
            listOf(
                "Toutes 12",
                "# Les forums généralistes",
                "Matériel",
                "Petites annonces 5 selected",
                "# Régions & canyons",
                "FR - Alpes du Sud",
                "SUISSE 1",
            ),
            entries.describe(),
        )
        assertNotNull((entries.last() as DcPickerOption).badge)
        assertNull((entries[2] as DcPickerOption).badge)
        assertEquals(null, (entries.first() as DcPickerOption).value)
    }

    private fun category(id: Int, name: String, section: String) = ForumCategory(
        forumId = id,
        forumName = name,
        forumUrl = "https://www.descente-canyon.com/forums/viewforum.php?f=$id",
        section = section,
    )

    private fun List<DcPickerEntry<*>>.describe(): List<String> = map { entry ->
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
}
