package fr.descentecanyon.app.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebitPredictionRangeTest {

    @Test
    fun `a confident prediction is shown as a single level`() {
        assertNull(prediction(NiveauDebit.CORRECT, sec = 0.02, filet = 0.15, correct = 0.7, gros = 0.13).likelyRange())
    }

    @Test
    fun `an uncertain prediction gives the predicted level and its most likely neighbour`() {
        assertEquals(
            DebitLevelRange(NiveauDebit.CORRECT, NiveauDebit.GROS),
            prediction(NiveauDebit.CORRECT, filet = 0.1, correct = 0.5, gros = 0.4).likelyRange(),
        )
        assertEquals(
            DebitLevelRange(NiveauDebit.FILET, NiveauDebit.CORRECT),
            prediction(NiveauDebit.CORRECT, filet = 0.45, correct = 0.5, gros = 0.05).likelyRange(),
        )
    }

    @Test
    fun `the extreme levels only have one neighbour`() {
        assertEquals(
            DebitLevelRange(NiveauDebit.SEC, NiveauDebit.FILET),
            prediction(NiveauDebit.SEC, sec = 0.55, filet = 0.45).likelyRange(),
        )
        assertEquals(
            DebitLevelRange(NiveauDebit.TRES_GROS, NiveauDebit.CRUE),
            prediction(NiveauDebit.CRUE, tresGros = 0.42, crue = 0.58).likelyRange(),
        )
    }

    @Test
    fun `no range without the level distribution`() {
        assertNull(prediction(NiveauDebit.CORRECT).copy(levelProbabilities = emptyMap()).likelyRange())
    }

    @Test
    fun `confidence is the probability of the shown level`() {
        val prediction = prediction(NiveauDebit.SEC, sec = 0.93, filet = 0.06, correct = 0.01)

        assertEquals(0.93, prediction.shownProbability()!!, 1e-9)
        assertEquals(PredictionConfidence.HIGH, prediction.confidence())
    }

    @Test
    fun `a range between two levels can be shown with high confidence`() {
        val prediction = prediction(NiveauDebit.FILET, sec = 0.04, filet = 0.48, correct = 0.45, gros = 0.03)

        assertEquals(0.93, prediction.shownProbability()!!, 1e-9)
        assertEquals(PredictionConfidence.HIGH, prediction.confidence())
    }

    @Test
    fun `confidence drops when the shown answer is less likely`() {
        assertEquals(
            PredictionConfidence.MEDIUM,
            prediction(NiveauDebit.CORRECT, filet = 0.1, correct = 0.75, gros = 0.15).confidence(),
        )
        assertEquals(
            PredictionConfidence.LOW,
            prediction(NiveauDebit.GROS, filet = 0.2, correct = 0.38, gros = 0.22, tresGros = 0.12, crue = 0.08).confidence(),
        )
    }

    @Test
    fun `no confidence without the level distribution`() {
        assertNull(prediction(NiveauDebit.CORRECT).copy(levelProbabilities = emptyMap()).confidence())
    }

    private fun prediction(
        level: NiveauDebit,
        sec: Double = 0.0,
        filet: Double = 0.0,
        correct: Double = 0.0,
        gros: Double = 0.0,
        tresGros: Double = 0.0,
        crue: Double = 0.0,
    ) = DailyDebitPrediction(
        date = LocalDate.of(2026, 7, 14),
        horizonDays = 0,
        level = PredictedDebitLevel.MEDIUM,
        probabilities = emptyMap(),
        highThreshold = 0.3,
        ordinalLevel = level,
        levelProbabilities = mapOf(
            NiveauDebit.SEC to sec,
            NiveauDebit.FILET to filet,
            NiveauDebit.CORRECT to correct,
            NiveauDebit.GROS to gros,
            NiveauDebit.TRES_GROS to tresGros,
            NiveauDebit.CRUE to crue,
        ),
    )
}
