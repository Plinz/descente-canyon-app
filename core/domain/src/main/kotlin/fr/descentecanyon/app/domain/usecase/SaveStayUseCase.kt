package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Keeps a stay. Its outings left out by new dates leave it and stay planned on their day: a draft
 * goes back among the drafts, where the user sees it, instead of hiding in a stay that no longer
 * shows that day.
 */
class SaveStayUseCase @Inject constructor(
    private val stayRepository: StayRepository,
    private val outingRepository: OutingRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(stay: Stay): Long {
        val id = stayRepository.save(stay)
        if (stay.id > 0) {
            outingsLeftOut(stay, outingRepository.observeAll().first()).forEach { outingRepository.save(it.copy(stayId = null)) }
        }
        return id
    }

    /** The outings of [stay] (kept with it) whose day it would no longer cover with its dates. */
    fun outingsLeftOut(stay: Stay, outings: List<PlannedOuting>): List<PlannedOuting> =
        outings.filter { it.stayId == stay.id && !stay.covers(it.dayIn(clock.zone)) }
}
