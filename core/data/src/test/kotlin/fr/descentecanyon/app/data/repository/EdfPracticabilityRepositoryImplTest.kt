package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.network.ApiHttpClient
import fr.descentecanyon.app.data.remote.edf.EdfAmenagementDto
import fr.descentecanyon.app.data.remote.edf.EdfChartDto
import fr.descentecanyon.app.data.remote.edf.EdfDataPointDto
import fr.descentecanyon.app.data.remote.edf.EdfGraphDto
import fr.descentecanyon.app.data.remote.edf.EdfLimitDto
import fr.descentecanyon.app.data.remote.edf.EdfPracticabilityDto
import fr.descentecanyon.app.data.remote.edf.EdfPracticabilityRemoteSource
import fr.descentecanyon.app.domain.model.EdfPracticabilityCondition
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdfPracticabilityRepositoryImplTest {

    @Test
    fun `returns no reference for unsupported canyon`() {
        val repository = EdfPracticabilityRepositoryImpl(
            remoteSource = fakeRemoteSource(practicabilityDto()),
            ioDispatcher = StandardTestDispatcher(),
        )

        assertNull(repository.getReference(999999))
    }

    @Test
    fun `parses mapped canyon practicability status`() = runTest {
        val repository = EdfPracticabilityRepositoryImpl(
            remoteSource = fakeRemoteSource(practicabilityDto()),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.getStatus(21002)

        assertTrue(result.isSuccess)
        val status = result.getOrThrow()
        assertEquals(40746706L, status.practicabilityId)
        assertEquals("Canyon du Tech", status.title)
        assertEquals("Lac le Tech", status.amenagementTitle)
        assertEquals(EdfPracticabilityCondition.APPROPRIATE, status.state)
        assertEquals(1204.77, status.lastSample?.value ?: 0.0, 0.001)
        assertEquals(EdfPracticabilityCondition.APPROPRIATE, status.lastSample?.condition)
        assertEquals(2, status.thresholds.size)
        assertEquals(
            "https://mariviereetmoi.edf.fr/#/map/place/PRACTICABILITY/40746706",
            status.sourceUrl,
        )
        assertEquals(
            "Le barrage du Tech se situe en amont du canyon du Tech.",
            status.description,
        )
    }

    private fun practicabilityDto(): EdfPracticabilityDto = EdfPracticabilityDto(
        id = 40746706,
        title = "Canyon du Tech",
        description = "<p>Le barrage du Tech se situe en amont du canyon du Tech.</p>",
        amenagement = EdfAmenagementDto(
            id = 352,
            title = "Lac le Tech",
        ),
        charts = listOf(
            EdfChartDto(
                type = "WATER_LEVEL",
                activity = "CANYONING",
                graph = EdfGraphDto(
                    limits = listOf(
                        EdfLimitDto(
                            condition = "APPROPRIATE",
                            min = 1150.0,
                            max = 1209.15,
                        ),
                        EdfLimitDto(
                            condition = "NOT_APPROPRIATE",
                            min = 1209.15,
                            max = 1214.0,
                        ),
                    ),
                    datas = listOf(
                        EdfDataPointDto(
                            value = 1204.64,
                            dateTime = "2026-04-03T15:00:00Z",
                            condition = "APPROPRIATE",
                        ),
                        EdfDataPointDto(
                            value = 1204.77,
                            dateTime = "2026-04-03T18:00:00Z",
                            condition = "APPROPRIATE",
                        ),
                    ),
                ),
            ),
        ),
        hasPublishedEventInProgress = false,
        state = "APPROPRIATE",
    )

    private fun fakeRemoteSource(response: EdfPracticabilityDto): EdfPracticabilityRemoteSource =
        object : EdfPracticabilityRemoteSource(mockk<ApiHttpClient>()) {
            override suspend fun fetchPracticability(practicabilityId: Long): EdfPracticabilityDto = response
        }
}
