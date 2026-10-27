package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.repository.StayRepository
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveStayUseCaseTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val clock = Clock.fixed(LocalDate.of(2026, 7, 1).atStartOfDay(zone).toInstant(), zone)
    private val outings = FakeOutingRepository()
    private val stays = object : StayRepository {
        val kept = MutableStateFlow<List<Stay>>(emptyList())

        override fun observeAll() = kept

        override suspend fun get(id: Long) = kept.value.firstOrNull { it.id == id }

        override suspend fun save(stay: Stay): Long {
            val id = stay.id.takeIf { it > 0 } ?: (kept.value.size + 1L)
            kept.value = kept.value.filterNot { it.id == id } + stay.copy(id = id)
            return id
        }

        override suspend fun delete(id: Long) {
            kept.value = kept.value.filterNot { it.id == id }
        }
    }
    private val useCase = SaveStayUseCase(stays, outings, clock)

    @Test
    fun `new dates leave out the outings of the days they drop, which stay planned`() = runTest {
        val id = useCase(Stay(placeId = 7, start = LocalDate.of(2026, 7, 12), end = LocalDate.of(2026, 7, 18)))
        outings.save(outingOn(LocalDate.of(2026, 7, 13), "Gardée", stayId = id))
        outings.save(outingOn(LocalDate.of(2026, 7, 17), "Sortie", stayId = id))
        // Of another stay, the same days: not touched.
        outings.save(outingOn(LocalDate.of(2026, 7, 17), "Ailleurs", stayId = 99))

        val shorter = Stay(placeId = 7, start = LocalDate.of(2026, 7, 12), end = LocalDate.of(2026, 7, 15), id = id)
        assertEquals(listOf("Sortie"), useCase.outingsLeftOut(shorter, outings.outings.value).map { it.canyonName })
        assertEquals(id, useCase(shorter))

        assertEquals(
            mapOf("Gardée" to id, "Sortie" to null, "Ailleurs" to 99L),
            outings.outings.value.associate { it.canyonName to it.stayId },
        )
        assertEquals(shorter, stays.kept.value.single())
    }

    private fun outingOn(date: LocalDate, name: String, stayId: Long): PlannedOuting {
        val start = date.atTime(9, 0).atZone(zone).toInstant()
        return PlannedOuting(
            canyonId = name.length,
            canyonName = name,
            start = start,
            expectedBack = start.plus(Duration.ofHours(4)),
            alertAt = start.plus(Duration.ofHours(6)),
            choices = OutingChoices(date, LocalTime.of(9, 0)),
            tracked = false,
            stayId = stayId,
        )
    }
}
