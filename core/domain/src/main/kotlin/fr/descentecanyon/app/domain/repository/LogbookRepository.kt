package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.LogbookEntry
import kotlinx.coroutines.flow.Flow

/** The user's logbook of descended canyons, most recent first; one outing per canyon and day. */
interface LogbookRepository {
    fun observeAll(): Flow<List<LogbookEntry>>

    fun observeForCanyon(canyonId: Int): Flow<List<LogbookEntry>>

    /** Canyons with at least one outing. */
    fun observeDescendedCanyonIds(): Flow<Set<Int>>

    /**
     * Saves an outing written by the user ([LogbookEntry.id] 0 for a new one). An outing already
     * logged in that canyon that day is replaced by this one, keeping what this one leaves empty.
     */
    suspend fun save(entry: LogbookEntry)

    /**
     * Records an outing found elsewhere (back from an outing, report sent, import): it completes the
     * outing of that canyon and day without overwriting it. Returns whether the outing was new.
     */
    suspend fun record(entry: LogbookEntry): Boolean

    suspend fun delete(id: Long)

    /** Companions already typed, the most frequent first. */
    suspend fun knownCompanions(): List<String>

    suspend fun clear()
}
