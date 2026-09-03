package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.descentecanyon.app.data.local.entity.CanyonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanyonDao {

    @Query("SELECT * FROM canyons WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<CanyonEntity>

    @Query("SELECT * FROM canyons WHERE id = :id")
    suspend fun getById(id: Int): CanyonEntity?

    @Query("SELECT * FROM canyons")
    fun observeAll(): Flow<List<CanyonEntity>>

    @Query("SELECT * FROM canyons WHERE isOffline = 1")
    fun getOfflineCanyons(): Flow<List<CanyonEntity>>

    @Query("SELECT * FROM canyons WHERE isFavorite = 1")
    fun getFavorites(): Flow<List<CanyonEntity>>

    @Query("SELECT COUNT(*) FROM canyons")
    suspend fun count(): Int

    @Query("SELECT id FROM canyons WHERE isFavorite = 1")
    suspend fun getFavoriteIds(): List<Int>

    @Query("SELECT isFavorite FROM canyons WHERE id = :canyonId")
    fun isFavorite(canyonId: Int): Flow<Boolean?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(canyons: List<CanyonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(canyon: CanyonEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(canyon: CanyonEntity): Long

    @Update
    suspend fun update(canyon: CanyonEntity)

    @Query("UPDATE canyons SET isOffline = :isOffline WHERE id = :canyonId")
    suspend fun setOffline(canyonId: Int, isOffline: Boolean)

    @Query("UPDATE canyons SET isFavorite = :isFavorite, favoriteAddedAt = :addedAt WHERE id = :canyonId")
    suspend fun setFavorite(canyonId: Int, isFavorite: Boolean, addedAt: Long?)

    @Query("UPDATE canyons SET isOffline = 0")
    suspend fun clearOfflineFlags()

    @Query("DELETE FROM canyons")
    suspend fun clearAll()

    @Query("SELECT * FROM canyons WHERE pays = :pays")
    fun getByCountry(pays: String): Flow<List<CanyonEntity>>

    @Query("SELECT * FROM canyons WHERE departement = :departement")
    fun getByDepartement(departement: String): Flow<List<CanyonEntity>>
}
