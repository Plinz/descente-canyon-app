package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    /** Favorite canyon ids, most recently added first. */
    @Query("SELECT canyonId FROM favorites ORDER BY addedAt DESC")
    fun observeIds(): Flow<List<Int>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE canyonId = :canyonId)")
    fun observeIsFavorite(canyonId: Int): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE canyonId = :canyonId")
    suspend fun delete(canyonId: Int)
}
