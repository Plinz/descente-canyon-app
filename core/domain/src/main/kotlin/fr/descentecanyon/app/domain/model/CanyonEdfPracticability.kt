package fr.descentecanyon.app.domain.model

import java.time.Instant

data class CanyonEdfPracticability(
    val practicabilityId: Long,
    val title: String,
    val amenagementTitle: String,
    val sourceUrl: String,
    val state: EdfPracticabilityCondition,
    val lastSample: EdfPracticabilitySample? = null,
    val thresholds: List<EdfPracticabilityThreshold> = emptyList(),
    val description: String? = null,
    val hasPublishedEventInProgress: Boolean = false,
)

data class EdfPracticabilityReference(val practicabilityId: Long, val sourceUrl: String)

data class EdfPracticabilitySample(val value: Double?, val recordedAt: Instant, val condition: EdfPracticabilityCondition)

data class EdfPracticabilityThreshold(val condition: EdfPracticabilityCondition, val min: Double?, val max: Double?)

enum class EdfPracticabilityCondition {
    APPROPRIATE,
    NOT_APPROPRIATE,
    NOT_INTERPRETED,
    UNKNOWN,
}
