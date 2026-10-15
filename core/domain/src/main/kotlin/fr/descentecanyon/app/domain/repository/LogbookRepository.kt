package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.LogbookEntry
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** The user's logbook of descended canyons, most recent first. */
interface LogbookRepository {
    fun observeAll(): Flow<List<LogbookEntry>>

    fun observeForCanyon(canyonId: Int): Flow<List<LogbookEntry>>

    /** Adds an outing; the same canyon on the same day is recorded once (the note is updated). */
    suspend fun add(canyonId: Int, canyonName: String, date: LocalDate, note: String?)

    suspend fun delete(id: Long)
}
