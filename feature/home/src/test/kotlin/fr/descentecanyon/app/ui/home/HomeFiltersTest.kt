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
