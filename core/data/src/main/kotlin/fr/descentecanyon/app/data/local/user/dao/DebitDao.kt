package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import fr.descentecanyon.app.data.local.user.entity.DebitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DebitDao {

    @Query("SELECT * FROM debits WHERE canyonId = :canyonId ORDER BY date DESC")
    fun getByCanyonId(canyonId: Int): Flow<List<DebitEntity>>

    @Query("SELECT * FROM debits WHERE canyonId IN (:canyonIds) ORDER BY date DESC")
    fun observeForCanyons(canyonIds: List<Int>): Flow<List<DebitEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(debits: List<DebitEntity>)

    @Query("DELETE FROM debits WHERE canyonId = :canyonId")
    suspend fun deleteByCanyonId(canyonId: Int)

    /** Replaces the cached observations of a canyon. */
    @Transaction
    suspend fun replaceForCanyon(canyonId: Int, debits: List<DebitEntity>) {
        deleteByCanyonId(canyonId)
        insertAll(debits)
    }
}
