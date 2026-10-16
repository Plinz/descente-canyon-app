package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.BibliographyEntry
import fr.descentecanyon.app.domain.model.BibliographyKind
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonWatershed
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.EdfPracticabilityCondition
import fr.descentecanyon.app.domain.model.EdfPracticabilityReference
import fr.descentecanyon.app.domain.model.EdfPracticabilitySample
import fr.descentecanyon.app.domain.model.GeoBounds
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.Regulation
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.EdfPracticabilityRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.domain.repository.RecentCanyonsRepository
import fr.descentecanyon.app.domain.repository.WeatherRepository
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDebitPredictionsUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDetailUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonEdfPracticabilityUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.domain.usecase.GetNearbyCanyonsUseCase
import fr.descentecanyon.app.domain.usecase.ToggleFavoriteUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import fr.descentecanyon.app.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class CanyonDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val canyonRepository = mockk<CanyonRepository>()
    private val favoritesRepository = mockk<FavoritesRepository>()
    private val photoRepository = mockk<PhotoRepository>()
    private val debitRepository = mockk<DebitRepository>()
    private val debitPredictionRepository = mockk<DebitPredictionRepository>()
    private val weatherRepository = mockk<WeatherRepository>()
    private val edfPracticabilityRepository = mockk<EdfPracticabilityRepository>()
    private val connectivityObserver = mockk<ConnectivityObserver>()
    private val notificationCenterRepository = mockk<NotificationCenterRepository>()
    private val recentCanyonsRepository = mockk<RecentCanyonsRepository>(relaxUnitFun = true)
    private val personalNote = MutableStateFlow<String?>(null)
    private val canyonNotesRepository = mockk<CanyonNotesRepository>(relaxUnitFun = true) {
        every { observeNote(42) } returns personalNote
    }
    private val logbookRepository = mockk<fr.descentecanyon.app.domain.repository.LogbookRepository>(relaxUnitFun = true) {
        every { observeForCanyon(42) } returns flowOf(emptyList())
    }
    private val getCanyonPreviewUseCase = GetCanyonPreviewUseCase(canyonRepository)
    private val getCanyonDetailUseCase = GetCanyonDetailUseCase(canyonRepository)
    private val getCanyonWeatherUseCase = GetCanyonWeatherUseCase(weatherRepository)
    private val getCanyonEdfPracticabilityUseCase = GetCanyonEdfPracticabilityUseCase(edfPracticabilityRepository)
    private val getCanyonDebitPredictionsUseCase = GetCanyonDebitPredictionsUseCase(debitPredictionRepository)
    private val toggleFavoriteUseCase = ToggleFavoriteUseCase(favoritesRepository)
    private val downloadPhotoForOfflineUseCase = DownloadPhotoForOfflineUseCase(photoRepository)

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `preview loads before full detail`() = runTest {
        stubBaseState()

        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(
            detail().copy(canyon = detail().canyon.copy(nom = "Preview")),
        )
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())

        val viewModel = createViewModel()

        advanceUntilIdle()

        assertEquals("Riolan", viewModel.uiState.value.canyonDetail?.canyon?.nom)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `full detail keeps bibliography and regulations after loading completes`() = runTest {
        stubBaseState()

        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(
            detail().copy(canyon = detail().canyon.copy(nom = "Preview")),
        )
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())

        val viewModel = createViewModel()

        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.canyonDetail?.bibliography?.size)
        assertEquals(1, viewModel.uiState.value.canyonDetail?.regulations?.size)
        assertNotNull(viewModel.uiState.value.weather)
        assertNotNull(viewModel.uiState.value.predictions)
        assertFalse(viewModel.uiState.value.isLoadingPhotos)
        assertFalse(viewModel.uiState.value.isLoadingDebits)
        assertFalse(viewModel.uiState.value.isLoadingWeather)
        assertFalse(viewModel.uiState.value.isLoadingPredictions)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `download photo updates local path and transient message`() = runTest {
        stubBaseState()

        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        coEvery { photoRepository.downloadPhoto(8) } returns Result.success("/tmp/photo.jpg")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.downloadPhoto(8)
        advanceUntilIdle()

        assertEquals("/tmp/photo.jpg", viewModel.uiState.value.canyonDetail?.photos?.first()?.localPath)
        assertEquals(UiText.res(R.string.photo_downloaded), viewModel.uiState.value.transientMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `weather failure keeps canyon detail available`() = runTest {
        stubBaseState()

        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        coEvery {
            weatherRepository.getCanyonWeather(any())
        } returns Result.failure(SocketTimeoutException("Request timeout has expired"))

        val viewModel = createViewModel()

        advanceUntilIdle()

        assertEquals("Riolan", viewModel.uiState.value.canyonDetail?.canyon?.nom)
        assertEquals(UiText.res(R.string.weather_load_error_network), viewModel.uiState.value.weatherError)
        assertFalse(viewModel.uiState.value.isLoadingWeather)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `weather failure shows the last downloaded forecast when there is one`() = runTest {
        stubBaseState()

        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.failure(SocketTimeoutException("timeout"))
        coEvery { weatherRepository.getCachedCanyonWeather(42) } returns weather().copy(isFromCache = true)

        val viewModel = createViewModel()

        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.weather?.isFromCache)
        assertEquals(null, viewModel.uiState.value.weatherError)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `offline the cached forecast is shown without trying the network`() = runTest {
        stubBaseState()
        every { connectivityObserver.observe() } returns flowOf(false)
        every { connectivityObserver.isCurrentlyOnline() } returns false

        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        coEvery { weatherRepository.getCachedCanyonWeather(42) } returns weather().copy(isFromCache = true)

        val viewModel = createViewModel()

        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.weather?.isFromCache)
        coVerify(exactly = 0) { weatherRepository.getCanyonWeather(any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `nearby canyons come with their last known report`() = runTest {
        stubBaseState()

        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())

        val viewModel = createViewModel()

        advanceUntilIdle()

        val nearby = viewModel.uiState.value.nearbyCanyons
        assertEquals(listOf(7), nearby.map { it.summary.id })
        assertEquals(NiveauDebit.CORRECT, nearby.single().summary.dernierDebit)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `prediction timeout shows friendly message instead of raw timeout`() = runTest {
        stubBaseState()

        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        coEvery {
            debitPredictionRepository.getPredictions(any())
        } returns Result.failure(SocketTimeoutException("Request timeout has expired"))

        val viewModel = createViewModel()

        advanceUntilIdle()

        assertEquals(
            UiText.res(R.string.prediction_load_error_network),
            viewModel.uiState.value.predictionError,
        )
        assertFalse(viewModel.uiState.value.isLoadingPredictions)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `detail refresh failure keeps preview content available`() = runTest {
        stubBaseState()

        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.failure(IllegalStateException("boom"))

        val viewModel = createViewModel()

        advanceUntilIdle()

        assertEquals("Riolan", viewModel.uiState.value.canyonDetail?.canyon?.nom)
        assertEquals(UiText.res(R.string.canyon_detail_load_error), viewModel.uiState.value.transientMessage)
        assertEquals(null, viewModel.uiState.value.error)
        assertNotNull(viewModel.uiState.value.weather)
        assertNotNull(viewModel.uiState.value.predictions)
        assertFalse(viewModel.uiState.value.isRefreshingDetail)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `edf status loads when a mapping exists`() = runTest {
        stubBaseState()

        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        every { edfPracticabilityRepository.getReference(42) } returns EdfPracticabilityReference(
            practicabilityId = 40746706,
            sourceUrl = "https://mariviereetmoi.edf.fr/#/map/place/PRACTICABILITY/40746706",
        )
        coEvery { edfPracticabilityRepository.getStatus(42) } returns Result.success(edfStatus())

        val viewModel = createViewModel()

        advanceUntilIdle()

        assertEquals(EdfPracticabilityCondition.APPROPRIATE, viewModel.uiState.value.edfStatus?.state)
        assertFalse(viewModel.uiState.value.isLoadingEdfStatus)
        assertEquals(null, viewModel.uiState.value.edfStatusError)
    }

    @Test
    fun `base detail merge preserves an already loaded watershed`() {
        val merged = mergeBaseCanyonDetail(
            newDetail = detail(),
            currentDetail = detail().copy(watershed = watershed()),
        )

        assertEquals(12.5, merged.watershed?.areaKm2)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `opening a canyon records it once in the recently viewed list`() = runTest {
        stubBaseState()
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())

        createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { recentCanyonsRepository.recordView(42) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `personal note is shown and saved`() = runTest {
        stubBaseState()
        coEvery { canyonRepository.getCanyonPreview(42) } returns Result.success(detail())
        coEvery { canyonRepository.getCanyonDetail(42) } returns Result.success(detail())
        val viewModel = createViewModel()

        personalNote.value = "Corde de 2 x 40 m suffisante"
        advanceUntilIdle()
        viewModel.savePersonalNote("Parking payant")
        advanceUntilIdle()

        assertEquals("Corde de 2 x 40 m suffisante", viewModel.uiState.value.personalNote)
        coVerify { canyonNotesRepository.saveNote(42, "Parking payant") }
    }

    private fun createViewModel(): CanyonDetailViewModel = CanyonDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
        getCanyonPreviewUseCase = getCanyonPreviewUseCase,
        getCanyonDetailUseCase = getCanyonDetailUseCase,
        toggleFavoriteUseCase = toggleFavoriteUseCase,
        canyonRepository = canyonRepository,
        favoritesRepository = favoritesRepository,
        notificationCenterRepository = notificationCenterRepository,
        connectivityObserver = connectivityObserver,
        conditions = CanyonConditionsController(
            getCanyonWeatherUseCase = getCanyonWeatherUseCase,
            getCanyonEdfPracticabilityUseCase = getCanyonEdfPracticabilityUseCase,
            getCanyonDebitPredictionsUseCase = getCanyonDebitPredictionsUseCase,
            connectivityObserver = connectivityObserver,
        ),
        media = CanyonMediaController(
            photoRepository = photoRepository,
            debitRepository = debitRepository,
            downloadPhotoForOfflineUseCase = downloadPhotoForOfflineUseCase,
            connectivityObserver = connectivityObserver,
        ),
        recentCanyonsRepository = recentCanyonsRepository,
        canyonNotesRepository = canyonNotesRepository,
        getNearbyCanyonsUseCase = GetNearbyCanyonsUseCase(canyonRepository, debitRepository),
        logbookRepository = logbookRepository,
    )

    private fun stubBaseState() {
        every { canyonRepository.observeWatershed(42) } returns flowOf(null)
        coEvery { canyonRepository.getWatershedGeometry(42) } returns null
        coEvery { weatherRepository.getCanyonWeather(any()) } returns Result.success(weather())
        every { edfPracticabilityRepository.getReference(42) } returns null
        coEvery { debitPredictionRepository.getPredictions(any()) } returns Result.success(predictions())
        every { photoRepository.observePhotos(42) } returns flowOf(detail().photos)
        coEvery { photoRepository.refreshPhotos(42) } returns Result.success(detail().photos)
        coEvery { photoRepository.downloadPhoto(8) } returns Result.success("/tmp/photo.jpg")
        every { debitRepository.getDebitsForCanyon(42) } returns flowOf(Result.success(detail().debits))
        coEvery { debitRepository.refreshDebits(42) } returns Result.success(detail().debits)
        every { favoritesRepository.isFavorite(42) } returns flowOf(false)
        every { notificationCenterRepository.observeIsCanyonFollowed(42) } returns flowOf(false)
        coEvery { notificationCenterRepository.toggleCanyonFollow(any(), any(), any()) } returns Unit
        every { connectivityObserver.observe() } returns flowOf(true)
        every { connectivityObserver.isCurrentlyOnline() } returns true
        coEvery { weatherRepository.getCachedCanyonWeather(42) } returns null
        coEvery { debitPredictionRepository.getCachedPredictions(42) } returns null
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                searchItem(id = 42, name = "Riolan", latitude = 43.95, longitude = 6.90),
                searchItem(id = 7, name = "Bollinette", latitude = 43.96, longitude = 6.92),
                searchItem(id = 8, name = "Lointain", latitude = 45.50, longitude = 6.90),
            ),
        )
        every { debitRepository.observeLatestKnownDebits(setOf(7)) } returns flowOf(
            mapOf(7 to Debit(canyonId = 7, date = java.time.LocalDate.of(2026, 3, 25), niveau = NiveauDebit.CORRECT)),
        )
    }

    private fun searchItem(id: Int, name: String, latitude: Double, longitude: Double) = CanyonSearchItem(
        id = id,
        nom = name,
        nomComplet = name,
        pays = "France",
        cotation = "v3a3II",
        cotationRating = CotationRating.parse("v3a3II"),
        representativeLat = latitude,
        representativeLng = longitude,
        url = "/canyoning/canyon/$id/$name.html",
        searchableText = name.lowercase(),
        normalizedNom = name.lowercase(),
        normalizedNomComplet = name.lowercase(),
    )

    private fun detail() = CanyonDetail(
        canyon = Canyon(
            id = 42,
            nom = "Riolan",
            nomComplet = "Canyon du Riolan",
            pays = "France",
            commune = "Sigale",
            cotation = "v4a4III",
            url = "/canyoning/canyon/42/riolan.html",
        ),
        photos = listOf(
            CanyonPhoto(
                id = 8,
                canyonId = 42,
                url = "https://example.test/photo.jpg",
                thumbnailUrl = "https://example.test/thumb.jpg",
            ),
        ),
        bibliography = listOf(
            BibliographyEntry(
                id = "biblio-1",
                kind = BibliographyKind.RESOURCE,
                title = "Topo local",
            ),
        ),
        regulations = listOf(
            Regulation(
                id = 7,
                status = "actif",
                title = "Arrete prefectoral",
                textUrl = "https://example.test/reglementation",
            ),
        ),
        debits = listOf(
            Debit(
                canyonId = 42,
                date = java.time.LocalDate.of(2026, 3, 27),
                niveau = NiveauDebit.CORRECT,
                auteur = "alice",
            ),
        ),
    )

    private fun weather() = CanyonWeather(
        target = WeatherTarget(
            latitude = 43.75,
            longitude = 6.25,
            source = WeatherLocationSource.WATERSHED_CENTER,
        ),
        timezone = "UTC",
        fetchedAt = Instant.parse("2026-03-26T12:00:00Z"),
    )

    private fun predictions() = CanyonDebitPredictions(
        target = WeatherTarget(
            latitude = 43.75,
            longitude = 6.25,
            source = WeatherLocationSource.WATERSHED_CENTER,
        ),
        timezone = "UTC",
        fetchedAt = Instant.parse("2026-03-26T12:00:00Z"),
        lookupSource = RuntimeLookupSource.CANYON,
        usedWeatherCache = false,
        policy = DebitPredictionPolicy.SAFETY_FIRST,
        predictions = listOf(
            DailyDebitPrediction(
                date = java.time.LocalDate.of(2026, 3, 26),
                horizonDays = 0,
                level = PredictedDebitLevel.MEDIUM,
                probabilities = mapOf(
                    PredictedDebitLevel.HIGH to 0.31,
                    PredictedDebitLevel.LOW to 0.18,
                    PredictedDebitLevel.MEDIUM to 0.51,
                ),
                highThreshold = 0.29,
            ),
        ),
    )

    private fun edfStatus() = CanyonEdfPracticability(
        practicabilityId = 40746706,
        title = "Canyon du Tech",
        amenagementTitle = "Lac le Tech",
        sourceUrl = "https://mariviereetmoi.edf.fr/#/map/place/PRACTICABILITY/40746706",
        state = EdfPracticabilityCondition.APPROPRIATE,
        lastSample = EdfPracticabilitySample(
            value = 1204.77,
            recordedAt = Instant.parse("2026-04-03T18:00:00Z"),
            condition = EdfPracticabilityCondition.APPROPRIATE,
        ),
        description = "Le risque de déversement est faible.",
    )

    private fun watershed() = CanyonWatershed(
        areaKm2 = 12.5,
        geometryJson = "{\"type\":\"Polygon\",\"coordinates\":[]}",
        bounds = GeoBounds(
            minLongitude = 6.0,
            minLatitude = 43.7,
            maxLongitude = 6.1,
            maxLatitude = 43.8,
        ),
    )
}
