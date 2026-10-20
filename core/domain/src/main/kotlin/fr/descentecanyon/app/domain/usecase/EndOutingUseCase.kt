package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

/**
 * "I'm back": the outing goes to the logbook, on the day it started, with the time from leaving the
 * car to now, and is removed, which cancels its notifications. Returns the outing ended, or null
 * when there was none with this id.
 */
class EndOutingUseCase @Inject constructor(
    private val outingRepository: OutingRepository,
    private val logbookRepository: LogbookRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(outingId: Long): PlannedOuting? {
        val outing = outingRepository.get(outingId) ?: return null
        val minutes = Duration.between(outing.start, clock.instant()).toMinutes()
        logbookRepository.record(
            LogbookEntry(
                canyonId = outing.canyonId,
                canyonName = outing.canyonName,
                date = outing.start.atZone(clock.zone).toLocalDate(),
                // Tapped before leaving or long after the return: the time says nothing of the outing.
                durationMinutes = minutes.takeIf { it in 1..LogbookEntry.MAX_RECORDED_DURATION_MINUTES }?.toInt(),
                source = LogbookSource.OUTING,
            ),
        )
        outingRepository.delete(outing.id)
        return outing
    }
}
