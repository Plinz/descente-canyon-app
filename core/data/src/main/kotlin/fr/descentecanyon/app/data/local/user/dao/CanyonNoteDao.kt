package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.user.entity.CanyonNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanyonNoteDao {

    @Query("SELECT text FROM canyon_notes WHERE canyonId = :canyonId")
    fun observe(canyonId: Int): Flow<String?>

    @Query("SELECT * FROM canyon_notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CanyonNoteEntity>>

    @Query("SELECT * FROM canyon_notes ORDER BY updatedAt DESC")
    suspend fun getAll(): List<CanyonNoteEntity>

    @Query("SELECT * FROM canyon_notes WHERE canyonId = :canyonId")
    suspend fun get(canyonId: Int): CanyonNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: CanyonNoteEntity)

    @Query("DELETE FROM canyon_notes WHERE canyonId = :canyonId")
    suspend fun delete(canyonId: Int)

    @Query("DELETE FROM canyon_notes")
    suspend fun deleteAll()
}
