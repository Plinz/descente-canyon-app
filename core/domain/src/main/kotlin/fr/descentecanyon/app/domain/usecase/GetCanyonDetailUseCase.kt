package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.repository.CanyonRepository
import javax.inject.Inject

class GetCanyonDetailUseCase @Inject constructor(private val canyonRepository: CanyonRepository) {
    suspend operator fun invoke(canyonId: Int): Result<CanyonDetail> = canyonRepository.getCanyonDetail(canyonId)
}
