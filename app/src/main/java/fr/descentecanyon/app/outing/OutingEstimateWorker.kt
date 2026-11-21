package fr.descentecanyon.app.outing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.repository.OutingEstimateMonitor
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import java.time.LocalTime

/**
 * Computes again the flow estimates of the outings planned in the next days (see
 * [OutingEstimateMonitor]) and tells the user when the day of one becomes a high-flow risk. At
 * night the alert waits for the next run, after [QUIET_UNTIL].
 */
class OutingEstimateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, OutingEstimateEntryPoint::class.java)
        val monitor = entryPoint.outingEstimateMonitor()
        val alerts = runCatching { monitor.refresh() }.getOrElse { return Result.retry() }
        val now = LocalTime.now()
        if (now.isBefore(QUIET_UNTIL) || !now.isBefore(QUIET_FROM)) return Result.success()
        val publisher = entryPoint.appNotificationPublisher()
        alerts.forEach { alert -> if (publisher.publishOutingFlowAlert(alert)) monitor.markAlerted(alert) }
        return Result.success()
    }

    private companion object {
        val QUIET_FROM: LocalTime = LocalTime.of(22, 0)
        val QUIET_UNTIL: LocalTime = LocalTime.of(7, 0)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OutingEstimateEntryPoint {
    fun outingEstimateMonitor(): OutingEstimateMonitor
    fun appNotificationPublisher(): AppNotificationPublisher
}
