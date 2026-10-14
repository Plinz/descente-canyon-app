package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.user.entity.DailyWeatherEntity

@Dao
interface DailyWeatherDao {

    @Query(
        """
        SELECT * FROM daily_weather
        WHERE canyonId = :canyonId AND date BETWEEN :startDate AND :endDate
        ORDER BY date ASC
        """,
    )
    suspend fun getByCanyonIdAndDateRange(canyonId: Int, startDate: String, endDate: String): List<DailyWeatherEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<DailyWeatherEntity>)

    @Query("DELETE FROM daily_weather WHERE canyonId = :canyonId AND date < :minDate")
    suspend fun deleteBefore(canyonId: Int, minDate: String)
}
