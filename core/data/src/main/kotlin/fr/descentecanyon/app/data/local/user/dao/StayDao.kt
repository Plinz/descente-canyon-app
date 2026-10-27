package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.user.entity.StayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StayDao {
    @Query("SELECT * FROM stays ORDER BY start, id")
    fun observeAll(): Flow<List<StayEntity>>

    @Query("SELECT * FROM stays ORDER BY start, id")
    suspend fun getAll(): List<StayEntity>

    @Query("SELECT * FROM stays WHERE id = :id")
    suspend fun get(id: Long): StayEntity?

    @Query("SELECT id FROM stays WHERE placeId = :placeId")
    suspend fun idsAt(placeId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StayEntity): Long

    @Query("DELETE FROM stays WHERE id = :id")
    suspend fun delete(id: Long)
}
