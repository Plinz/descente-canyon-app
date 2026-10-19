package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.SiteObservation
import fr.descentecanyon.app.domain.repository.SiteAccountRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeSiteAccountRepository @Inject constructor() : SiteAccountRepository {
    override suspend fun fetchMyObservations(): Result<List<SiteObservation>> = Result.success(emptyList())
}
