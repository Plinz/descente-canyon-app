package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.EdfPracticabilityReference
import fr.descentecanyon.app.domain.repository.EdfPracticabilityRepository
import javax.inject.Inject

class GetCanyonEdfPracticabilityUseCase @Inject constructor(private val repository: EdfPracticabilityRepository) {
    fun getReference(canyonId: Int): EdfPracticabilityReference? = repository.getReference(canyonId)

    suspend operator fun invoke(canyonId: Int): Result<CanyonEdfPracticability> = repository.getStatus(canyonId)
}
