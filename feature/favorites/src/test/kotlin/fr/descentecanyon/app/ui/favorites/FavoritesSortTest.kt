package fr.descentecanyon.app.ui.favorites

import fr.descentecanyon.app.domain.model.CanyonSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesSortTest {

    private val favorites = listOf(
        summary(1, "Écouges", null),
        summary(2, "Bollinette", LocalDate.of(2026, 7, 1)),
        summary(3, "aiguebrun", LocalDate.of(2026, 8, 1)),
    )

    @Test
    fun `favorites keep their order, or sort by name or by freshest report with unknown last`() {
        assertEquals(listOf(1, 2, 3), favorites.sortedFor(FavoritesSort.ADDED).map { it.id })
        assertEquals(listOf(3, 2, 1), favorites.sortedFor(FavoritesSort.NAME).map { it.id })
        assertEquals(listOf(3, 2, 1), favorites.sortedFor(FavoritesSort.LATEST_REPORT).map { it.id })
    }

    private fun summary(id: Int, name: String, reportDate: LocalDate?) =
        CanyonSummary(id = id, nom = name, pays = "France", cotation = "v3a3II", url = "", dernierDebitDate = reportDate)
}
