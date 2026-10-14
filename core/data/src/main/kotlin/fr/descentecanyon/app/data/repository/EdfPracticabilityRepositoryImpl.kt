package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.network.retryOnConnectivityFailure
import fr.descentecanyon.app.data.remote.edf.EdfDataPointDto
import fr.descentecanyon.app.data.remote.edf.EdfLimitDto
import fr.descentecanyon.app.data.remote.edf.EdfPracticabilityDto
import fr.descentecanyon.app.data.remote.edf.EdfPracticabilityRemoteSource
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.EdfPracticabilityCondition
import fr.descentecanyon.app.domain.model.EdfPracticabilityReference
import fr.descentecanyon.app.domain.model.EdfPracticabilitySample
import fr.descentecanyon.app.domain.model.EdfPracticabilityThreshold
import fr.descentecanyon.app.domain.repository.EdfPracticabilityRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

@Singleton
class EdfPracticabilityRepositoryImpl @Inject constructor(
    private val remoteSource: EdfPracticabilityRemoteSource,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : EdfPracticabilityRepository {

    override fun getReference(canyonId: Int): EdfPracticabilityReference? = EdfPracticabilityMappings.getReference(canyonId)

    override suspend fun getStatus(canyonId: Int): Result<CanyonEdfPracticability> = withContext(ioDispatcher) {
        suspendRunCatching {
            val reference = getReference(canyonId)
                ?: appError(AppError.DataUnavailable)
            retryOnConnectivityFailure {
                remoteSource.fetchPracticability(reference.practicabilityId)
            }.toDomain(reference)
        }
    }

    private fun EdfPracticabilityDto.toDomain(reference: EdfPracticabilityReference): CanyonEdfPracticability {
        val chart = charts.firstOrNull { it.type == WATER_LEVEL_CHART && it.activity == CANYONING_ACTIVITY }
            ?: charts.firstOrNull { it.type == WATER_LEVEL_CHART }
        val lastSample = chart?.graph?.datas?.lastOrNull()?.toDomainSample()
        return CanyonEdfPracticability(
            practicabilityId = id,
            title = title,
            amenagementTitle = amenagement?.title.orEmpty(),
            sourceUrl = reference.sourceUrl,
            state = parseCondition(state).takeUnless { it == EdfPracticabilityCondition.UNKNOWN }
                ?: lastSample?.condition
                ?: EdfPracticabilityCondition.UNKNOWN,
            lastSample = lastSample,
            thresholds = chart?.graph?.limits.orEmpty().map { limit -> limit.toDomainThreshold() },
            description = description?.toPlainText(),
            hasPublishedEventInProgress = hasPublishedEventInProgress,
        )
    }

    private fun EdfDataPointDto.toDomainSample(): EdfPracticabilitySample? {
        val rawDateTime = dateTime ?: return null
        val instant = runCatching { Instant.parse(rawDateTime) }.getOrNull() ?: return null
        return EdfPracticabilitySample(
            value = value,
            recordedAt = instant,
            condition = parseCondition(condition),
        )
    }

    private fun EdfLimitDto.toDomainThreshold(): EdfPracticabilityThreshold = EdfPracticabilityThreshold(
        condition = parseCondition(condition),
        min = min,
        max = max,
    )

    private fun parseCondition(value: String?): EdfPracticabilityCondition = when (value?.trim()?.uppercase()) {
        "APPROPRIATE" -> EdfPracticabilityCondition.APPROPRIATE
        "NOT_APPROPRIATE" -> EdfPracticabilityCondition.NOT_APPROPRIATE
        "NOT_INTERPRETED" -> EdfPracticabilityCondition.NOT_INTERPRETED
        else -> EdfPracticabilityCondition.UNKNOWN
    }

    private fun String.toPlainText(): String = Jsoup.parse(this)
        .text()
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val WATER_LEVEL_CHART = "WATER_LEVEL"
        const val CANYONING_ACTIVITY = "CANYONING"
    }
}
