package fr.descentecanyon.app.startup

import android.util.Log
import fr.descentecanyon.app.data.repository.EmbeddedDebitModelStore
import fr.descentecanyon.app.perf.PerformanceTrace
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PredictionWarmupCoordinator @Inject constructor(private val modelStore: EmbeddedDebitModelStore) {

    private val warmupMutex = Mutex()

    @Volatile
    private var warmedUp = false

    suspend fun warmupIfNeeded() {
        if (warmedUp) {
            PerformanceTrace.logEvent("prediction_warmup_skipped", "reason" to "already_warmed_up")
            return
        }

        warmupMutex.withLock {
            if (warmedUp) {
                PerformanceTrace.logEvent("prediction_warmup_skipped", "reason" to "already_warmed_up")
                return
            }

            PerformanceTrace.start(WARMUP_TRACE_KEY, "prediction_warmup")
            try {
                // Keep warmup focused on the ONNX session and lightweight metadata.
                // The large lookup/static feature JSONs are loaded on first real prediction use.
                modelStore.getFeatureSpec()
                modelStore.getThresholds()
                modelStore.getSession()

                warmedUp = true
                PerformanceTrace.end(
                    key = WARMUP_TRACE_KEY,
                    outcome = "ok",
                    "deferredAssets" to "runtime_lookups,static_features",
                )
            } catch (throwable: Throwable) {
                PerformanceTrace.end(
                    key = WARMUP_TRACE_KEY,
                    outcome = "failed",
                    "error" to (throwable.message ?: throwable::class.simpleName),
                )
                throw throwable
            }
        }
    }

    private companion object {
        const val TAG = "PredictionWarmup"
        const val WARMUP_TRACE_KEY = "startup.prediction_warmup"
    }
}
