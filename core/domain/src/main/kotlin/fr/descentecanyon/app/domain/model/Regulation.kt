package fr.descentecanyon.app.domain.model

data class Regulation(
    val id: Int,
    val status: String? = null,
    val action: String? = null,
    val title: String,
    val summary: String? = null,
    val remark: String? = null,
    val details: String? = null,
    val effectiveDate: String? = null,
    val textUrl: String,
    val attachments: List<RegulationAttachment> = emptyList(),
)

data class RegulationAttachment(val label: String, val url: String)
