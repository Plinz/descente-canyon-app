package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonDuration
import fr.descentecanyon.app.domain.model.PersonalPace
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * The user's pace: the outings timed in the logbook against the times of their sheets (approach,
 * descent and return). Null until enough outings are timed.
 */
class GetPersonalPaceUseCase @Inject constructor(
    private val logbookRepository: LogbookRepository,
    private val canyonRepository: CanyonRepository,
) {
    suspend operator fun invoke(): PersonalPace? {
        val timed = logbookRepository.observeAll().first().filter { it.durationMinutes != null }
        if (timed.size < PersonalPace.MIN_OUTINGS) return null
        val sheetTotals = timed.map { it.canyonId }.distinct().associateWith { canyonId ->
            canyonRepository.getCanyonPreview(canyonId).getOrNull()?.canyon?.let { canyon ->
                CanyonDuration.total(
                    CanyonDuration.parse(canyon.tempsApproche),
                    CanyonDuration.parse(canyon.tempsDescente),
                    CanyonDuration.parse(canyon.tempsRetour),
                )
            }
        }
        val samples = timed.mapNotNull { entry ->
            val measured = entry.durationMinutes ?: return@mapNotNull null
            sheetTotals[entry.canyonId]?.let { measured to it }
        }
        return PersonalPace.of(samples)
    }
}
