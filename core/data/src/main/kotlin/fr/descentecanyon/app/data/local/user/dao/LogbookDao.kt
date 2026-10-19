package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.descentecanyon.app.data.local.user.entity.LogbookEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogbookDao {
    @Query("SELECT * FROM logbook_entries ORDER BY date DESC, createdAt DESC")
    fun observeAll(): Flow<List<LogbookEntryEntity>>

    @Query("SELECT * FROM logbook_entries ORDER BY date DESC, createdAt DESC")
    suspend fun getAll(): List<LogbookEntryEntity>

    @Query("SELECT * FROM logbook_entries WHERE canyonId = :canyonId ORDER BY date DESC")
    fun observeForCanyon(canyonId: Int): Flow<List<LogbookEntryEntity>>

    @Query("SELECT DISTINCT canyonId FROM logbook_entries")
    fun observeCanyonIds(): Flow<List<Int>>

    @Query("SELECT * FROM logbook_entries WHERE canyonId = :canyonId AND date = :date LIMIT 1")
    suspend fun find(canyonId: Int, date: String): LogbookEntryEntity?

    @Query("SELECT * FROM logbook_entries WHERE id = :id")
    suspend fun findById(id: Long): LogbookEntryEntity?

    @Query("SELECT companions FROM logbook_entries WHERE companions IS NOT NULL AND companions != ''")
    suspend fun getCompanions(): List<String>

    @Upsert
    suspend fun upsert(entry: LogbookEntryEntity)

    /** Replaces [removedId] (an outing moved onto the day of another one) by [entry]. */
    @Transaction
    suspend fun replace(removedId: Long, entry: LogbookEntryEntity) {
        delete(removedId)
        upsert(entry)
    }

    @Query("DELETE FROM logbook_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM logbook_entries")
    suspend fun deleteAll()
}
