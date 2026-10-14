package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.EdfPracticabilityReference
import fr.descentecanyon.app.domain.repository.EdfPracticabilityRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeEdfPracticabilityRepository @Inject constructor() : EdfPracticabilityRepository {
    override fun getReference(canyonId: Int): EdfPracticabilityReference? = null

    override suspend fun getStatus(canyonId: Int): Result<CanyonEdfPracticability> =
        Result.failure(IllegalStateException("No fake EDF status for canyon $canyonId"))
}
