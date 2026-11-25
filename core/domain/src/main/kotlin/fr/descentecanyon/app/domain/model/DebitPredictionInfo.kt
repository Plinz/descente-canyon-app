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
    /** The server's estimates checked against the reports that came in after them, when known. */
    val liveCheck: DebitLiveCheck? = null,
)

/** The first estimate the server made for each canyon and day, compared with the reports of that day. */
data class DebitLiveCheck(val since: java.time.LocalDate, val days: Int, val horizons: List<DebitLiveCheckHorizon>)

data class DebitLiveCheckHorizon(
    /** 0 = estimate of the day itself, 1 = made the day before, 2 = two days before. */
    val horizonDays: Int,
    val reportCount: Int,
    val exactCount: Int,
    val withinOneCount: Int,
    /** Reports of a high flow (GROS or more) and how many of them were announced as high. */
    val highReportCount: Int,
    val highCaughtCount: Int,
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
