package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.repository.CanyonRepository
import javax.inject.Inject

class GetCanyonPreviewUseCase @Inject constructor(private val canyonRepository: CanyonRepository) {
    suspend operator fun invoke(canyonId: Int): Result<CanyonDetail> = canyonRepository.getCanyonPreview(canyonId)
}
