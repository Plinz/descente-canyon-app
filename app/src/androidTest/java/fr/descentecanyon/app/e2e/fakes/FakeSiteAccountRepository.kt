package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.SiteObservation
import fr.descentecanyon.app.domain.repository.SiteAccountRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeSiteAccountRepository @Inject constructor() : SiteAccountRepository {
    override suspend fun refreshMyObservations(): Result<List<SiteObservation>> = Result.success(emptyList())

    override suspend fun myObservationsReadAt(): Long? = null

    override suspend fun clearMyObservations() = Unit
}
