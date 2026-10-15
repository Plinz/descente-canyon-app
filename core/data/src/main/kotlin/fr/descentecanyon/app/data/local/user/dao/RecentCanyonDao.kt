package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import fr.descentecanyon.app.data.local.user.entity.RecentCanyonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentCanyonDao {

    @Query("SELECT canyonId FROM recent_canyons ORDER BY viewedAt DESC LIMIT :limit")
    fun observeRecentIds(limit: Int): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentCanyonEntity)

    @Query("DELETE FROM recent_canyons WHERE canyonId NOT IN (SELECT canyonId FROM recent_canyons ORDER BY viewedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    /** Records a view and keeps only the [keep] most recent canyons. */
    @Transaction
    suspend fun recordView(entry: RecentCanyonEntity, keep: Int) {
        upsert(entry)
        trim(keep)
    }

    @Query("DELETE FROM recent_canyons")
    suspend fun clear()
}
