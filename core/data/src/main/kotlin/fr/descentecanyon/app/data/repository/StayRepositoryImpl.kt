package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.PlannedOutingDao
import fr.descentecanyon.app.data.local.user.dao.StayDao
import fr.descentecanyon.app.data.local.user.entity.StayEntity
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.repository.StayRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class StayRepositoryImpl @Inject constructor(
    private val stayDao: StayDao,
    private val plannedOutingDao: PlannedOutingDao,
    private val clock: Clock,
) : StayRepository {

    override fun observeAll(): Flow<List<Stay>> = stayDao.observeAll().map { stays -> stays.mapNotNull { it.toDomain() } }
        .distinctUntilChanged()

    override suspend fun get(id: Long): Stay? = stayDao.get(id)?.toDomain()

    override suspend fun save(stay: Stay): Long {
        val previous = stay.id.takeIf { it > 0 }?.let { stayDao.get(it) }
        return stayDao.upsert(
            StayEntity(
                id = stay.id,
                placeId = stay.placeId,
                start = stay.start.toString(),
                end = stay.end.toString(),
                createdAt = previous?.createdAt ?: clock.millis(),
            ),
        )
    }

    override suspend fun delete(id: Long) {
        plannedOutingDao.deleteDraftsOfStay(id)
        plannedOutingDao.detachFromStay(id)
        stayDao.delete(id)
    }

    private fun StayEntity.toDomain(): Stay? {
        val first = runCatching { LocalDate.parse(start) }.getOrNull() ?: return null
        val last = runCatching { LocalDate.parse(end) }.getOrNull()?.takeIf { !it.isBefore(first) } ?: return null
        return Stay(placeId = placeId, start = first, end = last, id = id)
    }
}
