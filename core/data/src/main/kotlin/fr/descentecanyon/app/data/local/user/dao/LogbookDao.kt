package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import fr.descentecanyon.app.data.local.user.entity.LogbookEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogbookDao {
    @Query("SELECT * FROM logbook_entries ORDER BY date DESC, createdAt DESC")
    fun observeAll(): Flow<List<LogbookEntryEntity>>

    @Query("SELECT * FROM logbook_entries WHERE canyonId = :canyonId ORDER BY date DESC")
    fun observeForCanyon(canyonId: Int): Flow<List<LogbookEntryEntity>>

    @Query("SELECT * FROM logbook_entries WHERE canyonId = :canyonId AND date = :date LIMIT 1")
    suspend fun find(canyonId: Int, date: String): LogbookEntryEntity?

    @Upsert
    suspend fun upsert(entry: LogbookEntryEntity)

    @Query("DELETE FROM logbook_entries WHERE id = :id")
    suspend fun delete(id: Long)
}
