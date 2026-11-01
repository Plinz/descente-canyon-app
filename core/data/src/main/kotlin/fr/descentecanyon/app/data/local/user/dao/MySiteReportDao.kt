package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import fr.descentecanyon.app.data.local.user.entity.MySiteReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MySiteReportDao {
    @Query("SELECT * FROM my_site_reports")
    fun observeAll(): Flow<List<MySiteReportEntity>>

    /** The copy of the site's list becomes exactly [reports]. */
    @Transaction
    suspend fun replaceAll(reports: List<MySiteReportEntity>) {
        deleteAll()
        insertAll(reports)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reports: List<MySiteReportEntity>)

    @Query("DELETE FROM my_site_reports")
    suspend fun deleteAll()
}
