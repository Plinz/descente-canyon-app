package fr.descentecanyon.app.data.remote.edf

import kotlinx.serialization.Serializable

@Serializable
data class EdfPracticabilityDto(
    val id: Long,
    val title: String,
    val description: String? = null,
    val amenagement: EdfAmenagementDto? = null,
    val charts: List<EdfChartDto> = emptyList(),
    val hasPublishedEventInProgress: Boolean = false,
    val state: String? = null,
)

@Serializable
data class EdfAmenagementDto(val id: Long, val title: String)

@Serializable
data class EdfChartDto(val type: String? = null, val activity: String? = null, val graph: EdfGraphDto? = null)

@Serializable
data class EdfGraphDto(val limits: List<EdfLimitDto> = emptyList(), val datas: List<EdfDataPointDto> = emptyList())

@Serializable
data class EdfLimitDto(val condition: String? = null, val min: Double? = null, val max: Double? = null)

@Serializable
data class EdfDataPointDto(val value: Double? = null, val dateTime: String? = null, val condition: String? = null)
