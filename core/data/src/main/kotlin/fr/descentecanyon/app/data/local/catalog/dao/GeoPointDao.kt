package fr.descentecanyon.app.data.local.catalog.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.catalog.entity.GeoPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeoPointDao {

    @Query("SELECT * FROM geo_points")
    suspend fun getAll(): List<GeoPointEntity>

    @Query("SELECT * FROM geo_points WHERE canyonId = :canyonId")
    suspend fun getByCanyonId(canyonId: Int): List<GeoPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<GeoPointEntity>)

    @Query("DELETE FROM geo_points WHERE canyonId = :canyonId")
    suspend fun deleteByCanyonId(canyonId: Int)
}
