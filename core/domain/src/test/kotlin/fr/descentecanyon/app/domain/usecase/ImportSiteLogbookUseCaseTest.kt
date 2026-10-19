package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.SiteLogbookImport
import fr.descentecanyon.app.domain.model.SiteObservation
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.SiteAccountRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSiteLogbookUseCaseTest {

    private val recorded = mutableListOf<LogbookEntry>()
    private val logbookRepository = mockk<LogbookRepository> {
        // The outing of 30 July is already in the logbook.
        coEvery { record(any()) } answers {
            val entry = firstArg<LogbookEntry>()
            recorded += entry
            entry.date != LocalDate.of(2026, 7, 30)
        }
    }
    private val siteAccountRepository = mockk<SiteAccountRepository>()
    private val useCase = ImportSiteLogbookUseCase(siteAccountRepository, logbookRepository)

    @Test
    fun `descents go to the logbook with flow and comments, flows only looked at are left out`() = runTest {
        coEvery { siteAccountRepository.fetchMyObservations() } returns Result.success(
            listOf(
                observation(199338, 22146, "2026-07-30", publicRemark = "Approche 1h10", personalComment = "Avec Paul"),
                observation(195489, 2283, "2026-05-25", descended = false, level = NiveauDebit.CRUE),
                observation(198438, 21688, "2026-07-14", level = NiveauDebit.CORRECT),
                observation(190000, null, "2026-06-01"),
            ),
        )

        assertEquals(Result.success(SiteLogbookImport(descents = 2, added = 1, completed = 1)), useCase())

        assertEquals(
            LogbookEntry(
                canyonId = 22146,
                canyonName = "Canyon 22146",
                date = LocalDate.of(2026, 7, 30),
                note = "Avec Paul\n\nApproche 1h10",
                debitLevel = NiveauDebit.GROS,
                source = LogbookSource.SITE,
                siteObservationId = 199338,
            ),
            recorded.first(),
        )
        assertEquals(listOf(22146, 21688), recorded.map { it.canyonId })
        assertEquals(null, recorded.last().note)
    }

    @Test
    fun `without a session nothing is recorded`() = runTest {
        coEvery { siteAccountRepository.fetchMyObservations() } returns Result.failure(AppException(AppError.AuthenticationRequired))

        assertEquals(true, useCase().isFailure)
        coVerify(exactly = 0) { logbookRepository.record(any()) }
    }

    private fun observation(
        id: Long,
        canyonId: Int?,
        date: String,
        descended: Boolean = true,
        level: NiveauDebit = NiveauDebit.GROS,
        publicRemark: String? = null,
        personalComment: String? = null,
    ) = SiteObservation(
        id = id,
        canyonId = canyonId,
        canyonName = "Canyon $canyonId",
        date = LocalDate.parse(date),
        isDescended = descended,
        debitLevel = level,
        publicRemark = publicRemark,
        personalComment = personalComment,
    )
}
