package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.Shuttle
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.model.meetingParking
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** A day of a stay seen while choosing its canyon: the stay, its place and the other days each canyon is planned for. */
data class StayDayChoice(val stay: Stay, val place: Place, val plannedOn: Map<Int, LocalDate> = emptyMap())

/**
 * Choosing the canyon of a day of a stay among the canyons around its place: the canyons already
 * planned on its other days, and the drive to the nearest ones. An estimate in a straight line
 * misleads where a ridge stands between the campsite and the canyon, so the real road is asked once
 * online for the first canyons listed.
 */
class ChooseStayCanyonUseCase @Inject constructor(
    private val stayRepository: StayRepository,
    private val placeRepository: PlaceRepository,
    private val outingRepository: OutingRepository,
    private val canyonRepository: CanyonRepository,
    private val driveTimeRepository: DriveTimeRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) {
    /** The stay [stayId] seen from its day [date]; null while it or its place is not there. */
    fun observe(stayId: Long, date: LocalDate): Flow<StayDayChoice?> = combine(
        stayRepository.observeAll(),
        placeRepository.observeAll(),
        outingRepository.observeAll(),
    ) { stays, places, outings ->
        val stay = stays.firstOrNull { it.id == stayId } ?: return@combine null
        val place = places.firstOrNull { it.id == stay.placeId } ?: return@combine null
        val plannedOn = outings.filter(stay::includes)
            .flatMap { outing -> outing.canyonIds.map { it to outing.dayIn(clock.zone) } }
            .filter { (_, day) -> day != date }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, days) -> days.min() }
        StayDayChoice(stay, place, plannedOn)
    }.distinctUntilChanged()

    /**
     * The drives from [place] kept on the device, by canyon, then the real road to the first of
     * [canyons] still estimated as they come: one route a second at most, as the public routing
     * service asks, each canyon once, a few per visit, none far away.
     */
    fun observeDrives(place: Place, canyons: Flow<List<CanyonSearchItem>>): Flow<Map<Int, DriveTime>> = channelFlow {
        var known = driveTimeRepository.storedFrom(place.id)
        send(known)
        val asked = mutableSetOf<Int>()
        canyons.conflate().collect { list ->
            val toAsk = list.take(ROUTED_CANYONS)
                .filter { it.id !in known && it.id !in asked && it.isNear(place) }
                .take(MAX_ROUTES_PER_VISIT - asked.size)
            for (canyon in toAsk) {
                if (!connectivityObserver.isCurrentlyOnline()) return@collect
                asked += canyon.id
                val detail = canyonRepository.getCanyonPreview(canyon.id).getOrNull() ?: continue
                val parking = detail.meetingParking(withShuttle = Shuttle.parse(detail.canyon.navette) != null) ?: continue
                driveTimeRepository.route(place, canyon.id, parking).getOrNull()?.let { time ->
                    known = known + (canyon.id to time)
                    send(known)
                }
                delay(ROUTE_INTERVAL_MS)
            }
        }
    }

    private fun CanyonSearchItem.isNear(place: Place): Boolean {
        val latitude = representativeLat ?: return false
        val longitude = representativeLng ?: return false
        return haversineKm(place.latitude, place.longitude, latitude, longitude) <= ROUTED_MAX_KM
    }

    companion object {
        /** The canyons around a stay are first listed within this distance in a straight line. */
        const val DEFAULT_DISTANCE_KM = 40

        /** The first canyons of the list whose real drive is asked. */
        const val ROUTED_CANYONS = 8
        private const val MAX_ROUTES_PER_VISIT = 12
        private const val ROUTE_INTERVAL_MS = 1_100L

        /** Beyond this, a canyon is not a day out from the place: no route asked. */
        private const val ROUTED_MAX_KM = 100.0
    }
}

/** The drive to [item] from [place]: the one kept or found by road in [drives], else estimated in a straight line. */
fun driveTo(place: Place, item: CanyonSearchItem, drives: Map<Int, DriveTime>): DriveTime? {
    drives[item.id]?.let { return it }
    val latitude = item.representativeLat ?: return null
    val longitude = item.representativeLng ?: return null
    return DriveTime.estimate(place.latitude, place.longitude, latitude, longitude)
}
