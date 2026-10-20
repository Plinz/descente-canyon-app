package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetDriveTimeUseCaseTest {

    private val place = Place("Camping", 45.1015, 6.0794, id = 3)
    private val parking = GeoPoint(canyonId = 42, type = GeoPointType.PARKING_AVAL, latitude = 44.9853, longitude = 6.1505)
    private val estimate = DriveTime(30, DriveTimeSource.ESTIMATE, 21.0)
    private val route = DriveTime(40, DriveTimeSource.ROUTE, 31.1)
    private val repository = mockk<DriveTimeRepository> {
        coEvery { stored(3, 42, parking) } returns null
        coEvery { route(place, 42, parking) } returns Result.success(route)
    }

    @Test
    fun `the estimate comes at once, then the route`() = runTest {
        val times = GetDriveTimeUseCase(repository, connectivity(online = true))(place, 42, parking).toList()

        assertEquals(listOf(30, 40), times.map { it.minutes })
        assertEquals(listOf(DriveTimeSource.ESTIMATE, DriveTimeSource.ROUTE), times.map { it.source })
    }

    @Test
    fun `offline the estimate stays, a time kept is used as is`() = runTest {
        val offline = GetDriveTimeUseCase(repository, connectivity(online = false))(place, 42, parking).toList()
        assertEquals(listOf(DriveTimeSource.ESTIMATE), offline.map { it.source })
        coVerify(exactly = 0) { repository.route(any(), any(), any()) }

        val manual = DriveTime(55, DriveTimeSource.MANUAL)
        coEvery { repository.stored(3, 42, parking) } returns manual
        assertEquals(listOf(manual), GetDriveTimeUseCase(repository, connectivity(online = true))(place, 42, parking).toList())
        assertEquals(estimate.minutes, offline.single().minutes)
    }

    private fun connectivity(online: Boolean) = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns online }
}
