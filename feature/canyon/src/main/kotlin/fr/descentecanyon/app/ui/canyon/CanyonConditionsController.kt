package fr.descentecanyon.app.ui.canyon

import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.usecase.GetCanyonDebitPredictionsUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonEdfPracticabilityUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.toUiText
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CanyonConditionsState(
    val weather: CanyonWeather? = null,
    val isLoadingWeather: Boolean = false,
    val weatherError: UiText? = null,
    val edfStatus: CanyonEdfPracticability? = null,
    val isLoadingEdfStatus: Boolean = false,
    val edfStatusError: UiText? = null,
    val edfStatusSourceUrl: String? = null,
    val predictions: CanyonDebitPredictions? = null,
    val isLoadingPredictions: Boolean = false,
    val predictionError: UiText? = null,
)

/**
 * Current conditions of the canyon detail screen: weather, EDF water release status and debit
 * predictions. They need the loaded detail (coordinates, watershed) and the network.
 */
class CanyonConditionsController @Inject constructor(
    private val getCanyonWeatherUseCase: GetCanyonWeatherUseCase,
    private val getCanyonEdfPracticabilityUseCase: GetCanyonEdfPracticabilityUseCase,
    private val getCanyonDebitPredictionsUseCase: GetCanyonDebitPredictionsUseCase,
    private val connectivityObserver: ConnectivityObserver,
) {

    private val _state = MutableStateFlow(CanyonConditionsState())
    val state: StateFlow<CanyonConditionsState> = _state.asStateFlow()

    private lateinit var scope: CoroutineScope
    private var canyonId: Int = 0
    private var predictionLoadJob: Job? = null

    fun attach(canyonId: Int, scope: CoroutineScope) {
        this.canyonId = canyonId
        this.scope = scope
        _state.update { it.copy(edfStatusSourceUrl = getCanyonEdfPracticabilityUseCase.getReference(canyonId)?.sourceUrl) }
    }

    /** The canyon is being (re)loaded: pending loads are dropped and reloaded with the new detail. */
    fun onReloadStarted() {
        predictionLoadJob?.cancel()
        _state.update { it.copy(isLoadingWeather = false, isLoadingPredictions = false) }
    }

    /** The canyon could not be loaded at all. */
    fun onDetailUnavailable() {
        _state.update { it.copy(isLoadingWeather = false, isLoadingPredictions = false) }
    }

    /** Loads the sections that are missing or failed for [detail]. */
    fun loadFor(detail: CanyonDetail) {
        maybeLoadEdfStatus()
        maybeLoadWeather(detail)
        maybeSchedulePredictions(detail)
    }

    private fun maybeLoadEdfStatus() {
        val current = _state.value
        val sourceUrl = current.edfStatusSourceUrl ?: return
        val shouldLoad = current.edfStatus == null || current.edfStatusError != null
        if (!shouldLoad || current.isLoadingEdfStatus) return
        if (!connectivityObserver.isCurrentlyOnline()) {
            _state.update {
                it.copy(
                    isLoadingEdfStatus = false,
                    edfStatusError = UiText.res(R.string.edf_status_load_error_network),
                    edfStatusSourceUrl = sourceUrl,
                )
            }
            PerformanceTrace.logEvent("canyon_edf_status_load_skipped", "canyonId" to canyonId, "reason" to "offline")
            return
        }

        _state.update { it.copy(isLoadingEdfStatus = true, edfStatusError = null, edfStatusSourceUrl = sourceUrl) }
        scope.launch {
            PerformanceTrace.start(edfStatusLoadKey(canyonId), "canyon_edf_status_load", "canyonId" to canyonId)
            getCanyonEdfPracticabilityUseCase(canyonId).fold(
                onSuccess = { status ->
                    PerformanceTrace.end(
                        key = edfStatusLoadKey(canyonId),
                        outcome = "ready",
                        "canyonId" to canyonId,
                        "state" to status.state.name,
                    )
                    _state.update { it.copy(edfStatus = status, isLoadingEdfStatus = false, edfStatusError = null) }
                },
                onFailure = { throwable ->
                    PerformanceTrace.end(
                        key = edfStatusLoadKey(canyonId),
                        outcome = "failed",
                        "canyonId" to canyonId,
                        "error" to throwable::class.simpleName,
                    )
                    _state.update {
                        it.copy(
                            edfStatus = null,
                            isLoadingEdfStatus = false,
                            edfStatusError = throwable.toUiText(
                                fallback = R.string.edf_status_unavailable_now,
                                connectivity = R.string.edf_status_load_error_network,
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun maybeLoadWeather(detail: CanyonDetail) {
        val current = _state.value
        val shouldLoad = current.weather == null || current.weatherError != null ||
            (current.weather.isFromCache && connectivityObserver.isCurrentlyOnline())
        if (!shouldLoad || current.isLoadingWeather) return
        if (!connectivityObserver.isCurrentlyOnline()) {
            PerformanceTrace.logEvent("canyon_weather_load_skipped", "canyonId" to detail.canyon.id, "reason" to "offline")
            _state.update { it.copy(isLoadingWeather = true, weatherError = null) }
            scope.launch { showCachedWeatherOr(detail.canyon.id, UiText.res(R.string.weather_load_error_network)) }
            return
        }

        _state.update { it.copy(isLoadingWeather = true, weatherError = null) }
        scope.launch {
            PerformanceTrace.start(weatherLoadKey(detail.canyon.id), "canyon_weather_load", "canyonId" to detail.canyon.id)
            getCanyonWeatherUseCase(detail).fold(
                onSuccess = { weather ->
                    PerformanceTrace.end(
                        key = weatherLoadKey(detail.canyon.id),
                        outcome = "ready",
                        "canyonId" to detail.canyon.id,
                        "hourlyCount" to weather.hourly.size,
                    )
                    _state.update { it.copy(weather = weather, isLoadingWeather = false, weatherError = null) }
                },
                onFailure = { throwable ->
                    PerformanceTrace.end(
                        key = weatherLoadKey(detail.canyon.id),
                        outcome = "failed",
                        "canyonId" to detail.canyon.id,
                        "error" to throwable::class.simpleName,
                    )
                    showCachedWeatherOr(
                        canyonId = detail.canyon.id,
                        error = throwable.toUiText(
                            fallback = R.string.weather_unavailable_now,
                            connectivity = R.string.weather_load_error_network,
                        ),
                    )
                },
            )
        }
    }

    /** Without network, the last forecast downloaded for the canyon is better than nothing. */
    private suspend fun showCachedWeatherOr(canyonId: Int, error: UiText) {
        val cached = getCanyonWeatherUseCase.cached(canyonId)
        _state.update {
            if (cached != null) {
                it.copy(weather = cached, isLoadingWeather = false, weatherError = null)
            } else {
                it.copy(weather = null, isLoadingWeather = false, weatherError = error)
            }
        }
    }

    private fun maybeSchedulePredictions(detail: CanyonDetail) {
        val current = _state.value
        val shouldLoad = current.predictions == null || current.predictionError != null ||
            (current.predictions.isFromCache && connectivityObserver.isCurrentlyOnline())
        if (!shouldLoad || current.isLoadingPredictions) return

        _state.update { it.copy(isLoadingPredictions = true, predictionError = null) }
        predictionLoadJob?.cancel()
        predictionLoadJob = scope.launch {
            // Let the canyon screen settle before loading the heaviest remote/native prediction stack.
            delay(PREDICTION_LOAD_DELAY_MS)
            if (!connectivityObserver.isCurrentlyOnline()) {
                PerformanceTrace.logEvent("canyon_predictions_load_skipped", "canyonId" to detail.canyon.id, "reason" to "offline")
                showCachedPredictionsOr(detail.canyon.id, UiText.res(R.string.prediction_load_error_network))
                return@launch
            }
            loadPredictions(detail)
        }
    }

    private suspend fun loadPredictions(detail: CanyonDetail) {
        PerformanceTrace.start(predictionLoadKey(detail.canyon.id), "canyon_predictions_load", "canyonId" to detail.canyon.id)
        getCanyonDebitPredictionsUseCase(detail).fold(
            onSuccess = { predictions ->
                PerformanceTrace.end(
                    key = predictionLoadKey(detail.canyon.id),
                    outcome = "ready",
                    "canyonId" to detail.canyon.id,
                    "dayCount" to predictions.predictions.size,
                )
                _state.update { it.copy(predictions = predictions, isLoadingPredictions = false, predictionError = null) }
            },
            onFailure = { throwable ->
                PerformanceTrace.end(
                    key = predictionLoadKey(detail.canyon.id),
                    outcome = "failed",
                    "canyonId" to detail.canyon.id,
                    "error" to throwable::class.simpleName,
                )
                showCachedPredictionsOr(
                    canyonId = detail.canyon.id,
                    error = throwable.toUiText(
                        fallback = R.string.prediction_unavailable_now,
                        connectivity = R.string.prediction_load_error_network,
                    ),
                )
            },
        )
    }

    /** Without network, the last estimate computed for the canyon still covers today. */
    private suspend fun showCachedPredictionsOr(canyonId: Int, error: UiText) {
        val cached = getCanyonDebitPredictionsUseCase.cached(canyonId)
        _state.update {
            if (cached != null) {
                it.copy(predictions = cached, isLoadingPredictions = false, predictionError = null)
            } else {
                it.copy(predictions = null, isLoadingPredictions = false, predictionError = error)
            }
        }
    }

    private companion object {
        const val PREDICTION_LOAD_DELAY_MS = 800L

        fun weatherLoadKey(canyonId: Int): String = "screen.canyon.$canyonId.weather"

        fun edfStatusLoadKey(canyonId: Int): String = "screen.canyon.$canyonId.edf"

        fun predictionLoadKey(canyonId: Int): String = "screen.canyon.$canyonId.predictions"
    }
}
