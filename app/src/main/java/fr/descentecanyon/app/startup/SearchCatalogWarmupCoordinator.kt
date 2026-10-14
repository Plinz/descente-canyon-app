package fr.descentecanyon.app.startup

import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.perf.PerformanceTrace
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class SearchCatalogWarmupCoordinator @Inject constructor(private val searchCanyonsUseCase: SearchCanyonsUseCase) {

    private val warmupMutex = Mutex()

    @Volatile
    private var warmedUp = false

    suspend fun warmupIfNeeded() {
        if (warmedUp) {
            PerformanceTrace.logEvent("search_catalog_warmup_skipped", "reason" to "already_warmed_up")
            return
        }

        warmupMutex.withLock {
            if (warmedUp) {
                PerformanceTrace.logEvent("search_catalog_warmup_skipped", "reason" to "already_warmed_up")
                return
            }

            PerformanceTrace.start(WARMUP_TRACE_KEY, "search_catalog_warmup")
            try {
                val catalog = searchCanyonsUseCase.observeCatalog().first()
                warmedUp = true
                PerformanceTrace.end(
                    key = WARMUP_TRACE_KEY,
                    outcome = "ok",
                    "catalogSize" to catalog.size,
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
        const val WARMUP_TRACE_KEY = "startup.search_catalog_warmup"
    }
}
