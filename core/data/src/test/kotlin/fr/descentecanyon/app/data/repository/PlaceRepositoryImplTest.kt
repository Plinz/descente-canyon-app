package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.local.user.UserDatabase
import fr.descentecanyon.app.data.network.ApiHttpClient
import fr.descentecanyon.app.data.remote.routing.OsrmRouteDto
import fr.descentecanyon.app.data.remote.routing.RoutingRemoteSource
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.testutil.TestDatabases
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlaceRepositoryImplTest {

    private val clock = Clock.fixed(Instant.parse("2026-07-14T06:00:00Z"), ZoneOffset.UTC)
    private val database: UserDatabase = TestDatabases.user()
    private val outings = OutingRepositoryImpl(database.plannedOutingDao(), clock)
    private val stays = StayRepositoryImpl(database.stayDao(), database.plannedOutingDao(), outings, clock)
    private val places = PlaceRepositoryImpl(database.placeDao(), database.driveTimeDao(), stays, database.stayDao(), clock)
    private var routeCalls = 0
    private val routing = object : RoutingRemoteSource(mockk<ApiHttpClient>()) {
        override suspend fun drive(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): OsrmRouteDto {
            routeCalls++
            return OsrmRouteDto(duration = 2360.9, distance = 31067.5)
        }
    }
    private val driveTimes = DriveTimeRepositoryImpl(database.driveTimeDao(), routing, clock)
    private val parking = GeoPoint(canyonId = 42, type = GeoPointType.PARKING_AVAL, latitude = 44.9853, longitude = 6.1505)

    @After
    fun tearDown() = database.close()

    @Test
    fun `places used last come first, and a deleted place takes its stays and their canyons away`() = runTest {
        val home = places.save(Place("Maison", 45.0, 5.0))
        val campsite = places.save(Place("Camping", 45.1, 6.08))
        places.markUsed(home)
        assertEquals(listOf("Maison", "Camping"), places.observeAll().first().map { it.name })

        val stay = stays.save(Stay(campsite, LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 18)))
        assertEquals(listOf(Stay(campsite, LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 18), stay)), stays.observeAll().first())
        val start = clock.instant().plus(Duration.ofDays(1))
        val planned = outings.save(PlannedOuting(42, "Riolan", start, start, start.plus(Duration.ofHours(6)), stayId = stay))
        // Under way when the stay goes: the group is out, the outing stays followed.
        val now = clock.instant()
        val underway = outings.save(
            PlannedOuting(43, "Écouges", now, now.plus(Duration.ofHours(4)), now.plus(Duration.ofHours(6)), stayId = stay),
        )

        places.delete(campsite)
        assertEquals(emptyList<Stay>(), stays.observeAll().first())
        assertNull(outings.get(planned))
        assertEquals(null, outings.get(underway)?.stayId)
        assertEquals(43, outings.get(underway)?.canyonId)
        assertEquals(listOf("Maison"), places.observeAll().first().map { it.name })
    }

    @Test
    fun `a route is asked once and kept, the user's own time wins, a moved place forgets them`() = runTest {
        val id = places.save(Place("Camping", 45.1015, 6.0794))
        val place = places.get(id)!!
        assertNull(driveTimes.stored(id, 42, parking))

        assertEquals(DriveTime(40, DriveTimeSource.ROUTE, 31.0675), driveTimes.route(place, 42, parking).getOrThrow())
        assertEquals(40, driveTimes.stored(id, 42, parking)?.minutes)
        assertEquals(mapOf(42 to DriveTime(40, DriveTimeSource.ROUTE, 31.0675)), driveTimes.storedFrom(id))
        // Another parking (shuttle): the route is computed again.
        assertNull(driveTimes.stored(id, 42, parking.copy(latitude = 45.2)))

        driveTimes.setManual(id, 42, parking, 55)
        driveTimes.route(place, 42, parking)
        assertEquals(DriveTime(55, DriveTimeSource.MANUAL), driveTimes.stored(id, 42, parking.copy(latitude = 45.2)))
        assertEquals(2, routeCalls)

        places.save(place.copy(latitude = 45.3))
        assertNull(driveTimes.stored(id, 42, parking))

        driveTimes.setManual(id, 7, parking, 20)
        places.delete(id)
        assertEquals(emptyMap<Int, DriveTime>(), driveTimes.storedFrom(id))
    }
}
