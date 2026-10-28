package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.model.overlapping
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Keeps a stay, unless it shares a day with another one: one is at one place a day. A stay moved as a
 * whole takes its outings along; the days its new dates drop lose their canyons, except the outing
 * under way, which only leaves the stay.
 */
class SaveStayUseCase @Inject constructor(
    private val stayRepository: StayRepository,
    private val outingRepository: OutingRepository,
    private val clock: Clock,
) {
    /** The id of the stay kept; null when it overlaps another stay, and nothing is kept. */
    suspend operator fun invoke(stay: Stay): Long? {
        if (stayRepository.observeAll().first().overlapping(stay) != null) return null
        val kept = stay.id.takeIf { it > 0 }?.let { stayRepository.get(it) }
        val id = stayRepository.save(stay)
        kept ?: return id
        val now = clock.instant()
        val days = kept.shiftTo(stay)
        outingRepository.observeAll().first().filter(kept::includes).forEach { outing ->
            val covered = stay.covers(outing.dayIn(clock.zone))
            when {
                outing.isUnderway(now) -> if (!covered) outingRepository.save(outing.copy(stayId = null))
                days != null -> outingRepository.save(outing.movedBy(days, clock.zone).copy(stayId = id))
                !covered -> outingRepository.delete(outing.id)
            }
        }
        return id
    }

    /** The outings of [kept] that its new dates [stay] take away: none when the whole stay moves, its outings follow. */
    fun outingsLeftOut(kept: Stay, stay: Stay, outings: List<PlannedOuting>): List<PlannedOuting> {
        if (kept.shiftTo(stay) != null) return emptyList()
        val now = clock.instant()
        return outings.filter { kept.includes(it) && !it.isUnderway(now) && !stay.covers(it.dayIn(clock.zone)) }
    }
}
