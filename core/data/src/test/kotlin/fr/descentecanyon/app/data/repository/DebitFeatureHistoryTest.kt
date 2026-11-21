package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.DailyWeatherSource
import fr.descentecanyon.app.domain.model.DailyWeatherValue
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.RecentDebitCell
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The canyon's own reports as the model gets them: completed by the day grid, lagged for J+1 / J+2. */
class DebitFeatureHistoryTest {

    private val builder = DebitFeatureBuilder()
    private val today = LocalDate.of(2026, 11, 21)

    @Test
    fun `the day grid completes the saved debit list on the days it does not have`() {
        val saved = listOf(day(-30) to 2, day(-3) to 1)
        val cells = listOf(
            RecentDebitCell(canyonId = 7, date = today.minusDays(3), rank = 4), // already in the list: the list wins
            RecentDebitCell(canyonId = 7, date = today.minusDays(1), rank = 3),
            RecentDebitCell(canyonId = 7, date = today.minusDays(1), rank = 2), // the lowest of the day, as the site grid
            RecentDebitCell(canyonId = 8, date = today.minusDays(2), rank = 5), // another canyon
        )

        val history = DebitFeatureBuilder.withGridCells(saved, cells, canyonId = 7)

        assertEquals(listOf(day(-30) to 2, day(-3) to 1, day(-1) to 2), history)
    }

    @Test
    fun `for tomorrow and after, today's reports are not known yet`() {
        // Reported yesterday (GROS) and today (CORRECT): J0 sees yesterday's, J+1 only up to yesterday too.
        val history = listOf(day(-1) to 3, day(0) to 2, day(-6) to 1)

        val todayValues = builder.computeFeatureValues(input(today, history, horizonDays = 0))!!
        val tomorrowValues = builder.computeFeatureValues(input(today.plusDays(1), history, horizonDays = 1))!!
        val afterValues = builder.computeFeatureValues(input(today.plusDays(2), history, horizonDays = 2))!!

        assertEquals(1.0, todayValues["histDaysSinceLast"]!!, 0.0)
        assertEquals(3.0, todayValues["histLastRank"]!!, 0.0)
        // J+1: today's CORRECT is left out, yesterday's GROS is 2 days before the target day.
        assertEquals(2.0, tomorrowValues["histDaysSinceLast"]!!, 0.0)
        assertEquals(3.0, tomorrowValues["histLastRank"]!!, 0.0)
        assertEquals(2.0, tomorrowValues["histCount"]!!, 0.0)
        // J+2: the same reports, 3 days before.
        assertEquals(3.0, afterValues["histDaysSinceLast"]!!, 0.0)
        assertEquals(2.0, afterValues["histCount"]!!, 0.0)
        // What changed since the last report is measured from yesterday's report, not today's.
        val withToday = builder.computeFeatureValues(input(today.plusDays(1), history, horizonDays = 0))!!
        assertNotEquals(withToday["hLiquidSinceLast"], tomorrowValues["hLiquidSinceLast"])
    }

    @Test
    fun `a canyon without report has no last report`() {
        val values = builder.computeFeatureValues(input(today, emptyList(), horizonDays = 0))!!
        assertNull(values["histLastRank"])
        assertEquals(0.0, values["histCount"]!!, 0.0)
    }

    @Test
    fun `the basis shown with the estimate reads the feature values`() {
        val values = builder.computeFeatureValues(input(today, listOf(day(-4) to 2, day(-10) to 3), horizonDays = 0))!!

        val basis = DebitPredictionRepositoryImpl.basisOf(values, today)

        assertEquals(today.minusDays(4), basis.lastReportDate)
        assertEquals(NiveauDebit.CORRECT, basis.lastReportLevel)
        assertEquals(2, basis.reportsLast30Days)
        assertEquals(3.0 * 2.0, basis.rainLast3DaysMm!!, 1e-9)
        assertEquals(2.0, basis.rainOnDayMm!!, 1e-9)
        assertEquals(0, basis.rainGaugeCount)
        assertNull(basis.yesterdayGaugeRainMm)
    }

    private fun day(offset: Long): Long = today.plusDays(offset).toEpochDay()

    /** Two rainy millimetres a day at 10 °C for the whole window, a canyon at 1 000 m. */
    private fun input(target: LocalDate, history: List<Pair<Long, Int>>, horizonDays: Int) = DebitFeatureBuilder.DebitFeatureInput(
        targetDate = target,
        weather = (0 until DebitHydroModel.WINDOW_DAYS + 5).map { offset ->
            DailyWeatherValue(
                date = target.minusDays(offset.toLong()),
                precipitationSum = 2.0,
                temperature2mMean = 10.0,
                source = DailyWeatherSource.ARCHIVE,
            )
        },
        gridLatitude = 45.0,
        gridLongitude = 6.0,
        gridElevation = 1000.0,
        staticValues = emptyMap(),
        altitudeDepart = 1000.0,
        denivele = 300.0,
        longueur = 1500.0,
        cascadeMax = 20.0,
        history = history,
        massif = DebitFeatureBuilder.MonthlyAggregate(),
        region = DebitFeatureBuilder.MonthlyAggregate(),
        global = DebitFeatureBuilder.MonthlyAggregate(),
        horizonDays = horizonDays,
    )
}
