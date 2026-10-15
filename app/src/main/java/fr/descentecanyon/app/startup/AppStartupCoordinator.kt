package fr.descentecanyon.app.startup

import android.util.Log
import fr.descentecanyon.app.data.local.LocalDataInitializer
import fr.descentecanyon.app.data.repository.LegacyPhotoStorageMigrator
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.outing.OutingReminderScheduler
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.sync.NotificationSyncScheduler
import fr.descentecanyon.app.sync.PendingDebitSyncScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class AppStartupCoordinator @Inject constructor(
    private val localDataInitializer: LocalDataInitializer,
    private val authRepository: AuthRepository,
    private val searchCatalogWarmupCoordinator: SearchCatalogWarmupCoordinator,
    private val predictionWarmupCoordinator: PredictionWarmupCoordinator,
    private val legacyPhotoStorageMigrator: LegacyPhotoStorageMigrator,
    private val notificationSyncScheduler: NotificationSyncScheduler,
    private val pendingDebitSyncScheduler: PendingDebitSyncScheduler,
    private val outingReminderScheduler: OutingReminderScheduler,
    private val connectivityObserver: ConnectivityObserver,
) {

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializeMutex = Mutex()

    @Volatile private var initialized = false

    /**
     * The UI waits for the initialization only when the catalog has to be copied from the APK (first
     * launch or update) or when the data of a version <= 2.2 has to be migrated.
     */
    suspend fun resolveLaunchMode(): StartupLaunchMode {
        val launchMode = if (localDataInitializer.needsInitialization()) {
            StartupLaunchMode.BLOCKING_IMPORT
        } else {
            StartupLaunchMode.NON_BLOCKING
        }
        PerformanceTrace.logEvent(event = "startup_launch_mode_resolved", "launchMode" to launchMode.logLabel)
        return launchMode
    }

    suspend fun initialize() {
        if (initialized) return

        initializeMutex.withLock {
            if (initialized) return

            PerformanceTrace.logEvent("startup_initialize_enter")
            localDataInitializer.initialize()?.let { report ->
                PerformanceTrace.logEvent(
                    event = "startup_legacy_data_migrated",
                    "favorites" to report.favorites,
                    "pendingSubmissions" to report.pendingSubmissions,
                    "debits" to report.debits,
                    "photos" to report.photos,
                    "dailyWeather" to report.dailyWeather,
                    "keyValues" to report.keyValues,
                )
            }
            PerformanceTrace.logEvent("startup_local_data_ready")

            PerformanceTrace.start(AUTH_RESTORE_TRACE_KEY, "auth_restore_session")
            val restoreResult = authRepository.tryRestoreSession()
            PerformanceTrace.end(
                key = AUTH_RESTORE_TRACE_KEY,
                outcome = if (restoreResult.isSuccess) "restored" else "no_session",
            )

            notificationSyncScheduler.start()
            pendingDebitSyncScheduler.start()
            outingReminderScheduler.start()
            initialized = true
            PerformanceTrace.logEvent("startup_initialize_marked_ready")
            backgroundScope.launch {
                if (restoreResult.isSuccess && connectivityObserver.isCurrentlyOnline()) {
                    // Tells the user now, rather than at their next submission, that the saved session expired.
                    authRepository.validateSession().onFailure { throwable ->
                        Log.i(TAG, "Saved session not validated: ${throwable.toAppError()}")
                    }
                }
                suspendRunCatching {
                    legacyPhotoStorageMigrator.migrateOrDeleteLegacyPhotos()
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to migrate legacy photo storage", throwable)
                }

                delay(SEARCH_CATALOG_WARMUP_DELAY_MS)
                PerformanceTrace.logEvent("search_catalog_warmup_scheduled", "delayMs" to SEARCH_CATALOG_WARMUP_DELAY_MS)
                suspendRunCatching {
                    searchCatalogWarmupCoordinator.warmupIfNeeded()
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to warm up search catalog in background", throwable)
                }

                delay(PREDICTION_WARMUP_DELAY_AFTER_SEARCH_MS)
                PerformanceTrace.logEvent("prediction_warmup_scheduled", "delayMs" to PREDICTION_WARMUP_DELAY_AFTER_SEARCH_MS)
                suspendRunCatching {
                    predictionWarmupCoordinator.warmupIfNeeded()
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to warm up prediction stack in background", throwable)
                }
            }
        }
    }

    private companion object {
        const val TAG = "AppStartupCoordinator"
        const val SEARCH_CATALOG_WARMUP_DELAY_MS = 3_000L
        const val PREDICTION_WARMUP_DELAY_AFTER_SEARCH_MS = 2_500L
        const val AUTH_RESTORE_TRACE_KEY = "startup.auth_restore"
    }
}

enum class StartupLaunchMode(val logLabel: String) {
    NON_BLOCKING("normal_launch"),
    BLOCKING_IMPORT("blocking_import"),
}
