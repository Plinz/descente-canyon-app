package fr.descentecanyon.app.domain.model

data class DebitPredictionInfoSummary(
    val modelName: String,
    val targetMode: String,
    val featureCount: Int,
    val trainRowCount: Int,
    val calibrationRowCount: Int,
    val testRowCount: Int,
    val canyonCount: Int,
    val regionCount: Int,
    val massifCount: Int,
    val topDrivers: List<DebitPredictionDriver>,
) {
    val totalObservationCount: Int
        get() = trainRowCount + calibrationRowCount + testRowCount
}

/** Family of model inputs, shown to explain what drives the prediction. */
enum class DebitPredictionDriver {
    CANYON_HISTORY,
    AREA_CONTEXT,
    RECENT_RAIN,
    TEMPERATURE_SNOW,
    SEASON,
    WATERSHED,
}
