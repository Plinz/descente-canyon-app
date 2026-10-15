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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: CanyonNoteEntity)

    @Query("DELETE FROM canyon_notes WHERE canyonId = :canyonId")
    suspend fun delete(canyonId: Int)
}
