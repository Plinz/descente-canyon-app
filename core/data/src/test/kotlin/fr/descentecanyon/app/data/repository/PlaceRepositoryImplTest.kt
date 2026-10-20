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
import fr.descentecanyon.app.testutil.TestDatabases
import io.mockk.mockk
import java.time.Clock
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
    private val places = PlaceRepositoryImpl(database.placeDao(), database.driveTimeDao(), clock)
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
    fun `places used last come first and keep their stay`() = runTest {
        val home = places.save(Place("Maison", 45.0, 5.0))
        val campsite = places.save(Place("Camping", 45.1, 6.08, LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 18)))
        places.markUsed(home)

        val all = places.observeAll().first()
        assertEquals(listOf("Maison", "Camping"), all.map { it.name })
        assertEquals(LocalDate.of(2026, 7, 18), places.get(campsite)?.stayEnd)
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
