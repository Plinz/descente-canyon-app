package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.CanyonNoteDao
import fr.descentecanyon.app.data.local.user.entity.CanyonNoteEntity
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class CanyonNotesRepositoryImpl @Inject constructor(private val canyonNoteDao: CanyonNoteDao) : CanyonNotesRepository {

    override fun observeNote(canyonId: Int): Flow<String?> = canyonNoteDao.observe(canyonId)

    override suspend fun saveNote(canyonId: Int, text: String) {
        val note = text.trim()
        if (note.isEmpty()) {
            canyonNoteDao.delete(canyonId)
        } else {
            canyonNoteDao.upsert(CanyonNoteEntity(canyonId = canyonId, text = note, updatedAt = System.currentTimeMillis()))
        }
    }
}
