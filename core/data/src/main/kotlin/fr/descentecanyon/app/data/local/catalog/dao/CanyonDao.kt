package fr.descentecanyon.app.data.local.catalog.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.descentecanyon.app.data.local.catalog.entity.CanyonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanyonDao {

    @Query("SELECT * FROM canyons WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<CanyonEntity>

    @Query("SELECT * FROM canyons WHERE id IN (:ids)")
    fun observeByIds(ids: List<Int>): Flow<List<CanyonEntity>>

    @Query("SELECT * FROM canyons WHERE id = :id")
    suspend fun getById(id: Int): CanyonEntity?

    @Query("SELECT COUNT(*) FROM canyons")
    suspend fun count(): Int

    /** Never REPLACE: it would delete the row first and cascade to its GPS points and links. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(canyon: CanyonEntity): Long

    @Update
    suspend fun update(canyon: CanyonEntity)
}
