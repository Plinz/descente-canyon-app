package fr.descentecanyon.app.domain.model

data class DebitPredictionInfoSummary(
    val modelName: String,
    val targetMode: String,
    val featureCount: Int,
    /** Reports the shipped model learned from (every usable report). */
    val trainRowCount: Int,
    /** Reports of the walk-forward test seasons, each estimated by a model trained before it (part of [trainRowCount]). */
    val testRowCount: Int,
    val canyonCount: Int,
    val regionCount: Int,
    val massifCount: Int,
    val topDrivers: List<DebitPredictionDriver>,
)

/** Family of model inputs, shown to explain what drives the prediction. */
enum class DebitPredictionDriver {
    CANYON_HISTORY,
    NEIGHBOR_REPORTS,
    RAIN_GAUGES,
    AREA_CONTEXT,
    RECENT_RAIN,
    TEMPERATURE_SNOW,
    SEASON,
    WATERSHED,
}
