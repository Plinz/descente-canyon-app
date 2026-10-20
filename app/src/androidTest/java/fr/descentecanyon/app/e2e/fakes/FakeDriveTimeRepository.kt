package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** No routing service in the tests: drives stay offline estimates. */
@Singleton
class FakeDriveTimeRepository @Inject constructor() : DriveTimeRepository {
    override suspend fun stored(placeId: Long, canyonId: Int, destination: GeoPoint): DriveTime? = null

    override suspend fun storedFrom(placeId: Long): Map<Int, DriveTime> = emptyMap()

    override suspend fun route(place: Place, canyonId: Int, destination: GeoPoint): Result<DriveTime> =
        Result.failure(IOException("No routing in tests"))

    override suspend fun setManual(placeId: Long, canyonId: Int, destination: GeoPoint, minutes: Int) = Unit

    override suspend fun clear(placeId: Long, canyonId: Int) = Unit
}
