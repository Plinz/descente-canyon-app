package fr.descentecanyon.app.ui.proposal

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonPlace
import fr.descentecanyon.app.domain.model.CanyonProposal
import fr.descentecanyon.app.domain.model.CanyonSheet
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.ProposalKind
import fr.descentecanyon.app.domain.model.ProposalStatus
import fr.descentecanyon.app.domain.model.SheetPoint
import fr.descentecanyon.app.domain.model.SheetProblem
import fr.descentecanyon.app.domain.repository.CanyonProposalRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import fr.descentecanyon.app.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanyonProposalViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<CanyonProposalRepository> {
        every { observeAvailable() } returns flowOf(true)
        coEvery { canyonsNear(any(), any(), any()) } returns emptyList()
        coEvery { placeNear(any(), any()) } returns
            CanyonPlace("France", "Auvergne-Rhône-Alpes", "Isère", "Saint-Voisin", "Vercors", "Drac")
    }
    private val entry = SheetPoint(GeoPointType.ENTREE, 45.1, 5.7)

    private fun viewModel(canyonId: Int? = null) = CanyonProposalViewModel(
        SavedStateHandle(if (canyonId == null) mapOf("canyonId" to 0) else mapOf("canyonId" to canyonId)),
        repository,
    )

    @Test
    fun `a canyon searched for in vain is proposed under the name searched`() = runTest {
        val viewModel = CanyonProposalViewModel(SavedStateHandle(mapOf("canyonId" to 0, "name" to " Gorgette amont ")), repository)

        assertEquals("Gorgette amont", viewModel.uiState.value.draft[SheetField.NOM])
        assertEquals(null, viewModel.uiState.value.original)
    }

    @Test
    fun `the first point of any type fills the place of a new canyon, the commune excepted`() = runTest {
        val viewModel = viewModel()
        val parking = SheetPoint(GeoPointType.PARKING_AVAL, 45.1, 5.7)

        viewModel.update(SheetField.MASSIF, "Chartreuse")
        viewModel.savePoint(null, parking)
        advanceUntilIdle()

        val draft = viewModel.uiState.value.draft
        assertEquals("France", draft[SheetField.PAYS])
        assertEquals("Isère", draft[SheetField.DEPARTEMENT])
        assertEquals("Chartreuse", draft[SheetField.MASSIF]) // typed first, kept
        assertEquals("", draft[SheetField.COMMUNE])
        assertEquals(listOf(parking), draft.points)
    }

    @Test
    fun `the place follows the point, except what was typed by hand`() = runTest {
        val viewModel = viewModel()
        viewModel.savePoint(null, entry)
        advanceUntilIdle()
        viewModel.update(SheetField.REGION, "Rhône-Alpes")

        coEvery { repository.placeNear(44.0, 6.0) } returns
            CanyonPlace("France", "Provence-Alpes-Côte d'Azur", "Alpes-de-Haute-Provence", null, "Verdon", null)
        viewModel.savePoint(0, entry.copy(latitude = 44.0, longitude = 6.0))
        advanceUntilIdle()

        val draft = viewModel.uiState.value.draft
        assertEquals("Alpes-de-Haute-Provence", draft[SheetField.DEPARTEMENT])
        assertEquals("Verdon", draft[SheetField.MASSIF])
        assertEquals("", draft[SheetField.BASSIN]) // filled from the first point, none near the second
        assertEquals("Rhône-Alpes", draft[SheetField.REGION]) // typed, kept

        // An exit added later does not move the place: the entry places the canyon.
        coEvery { repository.placeNear(45.5, 6.5) } returns CanyonPlace("France", null, "Savoie", null, "Beaufortain", "Doron")
        viewModel.savePoint(null, SheetPoint(GeoPointType.SORTIE, 45.5, 6.5))
        advanceUntilIdle()
        assertEquals("Alpes-de-Haute-Provence", viewModel.uiState.value.draft[SheetField.DEPARTEMENT])
    }

    @Test
    fun `nothing is sent before the sheet is complete and the licence accepted`() = runTest {
        val viewModel = viewModel()

        viewModel.send()

        val state = viewModel.uiState.value
        assertTrue(state.showProblems)
        assertEquals(setOf(SheetProblem.NAME, SheetProblem.POINT, SheetProblem.DESCRIPTION), state.problems)
        assertEquals(UiText.res(R.string.proposal_fix_problems), state.message)

        viewModel.update(SheetField.NOM, "Ravin des Essais")
        viewModel.update(SheetField.REMARQUES, "Vu depuis le pont.")
        viewModel.savePoint(null, SheetPoint(GeoPointType.PARKING_AMONT, 45.1, 5.7))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.problems.isEmpty())
        viewModel.send()
        assertEquals(UiText.res(R.string.proposal_licence_required), viewModel.uiState.value.message)
        coVerify(exactly = 0) { repository.propose(any(), any()) }
    }

    @Test
    fun `a complete sheet is sent in the site's formats`() = runTest {
        val sent = slot<CanyonSheet>()
        coEvery { repository.propose(capture(sent), null) } returns Result.success(proposal(ProposalKind.NEW))
        val viewModel = viewModel()
        viewModel.update(SheetField.NOM, " Ravin des Essais ")
        viewModel.update(SheetField.DESCENTE, "Toboggans.")
        viewModel.update(SheetField.CORDE_MIN, "35")
        viewModel.update(SheetField.NAVETTE_KM, "2,5")
        viewModel.update(SheetField.TEMPS_APPROCHE, "45min")
        viewModel.setCotation(4, 2, 3)
        viewModel.savePoint(null, entry)
        viewModel.acceptLicence(true)
        advanceUntilIdle()

        viewModel.send()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.sent)
        assertEquals("Ravin des Essais", sent.captured.nom)
        assertEquals(35, sent.captured.cordeMin)
        assertEquals("2.5km", sent.captured.navette)
        assertEquals("v4a2III", CanyonSheet.cotationText(sent.captured.cotation))
        assertEquals("France", sent.captured.pays)
    }

    @Test
    fun `a change starts from the sheet and is not sent unchanged`() = runTest {
        val current = CanyonSheet(
            nom = "Clue du Test",
            pays = "France",
            cotation = CotationRating.parse("v3a2II"),
            descente = "Toboggans.",
            navette = "néant",
            points = listOf(entry),
        )
        coEvery { repository.sheetOf(1_000_000) } returns Result.success(current)
        coEvery { repository.propose(any(), 1_000_000) } returns Result.success(proposal(ProposalKind.EDIT))
        val viewModel = viewModel(1_000_000)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEdit)
        assertEquals("0", viewModel.uiState.value.draft[SheetField.NAVETTE_KM])
        viewModel.acceptLicence(true)
        viewModel.send()
        assertEquals(UiText.res(R.string.proposal_error_no_change), viewModel.uiState.value.message)
        // A change keeps the place as it is: no prefill on a sheet that already has one.
        viewModel.update(SheetField.CORDE_MIN, "40")
        viewModel.send()
        advanceUntilIdle()
        coVerify { repository.propose(match { it.cordeMin == 40 && it.navette == "néant" }, 1_000_000) }
    }

    @Test
    fun `a sheet of the site is never opened for a change`() = runTest {
        val viewModel = viewModel(21)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isEdit)
        assertNull(viewModel.uiState.value.canyonId)
    }

    private fun proposal(kind: ProposalKind) = CanyonProposal(
        id = "p1",
        kind = kind,
        canyonId = null,
        name = "Ravin des Essais",
        status = ProposalStatus.PENDING,
        createdAt = Instant.EPOCH,
    )
}
