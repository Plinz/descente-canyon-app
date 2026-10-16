package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * "I'm back": the current outing goes to the logbook, on the day it started, and is cleared, which
 * cancels its notifications. Returns the outing ended, or null when there was none for [canyonId].
 */
class EndOutingUseCase @Inject constructor(
    private val outingRepository: OutingRepository,
    private val logbookRepository: LogbookRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(canyonId: Int? = null): PlannedOuting? {
        val outing = outingRepository.observeCurrent().first() ?: return null
        if (canyonId != null && outing.canyonId != canyonId) return null
        logbookRepository.add(outing.canyonId, outing.canyonName, outing.start.atZone(clock.zone).toLocalDate(), null)
        outingRepository.clear()
        return outing
    }
}
