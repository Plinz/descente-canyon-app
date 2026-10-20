package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.Place

/** Drive times from the starting places to the canyon parkings, computed once and kept. */
interface DriveTimeRepository {
    /** The time kept for this place and parking (a route or the user's own time); null when there is none. */
    suspend fun stored(placeId: Long, canyonId: Int, destination: GeoPoint): DriveTime?

    /** The times kept from [placeId], by canyon. */
    suspend fun storedFrom(placeId: Long): Map<Int, DriveTime>

    /** Asks a routing service for the time by road, and keeps it. */
    suspend fun route(place: Place, canyonId: Int, destination: GeoPoint): Result<DriveTime>

    /** The user's own time for this drive: it is used from now on instead of the route. */
    suspend fun setManual(placeId: Long, canyonId: Int, destination: GeoPoint, minutes: Int)

    /** Forgets the user's own time (and the route, computed again). */
    suspend fun clear(placeId: Long, canyonId: Int)
}
