package fr.descentecanyon.app.data.local.catalog.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.catalog.entity.CanyonTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanyonTrackDao {

    @Query("SELECT * FROM canyon_tracks WHERE canyonId = :canyonId ORDER BY isPrimary DESC, name ASC, trackId ASC")
    suspend fun getByCanyonId(canyonId: Int): List<CanyonTrackEntity>

    @Query("SELECT * FROM canyon_tracks WHERE canyonId = :canyonId ORDER BY isPrimary DESC, name ASC, trackId ASC")
    fun observeByCanyonId(canyonId: Int): Flow<List<CanyonTrackEntity>>
}
