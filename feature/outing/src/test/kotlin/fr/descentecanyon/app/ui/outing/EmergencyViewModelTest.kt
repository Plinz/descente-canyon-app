package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CompassDirection
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.usecase.FindCanyonsAroundUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import fr.descentecanyon.app.ui.location.DeviceFix
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-07-14T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Europe/Paris"))
    private val outing = MutableStateFlow<PlannedOuting?>(null)
    private val outingRepository = object : OutingRepository {
        override fun observeCurrent() = outing
        override suspend fun save(outing: PlannedOuting) {
            this@EmergencyViewModelTest.outing.value = outing
        }
        override suspend fun clear() {
            outing.value = null
        }
    }

    /** Riolan in France and its neighbour, Ecouges in Switzerland for the numbers, 2.5 km apart. */
    private val canyonRepository = mockk<CanyonRepository> {
        every { observeSearchCatalog() } returns flowOf(listOf(item(42, 43.95, 6.90), item(7, 43.95, 6.931)))
        coEvery { getCanyonPreview(42) } returns Result.success(
            detail(
                42,
                "Riolan",
                "France",
                GeoPoint(canyonId = 42, type = GeoPointType.PARKING_AVAL, latitude = 43.95, longitude = 6.90),
                GeoPoint(canyonId = 42, type = GeoPointType.SORTIE, latitude = 43.96, longitude = 6.90),
                GeoPoint(
                    canyonId = 42,
                    type = GeoPointType.ECHAPPATOIRE,
                    latitude = 43.97,
                    longitude = 6.90,
                    title = "Sentier rive gauche",
                ),
            ),
        )
        coEvery { getCanyonPreview(7) } returns Result.success(
            detail(7, "Ecouges", "Suisse", GeoPoint(canyonId = 7, type = GeoPointType.ENTREE, latitude = 43.95, longitude = 6.931)),
        )
    }

    @Test
    fun `from a canyon page, the canyon and the numbers of its country are shown at once`() = runTest {
        val viewModel = viewModel(canyonId = 7)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Ecouges", state.canyon?.name)
        assertEquals(EmergencyCanyonOrigin.PAGE, state.origin)
        assertEquals(listOf("112", "1414", "144", "117", "118"), state.numbers.map { it.number })
        assertFalse(state.isLocatingCanyon)

        // A position orders nothing else: the canyon of the page stays, 2.5 km away.
        viewModel.onPosition(fix(43.95, 6.90))
        advanceUntilIdle()
        assertEquals("Ecouges", viewModel.uiState.value.canyon?.name)
        assertEquals(2_480.0, viewModel.uiState.value.canyonDistanceMeters!!.toDouble(), 20.0)
        assertTrue(viewModel.uiState.value.isAtCanyon)
        coVerify(exactly = 0) { canyonRepository.getCanyonPreview(42) }

        // Far from the canyon of the page, its points do not place the user.
        viewModel.onPosition(fix(45.0, 6.0))
        assertFalse(viewModel.uiState.value.isAtCanyon)
        assertNull(viewModel.uiState.value.nearestPoint)
    }

    @Test
    fun `the outing under way gives the canyon, with its expected return`() = runTest {
        outing.value =
            PlannedOuting(42, "Riolan", now.minus(Duration.ofHours(2)), now.plus(Duration.ofHours(3)), now.plus(Duration.ofHours(5)))

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(EmergencyCanyonOrigin.OUTING, state.origin)
        assertEquals("Riolan", state.canyon?.name)
        assertEquals(outing.value, state.outing)
        assertEquals(listOf("112", "15", "18", "17"), state.numbers.map { it.number })
        // Without position, the escape route comes first.
        assertEquals(GeoPointType.ECHAPPATOIRE, state.points.first().point.type)
    }

    @Test
    fun `without page nor outing, the canyon around the position is found and can be corrected`() = runTest {
        // An outing planned for tomorrow is not the one under way.
        outing.value = PlannedOuting(42, "Riolan", now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLocatingCanyon)

        // Too vague to tell the canyons apart.
        viewModel.onPosition(fix(43.954, 6.90, accuracy = 2_000f))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLocatingCanyon)
        assertNull(viewModel.uiState.value.canyon)

        // Between the parking (445 m south) and the exit (665 m north): on the route of Riolan.
        viewModel.onPosition(fix(43.954, 6.90))
        advanceUntilIdle()
        var state = viewModel.uiState.value
        assertFalse(state.isLocatingCanyon)
        assertEquals(EmergencyCanyonOrigin.POSITION, state.origin)
        assertEquals("Riolan", state.canyon?.name)
        assertEquals(0, state.canyonDistanceMeters)
        assertEquals(listOf(7), state.otherCanyons.map { it.summary.id })
        // Closest point first, with the way to go.
        assertEquals(GeoPointType.PARKING_AVAL, state.points.first().point.type)
        assertEquals(445, state.nearestPoint?.distanceMeters)
        assertEquals(CompassDirection.S, state.points.first().direction)
        assertEquals(CompassDirection.N, state.points[1].direction)

        viewModel.selectCanyon(7)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals("Ecouges", state.canyon?.name)
        assertEquals(listOf(42), state.otherCanyons.map { it.summary.id })
        assertEquals("1414", state.numbers[1].number)
    }

    @Test
    fun `without canyon, the numbers follow the mobile network country`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onPositionUnavailable()
        viewModel.setNetworkCountry("co")

        var state = viewModel.uiState.value
        assertFalse(state.isLocatingCanyon)
        assertEquals(listOf("123", "119"), state.numbers.map { it.number })
        assertTrue(state.isCountryKnown)

        viewModel.setNetworkCountry("zz")
        state = viewModel.uiState.value
        assertEquals(listOf("112"), state.numbers.map { it.number })
        assertFalse(state.isCountryKnown)
    }

    private fun viewModel(canyonId: Int = 0) = EmergencyViewModel(
        savedStateHandle = SavedStateHandle(mapOf(EmergencyViewModel.CANYON_ID_KEY to canyonId)),
        getCanyonPreviewUseCase = GetCanyonPreviewUseCase(canyonRepository),
        findCanyonsAroundUseCase = FindCanyonsAroundUseCase(canyonRepository),
        outingRepository = outingRepository,
        clock = clock,
    )

    private fun fix(latitude: Double, longitude: Double, accuracy: Float = 8f) = DeviceFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        altitudeMeters = 640.0,
        timeEpochMs = now.toEpochMilli(),
    )

    private fun detail(id: Int, name: String, country: String, vararg points: GeoPoint) = CanyonDetail(
        canyon = Canyon(id = id, nom = name, nomComplet = name, pays = country, commune = "Sigale", cotation = "v4a4III", url = ""),
        geoPoints = points.toList(),
    )

    private fun item(id: Int, latitude: Double, longitude: Double) = CanyonSearchItem(
        id = id,
        nom = "Canyon $id",
        nomComplet = "Canyon $id",
        pays = "France",
        cotation = "v3a3II",
        cotationRating = CotationRating.parse("v3a3II"),
        representativeLat = latitude,
        representativeLng = longitude,
        url = "/canyoning/canyon/$id.html",
        searchableText = "canyon $id",
        normalizedNom = "canyon $id",
        normalizedNomComplet = "canyon $id",
    )
}
