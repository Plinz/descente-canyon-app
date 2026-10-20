package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The drive from a starting place to a canyon parking: the time kept (the user's own or a route),
 * else an offline estimate at once, then the route when the network answers.
 */
class GetDriveTimeUseCase @Inject constructor(
    private val driveTimeRepository: DriveTimeRepository,
    private val connectivityObserver: ConnectivityObserver,
) {
    operator fun invoke(place: Place, canyonId: Int, destination: GeoPoint): Flow<DriveTime> = flow {
        driveTimeRepository.stored(place.id, canyonId, destination)?.let {
            emit(it)
            return@flow
        }
        emit(DriveTime.estimate(place.latitude, place.longitude, destination.latitude, destination.longitude))
        if (connectivityObserver.isCurrentlyOnline()) {
            driveTimeRepository.route(place, canyonId, destination).getOrNull()?.let { emit(it) }
        }
    }
}
