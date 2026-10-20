package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.OutingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Outings kept in memory, ids given in order. */
internal class FakeOutingRepository : OutingRepository {
    val outings = MutableStateFlow<List<PlannedOuting>>(emptyList())
    private var nextId = 1L

    override fun observeAll() = outings

    override suspend fun get(id: Long): PlannedOuting? = outings.value.firstOrNull { it.id == id }

    override suspend fun save(outing: PlannedOuting): Long {
        val id = outing.id.takeIf { it > 0 } ?: nextId++
        outings.update { list -> (list.filterNot { it.id == id } + outing.copy(id = id)).sortedBy { it.start } }
        return id
    }

    override suspend fun delete(id: Long) = outings.update { list -> list.filterNot { it.id == id } }

    override suspend fun clear() = outings.update { emptyList() }
}
