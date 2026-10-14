package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeDebitPredictionRepository @Inject constructor() : DebitPredictionRepository {
    override suspend fun getPredictions(detail: CanyonDetail): Result<CanyonDebitPredictions> = Result.success(
        CanyonDebitPredictions(
            target = WeatherTarget(
                latitude = 0.0,
                longitude = 0.0,
                source = WeatherLocationSource.UNKNOWN,
            ),
            timezone = "UTC",
            fetchedAt = Instant.EPOCH,
            lookupSource = RuntimeLookupSource.GLOBAL,
            usedWeatherCache = false,
            policy = DebitPredictionPolicy.BALANCED,
            predictions = listOf(
                DailyDebitPrediction(
                    date = LocalDate.of(2026, 1, 1),
                    horizonDays = 0,
                    level = PredictedDebitLevel.MEDIUM,
                    probabilities = mapOf(
                        PredictedDebitLevel.LOW to 0.2,
                        PredictedDebitLevel.MEDIUM to 0.6,
                        PredictedDebitLevel.HIGH to 0.2,
                    ),
                    highThreshold = 2.5,
                    ordinalScore = 2.0,
                    ordinalLevel = NiveauDebit.CORRECT,
                ),
            ),
        ),
    )

    override suspend fun getModelSummary(): Result<DebitPredictionInfoSummary> = Result.success(
        DebitPredictionInfoSummary(
            modelName = "e2e",
            targetMode = "ordinal",
            featureCount = 0,
            trainRowCount = 0,
            calibrationRowCount = 0,
            testRowCount = 0,
            canyonCount = 0,
            regionCount = 0,
            massifCount = 0,
            topDrivers = emptyList(),
        ),
    )
}
