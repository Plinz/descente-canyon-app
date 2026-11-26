package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.BackendConfig
import fr.descentecanyon.app.data.remote.backend.BackendClient
import fr.descentecanyon.app.data.remote.backend.BackendTestServer
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.AppServerState
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSheet
import fr.descentecanyon.app.domain.model.CanyonSourceType
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.ProposalKind
import fr.descentecanyon.app.domain.model.ProposalRefusal
import fr.descentecanyon.app.domain.model.ProposalRejectionReason
import fr.descentecanyon.app.domain.model.ProposalStatus
import fr.descentecanyon.app.domain.model.ServerFeatures
import fr.descentecanyon.app.domain.model.SheetPoint
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.testutil.TestDatabases
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The proposal fixtures are written by the Python server (server/tools/make_proposal_fixtures.py). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CanyonProposalRepositoryImplTest {

    private val backend = BackendTestServer()
    private val database = TestDatabases.user()
    private val canyonRepository = mockk<CanyonRepository>()
    private val serverState = MutableStateFlow(AppServerState(isConfigured = true))
    private val appServer = mockk<AppServerRepository> { every { observeState() } returns serverState }

    private fun repository(client: BackendClient = backend.client, config: BackendConfig = backend.config) = CanyonProposalRepositoryImpl(
        client = client,
        config = config,
        installKeys = InstallKeyStore(database.keyValueDao()),
        keyValueDao = database.keyValueDao(),
        canyonRepository = canyonRepository,
        appServer = appServer,
        json = backend.json,
    )

    @After
    fun tearDown() {
        backend.shutdown()
        database.close()
    }

    /** The sheet behind proposal-request.json. */
    private val sheet = CanyonSheet(
        nom = "Ravin des Essais",
        pays = "France",
        departement = "Isère",
        cotation = CotationRating(raw = "", vertical = 4, aquatic = 2, engagement = 3),
        cordeMin = 35,
        tempsApproche = "45min",
        navette = "néant",
        descente = "Une C30 en entrée, puis des toboggans.",
        isForbidden = true,
        points = listOf(
            SheetPoint(GeoPointType.ENTREE, 45.1, 5.7),
            SheetPoint(GeoPointType.SORTIE, 45.105, 5.71, "Pont"),
        ),
        // Blank fields stay out of the request.
        region = "  ",
        tempsRetour = "",
    )

    @Test
    fun `a new canyon is sent as the server takes it, with the installation key, and kept in my proposals`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/proposal.json"), code = 201))
        val repository = repository()

        val proposal = repository.propose(sheet, canyonId = null).getOrThrow()

        val request = backend.server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/canyon-proposals", request.path)
        assertEquals(43, request.getHeader("X-Install-Key")?.length)
        assertEquals(
            Json.parseToJsonElement(BackendTestServer.resource("backend/proposal-request.json")),
            Json.parseToJsonElement(request.body.readUtf8()),
        )
        assertEquals(ProposalKind.NEW, proposal.kind)
        assertEquals(ProposalStatus.PENDING, proposal.status)
        assertEquals("Ravin des Essais", proposal.name)
        assertNull(proposal.canyonId)
        assertEquals(listOf(proposal), repository.observeMine().first())
    }

    @Test
    fun `my proposals are read with what the operator decided`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/proposals-mine.json")))
        val repository = repository()

        val mine = repository.refreshMine().getOrThrow()

        assertEquals(
            listOf(ProposalStatus.PENDING, ProposalStatus.REJECTED, ProposalStatus.APPROVED, ProposalStatus.PENDING),
            mine.map {
                it.status
            },
        )
        val edit = mine.first()
        assertEquals(ProposalKind.EDIT, edit.kind)
        assertEquals(1_000_000, edit.canyonId)
        val declined = mine[1]
        assertEquals(ProposalRejectionReason.DUPLICATE, declined.reason)
        assertEquals("Déjà ajouté : Ravin des Essais", declined.note)
        assertTrue(declined.decidedAt != null)
        assertEquals(1_000_000, mine[2].canyonId)
        assertEquals("/v1/canyon-proposals/mine", backend.server.takeRequest().path)
        assertEquals(mine, repository.observeMine().first())
    }

    @Test
    fun `a refused sheet names the refusal and the field`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/error-proposal-invalid.json"), code = 422))

        val error = repository().propose(sheet, canyonId = null).exceptionOrNull()!!.toAppError()

        assertEquals(AppError.ProposalRefused(ProposalRefusal.INVALID, "nom"), error)
    }

    @Test
    fun `too many sends is a refusal of the proposal, not of a track`() = runTest {
        backend.server.enqueue(json("""{"error":"RATE_LIMITED"}""", code = 429))

        val error = repository().propose(sheet, canyonId = null).exceptionOrNull()!!.toAppError()

        assertEquals(AppError.ProposalRefused(ProposalRefusal.RATE_LIMITED), error)
    }

    @Test
    fun `a withdrawn proposal is marked so at once`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/proposals-mine.json")))
        backend.server.enqueue(MockResponse().setResponseCode(204))
        val repository = repository()
        val pending = repository.refreshMine().getOrThrow().first()
        backend.server.takeRequest()

        repository.withdraw(pending).getOrThrow()

        val request = backend.server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/canyon-proposals/${pending.id}", request.path)
        assertEquals(ProposalStatus.WITHDRAWN, repository.observeMine().first().first().status)
    }

    @Test
    fun `nothing is sent while the person keeps the server off or the operator closed proposals`() = runTest {
        serverState.value = AppServerState(isConfigured = true, isEnabled = false)
        assertEquals(false, repository().observeAvailable().first())
        assertEquals(AppError.DataUnavailable, repository().propose(sheet, null).exceptionOrNull()!!.toAppError())
        serverState.value = AppServerState(isConfigured = true, features = ServerFeatures(canyonProposals = false))
        assertEquals(AppError.DataUnavailable, repository().propose(sheet, null).exceptionOrNull()!!.toAppError())
        assertEquals(0, backend.server.requestCount)
    }

    @Test
    fun `nothing is sent for a sheet of the site, nor without a server`() = runTest {
        val noServer = BackendClient(OkHttpClient(), backend.json, BackendConfig.NONE, Dispatchers.Unconfined)

        assertEquals(AppError.DataUnavailable, repository().propose(sheet, canyonId = 21).exceptionOrNull()!!.toAppError())
        assertEquals(
            AppError.DataUnavailable,
            repository(noServer, BackendConfig.NONE).propose(sheet, null).exceptionOrNull()!!.toAppError(),
        )
        assertEquals(emptyList<Any>(), repository(noServer, BackendConfig.NONE).refreshMine().getOrThrow())
        assertEquals(0, backend.server.requestCount)
    }

    @Test
    fun `the place and the canyons around come from the catalog`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                item(1, "Proche", 45.11, 5.7, region = "Auvergne-Rhône-Alpes", departement = "Isère", massif = "Vercors"),
                item(2, "Moins proche", 45.13, 5.7),
                item(3, "Loin", 46.5, 5.7),
                item(4, "Sans position", null, null),
            ),
        )
        val repository = repository()

        val place = repository.placeNear(45.1, 5.7)!!
        assertEquals("Isère", place.departement)
        assertEquals("Vercors", place.massif)
        assertNull(repository.placeNear(48.0, 2.0))
        assertEquals(listOf(1, 2), repository.canyonsNear(45.1, 5.7, maxDistanceKm = 5.0).map { it.summary.id })
    }

    @Test
    fun `a change starts from the catalog sheet in the site's formats`() = runTest {
        val canyon = Canyon(
            id = 1_000_003,
            nom = "Clue du Test",
            nomComplet = "Clue du Test",
            pays = "France",
            commune = "Saint-Test",
            cotation = "v3a2II",
            tempsApproche = "??",
            tempsDescente = "2h",
            navette = "5.0km",
            url = "",
            sourceType = CanyonSourceType.APP,
        )
        coEvery { canyonRepository.getCanyonPreview(1_000_003) } returns Result.success(
            CanyonDetail(
                canyon = canyon,
                descente = "Toboggans.",
                geoPoints = listOf(
                    GeoPoint(canyonId = 1_000_003, type = GeoPointType.ENTREE, latitude = 45.1, longitude = 5.7, title = null),
                ),
            ),
        )

        val sheet = repository().sheetOf(1_000_003).getOrThrow()

        assertEquals("", sheet.nomComplet)
        assertEquals("v3a2II", CanyonSheet.cotationText(sheet.cotation))
        assertEquals("", sheet.tempsApproche)
        assertEquals("2h", sheet.tempsDescente)
        assertEquals("5", CanyonSheet.kilometresOf(sheet.navette))
        assertEquals(listOf(SheetPoint(GeoPointType.ENTREE, 45.1, 5.7)), sheet.points)
        assertTrue(sheet.problems().isEmpty())
    }

    private fun item(
        id: Int,
        name: String,
        latitude: Double?,
        longitude: Double?,
        region: String? = null,
        departement: String? = null,
        massif: String? = null,
    ) = CanyonSearchItem(
        id = id,
        nom = name,
        nomComplet = name,
        pays = "France",
        region = region,
        departement = departement,
        massif = massif,
        cotation = "",
        cotationRating = CotationRating(""),
        representativeLat = latitude,
        representativeLng = longitude,
        url = "",
        searchableText = name,
        normalizedNom = name,
        normalizedNomComplet = name,
    )

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
