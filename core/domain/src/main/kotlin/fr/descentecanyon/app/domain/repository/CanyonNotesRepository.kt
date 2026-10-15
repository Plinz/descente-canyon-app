package fr.descentecanyon.app.domain.repository

import kotlinx.coroutines.flow.Flow

/** Private notes the user writes about canyons; they stay on the device. */
interface CanyonNotesRepository {
    fun observeNote(canyonId: Int): Flow<String?>

    /** Saves the note of a canyon; a blank text deletes it. */
    suspend fun saveNote(canyonId: Int, text: String)
}
