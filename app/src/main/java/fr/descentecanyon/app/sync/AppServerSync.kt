package fr.descentecanyon.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.BackendConfig
import fr.descentecanyon.app.data.repository.AppServerRepositoryImpl
import fr.descentecanyon.app.data.repository.CatalogUpdateScheduler
import fr.descentecanyon.app.domain.error.isTransient
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.repository.AppServerRepository
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app in touch with its server while it is switched on: the signed manifest is read twice
 * a day, and a newer catalog is downloaded in the background (unmetered network unless the person
 * allowed mobile data). Nothing is scheduled when no server is built into the app.
 */
@Singleton
class AppServerSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val config: BackendConfig,
    private val repository: AppServerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false

    fun start() {
        if (started || !config.isConfigured) return
        started = true
        scope.launch {
            // A forbidden canyon or a message should not wait for the twice-daily check.
            val state = repository.observeState().first()
            val checkedAt = state.lastCheckedAt
            if (state.isActive && (checkedAt == null || Duration.between(checkedAt, Instant.now()) > STARTUP_CHECK_AFTER)) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    MANIFEST_NOW_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<AppServerManifestWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build(),
                )
            }
        }
        scope.launch {
            repository.observeState().map { it.isEnabled }.distinctUntilChanged().collect { enabled ->
                val workManager = WorkManager.getInstance(context)
                if (enabled) {
                    workManager.enqueueUniquePeriodicWork(
                        MANIFEST_WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<AppServerManifestWorker>(MANIFEST_PERIOD_HOURS, TimeUnit.HOURS)
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                            .build(),
                    )
                } else {
                    workManager.cancelUniqueWork(MANIFEST_WORK_NAME)
                }
            }
        }
    }

    internal companion object {
        const val MANIFEST_WORK_NAME = "app-server-manifest"
        const val MANIFEST_NOW_WORK_NAME = "app-server-manifest-now"
        val STARTUP_CHECK_AFTER: Duration = Duration.ofHours(3)
        const val CATALOG_WORK_NAME = "app-server-catalog-download"
        const val MANIFEST_PERIOD_HOURS = 12L
        const val BACKOFF_MINUTES = 30L
    }
}

@Singleton
class WorkManagerCatalogUpdateScheduler @Inject constructor(@param:ApplicationContext private val context: Context) :
    CatalogUpdateScheduler {

    override fun scheduleDownload(allowMobileData: Boolean) {
        val network = if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
        WorkManager.getInstance(context).enqueueUniqueWork(
            AppServerSyncScheduler.CATALOG_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CatalogDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, AppServerSyncScheduler.BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
    }

    override fun cancelDownload() {
        WorkManager.getInstance(context).cancelUniqueWork(AppServerSyncScheduler.CATALOG_WORK_NAME)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppServerWorkerEntryPoint {
    fun appServerRepository(): AppServerRepositoryImpl
}

class AppServerManifestWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            AppServerWorkerEntryPoint::class.java,
        ).appServerRepository()
        return repository.refresh().fold(
            onSuccess = { Result.success() },
            onFailure = { if (it.toAppError().isTransient() && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success() },
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

class CatalogDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            AppServerWorkerEntryPoint::class.java,
        ).appServerRepository()
        return repository.downloadAnnouncedCatalog().fold(
            onSuccess = { Result.success() },
            onFailure = { if (it.toAppError().isTransient() && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure() },
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppServerSyncModule {
    @Binds
    @Singleton
    abstract fun bindCatalogUpdateScheduler(impl: WorkManagerCatalogUpdateScheduler): CatalogUpdateScheduler
}
