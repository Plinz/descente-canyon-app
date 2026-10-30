package fr.descentecanyon.app.testutil

import fr.descentecanyon.app.domain.model.AccidentReport
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.FoundPlace
import fr.descentecanyon.app.domain.model.GearListSettings
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.MessageAudience
import fr.descentecanyon.app.domain.model.MessageSection
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.repository.AccidentReportRepository
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import fr.descentecanyon.app.domain.repository.OutingPreferencesRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.PlaceSearchRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [OutingRepository]: ids given in order, outings kept soonest first. */
class FakeOutingRepository : OutingRepository {
    val outings = MutableStateFlow<List<PlannedOuting>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<PlannedOuting>> = outings

    override suspend fun get(id: Long): PlannedOuting? = outings.value.firstOrNull { it.id == id }

    override suspend fun save(outing: PlannedOuting): Long {
        val id = outing.id.takeIf { it > 0 } ?: nextId++
        outings.update { list -> (list.filterNot { it.id == id } + outing.copy(id = id)).sortedBy { it.start } }
        return id
    }

    override suspend fun delete(id: Long) = outings.update { list -> list.filterNot { it.id == id } }

    override suspend fun clear() = outings.update { emptyList() }
}

/** In-memory [PlaceRepository]. */
class FakePlaceRepository(places: List<Place> = emptyList()) : PlaceRepository {
    val places = MutableStateFlow(places)
    private var nextId = (places.maxOfOrNull { it.id } ?: 0L) + 1

    override fun observeAll(): Flow<List<Place>> = places

    override suspend fun get(id: Long): Place? = places.value.firstOrNull { it.id == id }

    override suspend fun save(place: Place): Long {
        val id = place.id.takeIf { it > 0 } ?: nextId++
        places.update { list -> list.filterNot { it.id == id } + place.copy(id = id) }
        return id
    }

    override suspend fun delete(id: Long) = places.update { list -> list.filterNot { it.id == id } }

    override suspend fun markUsed(id: Long) = places.update { list ->
        list.map {
            if (it.id ==
                id
            ) {
                it.copy(lastUsedAt = it.lastUsedAt + 1)
            } else {
                it
            }
        }
    }
}

/** In-memory [StayRepository]; deleting a stay drops the outings of its days in [outings], but the one under way at [clock]. */
class FakeStayRepository(
    stays: List<Stay> = emptyList(),
    private val outings: FakeOutingRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
) : StayRepository {
    val stays = MutableStateFlow(stays)
    private var nextId = (stays.maxOfOrNull { it.id } ?: 0L) + 1

    override fun observeAll(): Flow<List<Stay>> = stays

    override suspend fun get(id: Long): Stay? = stays.value.firstOrNull { it.id == id }

    override suspend fun save(stay: Stay): Long {
        val id = stay.id.takeIf { it > 0 } ?: nextId++
        stays.update { list -> (list.filterNot { it.id == id } + stay.copy(id = id)).sortedBy { it.start } }
        return id
    }

    override suspend fun delete(id: Long) {
        val stay = get(id) ?: return
        stays.update { list -> list.filterNot { it.id == id } }
        outings?.outings?.update { list ->
            list.filterNot { stay.includes(it) && !it.isUnderway(clock.instant()) }.map {
                if (it.stayId ==
                    id
                ) {
                    it.copy(stayId = null)
                } else {
                    it
                }
            }
        }
    }
}

/** [DriveTimeRepository] answering [route] for every place, and keeping the user's own times. */
class FakeDriveTimeRepository(var route: DriveTime? = null) : DriveTimeRepository {
    val manual = mutableMapOf<Pair<Long, Int>, DriveTime>()

    override suspend fun stored(placeId: Long, canyonId: Int, destination: GeoPoint): DriveTime? = manual[placeId to canyonId]

    override suspend fun storedFrom(placeId: Long): Map<Int, DriveTime> =
        manual.filterKeys { it.first == placeId }.mapKeys { it.key.second }

    override suspend fun route(place: Place, canyonId: Int, destination: GeoPoint): Result<DriveTime> =
        route?.let { Result.success(it) } ?: Result.failure(IllegalStateException("No route"))

    override suspend fun setManual(placeId: Long, canyonId: Int, destination: GeoPoint, minutes: Int) {
        manual[placeId to canyonId] = DriveTime(minutes, DriveTimeSource.MANUAL)
    }

    override suspend fun clear(placeId: Long, canyonId: Int) {
        manual.remove(placeId to canyonId)
    }
}

/** [PlaceSearchRepository] answering [results] (or failing when null), with the searches made. */
class FakePlaceSearchRepository(var results: List<FoundPlace>? = emptyList()) : PlaceSearchRepository {
    val queries = mutableListOf<String>()

    override suspend fun search(query: String, nearLatitude: Double?, nearLongitude: Double?, language: String?): Result<List<FoundPlace>> {
        queries += query
        return results?.let { Result.success(it) } ?: Result.failure(IllegalStateException("Offline"))
    }
}

/** In-memory [OutingPreferencesRepository]. */
class FakeOutingPreferencesRepository(settings: OutingSettings = OutingSettings()) : OutingPreferencesRepository {
    val settings = MutableStateFlow(settings)
    val sections = MutableStateFlow(MessageAudience.entries.associateWith { it.defaultSections })
    val gear = MutableStateFlow(GearListSettings())

    override fun observeSettings(): Flow<OutingSettings> = settings

    override suspend fun setSettings(settings: OutingSettings) {
        this.settings.value = settings
    }

    override fun observeMessageSections(): Flow<Map<MessageAudience, Set<MessageSection>>> = sections

    override suspend fun setMessageSections(audience: MessageAudience, sections: Set<MessageSection>) =
        this.sections.update { it + (audience to sections) }

    override fun observeGearList(): Flow<GearListSettings> = gear

    override suspend fun setGearList(settings: GearListSettings) {
        gear.value = settings
    }
}

/** In-memory [AccidentReportRepository], without expiry. */
class FakeAccidentReportRepository(report: AccidentReport? = null) : AccidentReportRepository {
    val report = MutableStateFlow(report)

    override fun observe(): Flow<AccidentReport?> = report

    override suspend fun save(report: AccidentReport) {
        this.report.value = report.takeUnless { it.isEmpty }
    }

    override suspend fun clear() {
        report.value = null
    }
}
