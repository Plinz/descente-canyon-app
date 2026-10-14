package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.local.catalog.CatalogDatabase
import fr.descentecanyon.app.data.local.catalog.entity.CanyonEntity
import fr.descentecanyon.app.data.local.user.UserDatabase
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appErrorOrNull
import fr.descentecanyon.app.testutil.FakeSiteHttpClient
import fr.descentecanyon.app.testutil.SitePages
import fr.descentecanyon.app.testutil.TestDatabases
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CanyonRepositoryDetailTest {

    private val canyonId = 2186
    private val siteClient = FakeSiteHttpClient()
    private var online = true
    private lateinit var catalog: CatalogDatabase
    private lateinit var user: UserDatabase
    private lateinit var repository: CanyonRepositoryImpl

    @Before
    fun setUp() {
        catalog = TestDatabases.catalog()
        user = TestDatabases.user()
        val localStore = CanyonLocalStore(
            canyonDao = catalog.canyonDao(),
            canyonTrackDao = catalog.canyonTrackDao(),
            geoPointDao = catalog.geoPointDao(),
            bibliographyDao = catalog.bibliographyDao(),
            regulationDao = catalog.regulationDao(),
            watershedDao = catalog.watershedDao(),
            debitDao = user.debitDao(),
            photoDao = user.photoDao(),
            representativePointSelector = RepresentativePointSelector(),
        )
        repository = CanyonRepositoryImpl(
            catalogDatabase = catalog,
            canyonDao = catalog.canyonDao(),
            searchIndexDao = catalog.searchIndexDao(),
            watershedDao = catalog.watershedDao(),
            localStore = localStore,
            scraper = CanyonScraper(siteClient),
            favoritesRepository = FavoritesRepositoryImpl(user.favoriteDao(), catalog.canyonDao()),
            connectivityObserver = object : ConnectivityObserver {
                override fun observe(): Flow<Boolean> = flowOf(online)
                override fun isCurrentlyOnline(): Boolean = online
            },
        )
    }

    @After
    fun tearDown() {
        catalog.close()
        user.close()
    }

    @Test
    fun `a recent catalog detail is served without network`() = runTest {
        catalog.canyonDao().insertIgnore(catalogCanyon(lastUpdated = System.currentTimeMillis()))

        val detail = repository.getCanyonDetail(canyonId).getOrThrow()

        assertEquals("Catalogue", detail.canyon.nom)
        assertTrue(siteClient.gets.isEmpty())
    }

    @Test
    fun `a stale detail is refreshed from the site and keeps catalog-only fields`() = runTest {
        catalog.canyonDao().insertIgnore(catalogCanyon(lastUpdated = 0))
        serveCanyonPages()

        val detail = repository.getCanyonDetail(canyonId).getOrThrow()

        assertEquals("Valouse", detail.canyon.nom)
        assertEquals("v4a1III", detail.canyon.cotation)
        assertEquals("Calcaire", detail.geologie)
        assertTrue(detail.canyon.isForbidden)
        assertTrue(detail.geoPoints.isNotEmpty())
        val stored = catalog.canyonDao().getById(canyonId)!!
        assertTrue(System.currentTimeMillis() - stored.lastUpdated < 60_000)
    }

    @Test
    fun `a stale detail is shown as is while offline`() = runTest {
        catalog.canyonDao().insertIgnore(catalogCanyon(lastUpdated = 0))
        online = false

        assertEquals("Catalogue", repository.getCanyonDetail(canyonId).getOrThrow().canyon.nom)
        assertTrue(siteClient.gets.isEmpty())
    }

    @Test
    fun `an unreadable page never overwrites the catalog`() = runTest {
        catalog.canyonDao().insertIgnore(catalogCanyon(lastUpdated = 0))
        siteClient.onGet(SiteUrls.canyon(canyonId), SitePages.anonymous("<p>Nouvelle mise en page</p>"))
        siteClient.onGet(SiteUrls.canyonDescription(canyonId), SitePages.anonymous())
        siteClient.onGet(SiteUrls.canyonMap(canyonId), SitePages.anonymous())

        val detail = repository.getCanyonDetail(canyonId).getOrThrow()

        assertEquals("Catalogue", detail.canyon.nom)
        assertEquals("Catalogue", catalog.canyonDao().getById(canyonId)?.nom)
    }

    @Test
    fun `a canyon missing from the catalog is fetched, stored and indexed`() = runTest {
        serveCanyonPages()

        val detail = repository.getCanyonDetail(canyonId).getOrThrow()

        assertEquals("Valouse", detail.canyon.nom)
        assertEquals(listOf(canyonId), repository.observeSearchCatalog().first().map { it.id })
    }

    @Test
    fun `a missing canyon cannot be loaded offline`() = runTest {
        siteClient.onGetFailure(SiteUrls.canyon(canyonId), java.net.UnknownHostException("offline"))

        assertEquals(AppError.Offline, repository.getCanyonDetail(canyonId).appErrorOrNull())
    }

    @Test
    fun `the search catalog flags favorites from the user database`() = runTest {
        serveCanyonPages()
        repository.getCanyonDetail(canyonId).getOrThrow()
        user.favoriteDao().insert(FavoriteEntity(canyonId))

        assertTrue(repository.observeSearchCatalog().first().single().isFavorite)
    }

    @Test
    fun `merge keeps the catalog values the scraper did not read`() {
        val existing = catalogCanyon(lastUpdated = 0).copy(tempsApproche = "45min", interet = 3f)
        val scraped = CanyonEntity(
            id = canyonId, nom = "Valouse", nomComplet = "", pays = "", commune = "", cotation = "",
            url = "", tempsApproche = " ", interet = null, lastUpdated = 99,
        )

        val merged = mergeScrapedCanyon(existing, scraped)

        assertEquals("Valouse", merged.nom)
        assertEquals("Catalogue complet", merged.nomComplet)
        assertEquals("45min", merged.tempsApproche)
        assertEquals(3f, merged.interet)
        assertEquals("Calcaire", merged.geologie)
        assertEquals(99, merged.lastUpdated)
    }

    private fun serveCanyonPages() {
        siteClient.onGet(SiteUrls.canyon(canyonId), fixture("canyon_summary.html"))
        siteClient.onGet(SiteUrls.canyonDescription(canyonId), fixture("canyon_description.html"))
        siteClient.onGet(SiteUrls.canyonMap(canyonId), fixture("canyon_carte.html"))
    }

    private fun catalogCanyon(lastUpdated: Long) = CanyonEntity(
        id = canyonId,
        nom = "Catalogue",
        nomComplet = "Catalogue complet",
        pays = "France",
        commune = "La Burbanche",
        cotation = "v3a1II",
        url = "https://www.descente-canyon.com/canyoning/canyon/$canyonId/Valouse.html",
        geologie = "Calcaire",
        isForbidden = true,
        lastUpdated = lastUpdated,
    )

    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResource("html/$name")).readText()
}
