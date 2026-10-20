package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.user.entity.PlannedOutingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannedOutingDao {
    @Query("SELECT * FROM planned_outings ORDER BY start")
    fun observeAll(): Flow<List<PlannedOutingEntity>>

    @Query("SELECT * FROM planned_outings WHERE id = :id")
    suspend fun get(id: Long): PlannedOutingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlannedOutingEntity): Long

    @Query("DELETE FROM planned_outings WHERE id = :id")
    suspend fun delete(id: Long)

    /** Forgets the outings whose alert time is before [beforeEpochMs]. */
    @Query("DELETE FROM planned_outings WHERE alertAt < :beforeEpochMs")
    suspend fun deleteAlertedBefore(beforeEpochMs: Long)

    @Query("DELETE FROM planned_outings")
    suspend fun deleteAll()
}
