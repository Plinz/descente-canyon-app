package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingDebitSubmissionDao {

    @Query("SELECT * FROM pending_debit_submissions ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingDebitSubmissionEntity>

    @Query("SELECT * FROM pending_debit_submissions ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingDebitSubmissionEntity>>

    @Query("SELECT COUNT(*) FROM pending_debit_submissions")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(submission: PendingDebitSubmissionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(submissions: List<PendingDebitSubmissionEntity>)

    @Query("DELETE FROM pending_debit_submissions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
