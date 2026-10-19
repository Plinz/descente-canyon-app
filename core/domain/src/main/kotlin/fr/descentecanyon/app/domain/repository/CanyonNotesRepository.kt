package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonNote
import kotlinx.coroutines.flow.Flow

/** Memos the user writes about canyons; they are never sent to the site. */
interface CanyonNotesRepository {
    fun observeNote(canyonId: Int): Flow<String?>

    /** Every memo, the most recently edited first. */
    fun observeAll(): Flow<List<CanyonNote>>

    /** Saves the memo of a canyon; a blank text deletes it. */
    suspend fun saveNote(canyonId: Int, text: String)

    suspend fun clear()
}
