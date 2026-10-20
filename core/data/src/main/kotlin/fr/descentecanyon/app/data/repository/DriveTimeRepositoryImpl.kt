package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.DriveTimeDao
import fr.descentecanyon.app.data.local.user.entity.DriveTimeEntity
import fr.descentecanyon.app.data.remote.routing.RoutingRemoteSource
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.model.roundUpToFive
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveTimeRepositoryImpl @Inject constructor(
    private val dao: DriveTimeDao,
    private val remote: RoutingRemoteSource,
    private val clock: Clock,
) : DriveTimeRepository {

    override suspend fun stored(placeId: Long, canyonId: Int, destination: GeoPoint): DriveTime? {
        val entity = dao.get(placeId, canyonId) ?: return null
        // A route to another parking (the shuttle changed it) is computed again; the user's own time stays.
        if (entity.source != DriveTimeSource.MANUAL.name && entity.distanceTo(destination) > SAME_PARKING_KM) return null
        return entity.toDomain()
    }

    override suspend fun storedFrom(placeId: Long): Map<Int, DriveTime> = dao.getFrom(placeId).associate { it.canyonId to it.toDomain() }

    override suspend fun route(place: Place, canyonId: Int, destination: GeoPoint): Result<DriveTime> = suspendRunCatching {
        val route = remote.drive(place.latitude, place.longitude, destination.latitude, destination.longitude)
        val time = DriveTime(
            minutes = roundUpToFive(route.duration / SECONDS_PER_MINUTE),
            source = DriveTimeSource.ROUTE,
            distanceKm = route.distance / METERS_PER_KM,
        )
        if (dao.get(place.id, canyonId)?.source != DriveTimeSource.MANUAL.name) {
            dao.upsert(entity(place.id, canyonId, destination, time))
        }
        time
    }

    override suspend fun setManual(placeId: Long, canyonId: Int, destination: GeoPoint, minutes: Int) {
        dao.upsert(entity(placeId, canyonId, destination, DriveTime(minutes, DriveTimeSource.MANUAL)))
    }

    override suspend fun clear(placeId: Long, canyonId: Int) = dao.delete(placeId, canyonId)

    private fun entity(placeId: Long, canyonId: Int, destination: GeoPoint, time: DriveTime) = DriveTimeEntity(
        placeId = placeId,
        canyonId = canyonId,
        toLatitude = destination.latitude,
        toLongitude = destination.longitude,
        minutes = time.minutes,
        distanceKm = time.distanceKm,
        source = time.source.name,
        updatedAt = clock.millis(),
    )

    private fun DriveTimeEntity.distanceTo(point: GeoPoint) = haversineKm(toLatitude, toLongitude, point.latitude, point.longitude)

    private fun DriveTimeEntity.toDomain() = DriveTime(
        minutes = minutes,
        source = DriveTimeSource.entries.firstOrNull { it.name == source } ?: DriveTimeSource.ROUTE,
        distanceKm = distanceKm,
    )

    private companion object {
        const val SAME_PARKING_KM = 0.2
        const val SECONDS_PER_MINUTE = 60.0
        const val METERS_PER_KM = 1000.0
    }
}
