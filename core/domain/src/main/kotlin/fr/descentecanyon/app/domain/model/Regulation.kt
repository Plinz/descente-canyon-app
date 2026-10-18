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
) {
    /** In force today; the site keeps obsolete and repealed texts in the history of the canyon. */
    val isInForce: Boolean get() = status.equals(STATUS_IN_FORCE, ignoreCase = true)

    /** The text forbids the canyon (rather than regulating access, periods, groups...). */
    val forbids: Boolean get() = action?.contains(ACTION_FORBIDS, ignoreCase = true) == true

    private companion object {
        const val STATUS_IN_FORCE = "Actif"
        const val ACTION_FORBIDS = "Interdit"
    }
}

data class RegulationAttachment(val label: String, val url: String)
