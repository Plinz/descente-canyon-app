package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanyonSheetTest {

    private val complete = CanyonSheet(
        nom = "Ravin des Essais",
        pays = "France",
        descente = "Une C30 puis des toboggans.",
        points = listOf(SheetPoint(GeoPointType.ENTREE, 45.1, 5.7), SheetPoint(GeoPointType.SORTIE, 45.105, 5.71)),
    )

    @Test
    fun `a sheet needs a name, one point of any type and one text`() {
        assertEquals(emptySet<SheetProblem>(), complete.problems())
        assertEquals(setOf(SheetProblem.NAME, SheetProblem.POINT, SheetProblem.DESCRIPTION), CanyonSheet().problems())
        val parking = SheetPoint(GeoPointType.PARKING_AVAL, 45.1, 5.7)
        assertEquals(emptySet<SheetProblem>(), CanyonSheet(nom = "Ravin", remarques = "Vu du pont.", points = listOf(parking)).problems())
    }

    @Test
    fun `the entry places the canyon, else its first point`() {
        val parking = SheetPoint(GeoPointType.PARKING_AVAL, 45.1, 5.7)
        val entry = SheetPoint(GeoPointType.ENTREE, 45.2, 5.7)
        assertEquals(parking, CanyonSheet(points = listOf(parking)).anchor)
        assertEquals(entry, CanyonSheet(points = listOf(parking, entry)).anchor)
        assertEquals(null, CanyonSheet().anchor)
    }

    @Test
    fun `durations read like the site's, within its ten characters`() {
        listOf("", "45min", "1h30", "20 à 45min", "néant", "2-3h").forEach { assertTrue(it, CanyonSheet.isDuration(it)) }
        listOf("longue", "1h15 à 1h45", "variable").forEach { assertFalse(it, CanyonSheet.isDuration(it)) }
        assertEquals(setOf(SheetProblem.DURATION), complete.copy(tempsRetour = "longue").problems())
    }

    @Test
    fun `the shuttle is typed in km and kept in the site's format`() {
        assertEquals("5.0km", CanyonSheet.shuttleOf("5"))
        assertEquals("2.5km", CanyonSheet.shuttleOf("2,5"))
        assertEquals(CanyonSheet.NO_SHUTTLE, CanyonSheet.shuttleOf("0"))
        assertEquals("", CanyonSheet.shuttleOf(""))
        assertEquals("5", CanyonSheet.kilometresOf("5.0km"))
        assertEquals("2.5", CanyonSheet.kilometresOf("2.5km"))
        assertEquals("0", CanyonSheet.kilometresOf("néant"))
        assertEquals("", CanyonSheet.kilometresOf("??"))
        assertEquals(setOf(SheetProblem.SHUTTLE), complete.copy(navette = "loin").problems())
    }

    @Test
    fun `the rating is written like the site's`() {
        assertEquals("v4a3III", CanyonSheet.cotationText(CotationRating.parse("v4a3III")))
        assertEquals("", CanyonSheet.cotationText(CotationRating.parse("??")))
        assertEquals("", CanyonSheet.cotationText(null))
    }

    @Test
    fun `points of two canyons and impossible numbers are caught`() {
        val far = complete.copy(points = complete.points + SheetPoint(GeoPointType.PARKING_AVAL, 45.5, 5.7))
        assertEquals(setOf(SheetProblem.POINTS_TOO_FAR_APART), far.problems())
        assertEquals(setOf(SheetProblem.NUMBER), complete.copy(cordeMin = 5_000).problems())
    }

    @Test
    fun `canyons added in the app are told by their id`() {
        assertTrue(AppCanyons.isAppCanyon(1_000_000))
        assertFalse(AppCanyons.isAppCanyon(24_212))
        val siteCanyon = Canyon(id = 21, nom = "Mouna", nomComplet = "Mouna", pays = "France", commune = "", cotation = "", url = "")
        assertFalse(siteCanyon.isAddedInApp)
        assertTrue(siteCanyon.copy(sourceType = CanyonSourceType.APP).isAddedInApp)
    }
}
