package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.NearbyCanyon
import fr.descentecanyon.app.domain.model.nearestCanyons
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Canyons around a canyon, from the offline catalog, with the last flow report known for each of
 * them (cached reports and latest reports feed) so a plan B can be chosen without network.
 */
class GetNearbyCanyonsUseCase @Inject constructor(
    private val canyonRepository: CanyonRepository,
    private val debitRepository: DebitRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(canyonId: Int, maxDistanceKm: Double = DEFAULT_RADIUS_KM, limit: Int = DEFAULT_LIMIT): Flow<List<NearbyCanyon>> =
        canyonRepository.observeSearchCatalog()
            .map { catalog -> nearestCanyons(canyonId, catalog, maxDistanceKm, limit) }
            .distinctUntilChanged()
            .flatMapLatest { nearby ->
                if (nearby.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    debitRepository.observeLatestKnownDebits(nearby.mapTo(mutableSetOf()) { it.summary.id }).map { debits ->
                        nearby.map { canyon ->
                            val debit = debits[canyon.summary.id] ?: return@map canyon
                            canyon.copy(summary = canyon.summary.copy(dernierDebit = debit.niveau, dernierDebitDate = debit.date))
                        }
                    }
                }
            }

    companion object {
        const val DEFAULT_RADIUS_KM = 30.0
        const val DEFAULT_LIMIT = 12
    }
}
