package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.DriveTimeDao
import fr.descentecanyon.app.data.local.user.dao.PlaceDao
import fr.descentecanyon.app.data.local.user.dao.StayDao
import fr.descentecanyon.app.data.local.user.entity.PlaceEntity
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class PlaceRepositoryImpl @Inject constructor(
    private val placeDao: PlaceDao,
    private val driveTimeDao: DriveTimeDao,
    private val stayRepository: StayRepository,
    private val stayDao: StayDao,
    private val clock: Clock,
) : PlaceRepository {

    override fun observeAll(): Flow<List<Place>> = placeDao.observeAll().map { places -> places.map { it.toDomain() } }

    override suspend fun get(id: Long): Place? = placeDao.get(id)?.toDomain()

    override suspend fun save(place: Place): Long {
        val previous = place.id.takeIf { it > 0 }?.let { placeDao.get(it) }
        // A place moved: its drive times no longer hold.
        if (previous != null && (previous.latitude != place.latitude || previous.longitude != place.longitude)) {
            driveTimeDao.deleteFrom(place.id)
        }
        return placeDao.upsert(
            PlaceEntity(
                id = place.id,
                name = place.name.trim(),
                latitude = place.latitude,
                longitude = place.longitude,
                lastUsedAt = place.lastUsedAt,
                createdAt = previous?.createdAt ?: clock.millis(),
            ),
        )
    }

    override suspend fun delete(id: Long) {
        stayDao.idsAt(id).forEach { stayRepository.delete(it) }
        driveTimeDao.deleteFrom(id)
        placeDao.delete(id)
    }

    override suspend fun markUsed(id: Long) = placeDao.markUsed(id, clock.millis())

    private fun PlaceEntity.toDomain() = Place(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        lastUsedAt = lastUsedAt,
    )
}
