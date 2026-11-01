package fr.descentecanyon.app.domain.model

import java.time.LocalDate
import java.time.Month
import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonCheckTest {

    private fun reports(vararg perMonth: Pair<Month, Int>): List<Debit> = perMonth.flatMap { (month, count) ->
        List(count) { day -> Debit(canyonId = 1, date = LocalDate.of(2020 + day % 5, month, 1 + day % 28), niveau = NiveauDebit.CORRECT) }
    }

    private val summerCanyon = reports(
        Month.MAY to 4,
        Month.JUNE to 20,
        Month.JULY to 40,
        Month.AUGUST to 45,
        Month.SEPTEMBER to 15,
        Month.OCTOBER to 2,
        Month.NOVEMBER to 1,
    )

    @Test
    fun `a month with few reports is off season, with the usual months`() {
        assertEquals(
            SeasonCheck.OffSeason(Month.NOVEMBER, 1, 127, Month.JUNE, Month.SEPTEMBER),
            SeasonCheck.of(summerCanyon, LocalDate.of(2026, 11, 1)),
        )
        assertEquals(
            SeasonCheck.OffSeason(Month.FEBRUARY, 0, 127, Month.JUNE, Month.SEPTEMBER),
            SeasonCheck.of(summerCanyon, LocalDate.of(2027, 2, 14)),
        )
    }

    @Test
    fun `a month of the season is in season`() {
        assertEquals(SeasonCheck.InSeason, SeasonCheck.of(summerCanyon, LocalDate.of(2026, 7, 14)))
        // 4 of 127 reports: still a month people go.
        assertEquals(SeasonCheck.InSeason, SeasonCheck.of(summerCanyon, LocalDate.of(2026, 5, 20)))
    }

    @Test
    fun `a southern summer runs over the new year`() {
        val southern = reports(Month.DECEMBER to 20, Month.JANUARY to 30, Month.FEBRUARY to 25, Month.MARCH to 10, Month.APRIL to 1)
        assertEquals(
            SeasonCheck.OffSeason(Month.JULY, 0, 86, Month.DECEMBER, Month.MARCH),
            SeasonCheck.of(southern, LocalDate.of(2026, 7, 1)),
        )
    }

    @Test
    fun `a flow only looked at is not a descent`() {
        // Glacier-fed: looked at in July (too much water), descended from August to February.
        val glacier = reports(Month.AUGUST to 20, Month.SEPTEMBER to 40, Month.OCTOBER to 30, Month.JANUARY to 10) +
            List(8) { Debit(canyonId = 1, date = LocalDate.of(2024, 7, 1 + it), niveau = NiveauDebit.TRES_GROS, isDescended = false) }
        assertEquals(
            SeasonCheck.OffSeason(Month.JULY, 0, 100, Month.AUGUST, Month.OCTOBER),
            SeasonCheck.of(glacier, LocalDate.of(2026, 7, 10)),
        )
    }

    @Test
    fun `too few reports tell no season`() {
        assertEquals(SeasonCheck.Unknown, SeasonCheck.of(reports(Month.JULY to 12), LocalDate.of(2026, 11, 1)))
        assertEquals(SeasonCheck.Unknown, SeasonCheck.of(emptyList(), LocalDate.of(2026, 11, 1)))
    }
}
