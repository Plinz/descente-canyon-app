package fr.descentecanyon.app.outing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.OutingRepository
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Follows the planned outing: at the expected return, a notification asks how it went and offers
 * to report the flow (a fresh report is the best input of the flow estimates of everyone).
 */
@Singleton
class OutingReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val outingRepository: OutingRepository,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            outingRepository.observeCurrent().collect { outing ->
                if (outing == null) cancel() else schedule(outing)
            }
        }
    }

    private fun schedule(outing: PlannedOuting) {
        val delay = Duration.between(clock.instant(), outing.expectedBack)
        // Past reminders are not replayed: the outing is over or was planned after the fact.
        if (delay.isNegative) return cancel()
        val request = OneTimeWorkRequestBuilder<OutingReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    OutingReminderWorker.KEY_CANYON_ID to outing.canyonId,
                    OutingReminderWorker.KEY_CANYON_NAME to outing.canyonName,
                    OutingReminderWorker.KEY_EXPECTED_BACK to outing.expectedBack.toEpochMilli(),
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "outing-end-reminder"
    }
}
