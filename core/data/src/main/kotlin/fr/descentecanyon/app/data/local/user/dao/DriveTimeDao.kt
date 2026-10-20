package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.user.entity.DriveTimeEntity

@Dao
interface DriveTimeDao {
    @Query("SELECT * FROM drive_times WHERE placeId = :placeId AND canyonId = :canyonId")
    suspend fun get(placeId: Long, canyonId: Int): DriveTimeEntity?

    @Query("SELECT * FROM drive_times WHERE placeId = :placeId")
    suspend fun getFrom(placeId: Long): List<DriveTimeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DriveTimeEntity)

    @Query("DELETE FROM drive_times WHERE placeId = :placeId AND canyonId = :canyonId")
    suspend fun delete(placeId: Long, canyonId: Int)

    @Query("DELETE FROM drive_times WHERE placeId = :placeId")
    suspend fun deleteFrom(placeId: Long)
}
