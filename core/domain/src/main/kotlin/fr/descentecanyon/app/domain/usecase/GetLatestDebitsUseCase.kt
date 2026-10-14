package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.repository.DebitRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class GetLatestDebitsUseCase @Inject constructor(private val debitRepository: DebitRepository) {
    operator fun invoke(limit: Int = 20): Flow<Result<List<Debit>>> = debitRepository.getLatestDebits(limit)
}
