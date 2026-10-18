package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingDebitSubmissionDao {

    /** Reports to send, oldest first: the ones refused by the site wait for the user. */
    @Query("SELECT * FROM pending_debit_submissions WHERE rejectedAt IS NULL ORDER BY createdAt ASC")
    suspend fun getSendable(): List<PendingDebitSubmissionEntity>

    /** Every stored report, refused ones included. */
    @Query("SELECT * FROM pending_debit_submissions ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingDebitSubmissionEntity>>

    @Query("SELECT COUNT(*) FROM pending_debit_submissions WHERE rejectedAt IS NULL")
    fun observeCount(): Flow<Int>

    @Query("UPDATE pending_debit_submissions SET failedAttempts = failedAttempts + 1 WHERE id = :id")
    suspend fun recordFailedAttempt(id: Long)

    @Query("UPDATE pending_debit_submissions SET rejectedAt = :rejectedAt, rejectionReason = :reason WHERE id = :id")
    suspend fun markRejected(id: Long, rejectedAt: Long, reason: String?)

    @Query("UPDATE pending_debit_submissions SET rejectedAt = NULL, rejectionReason = NULL WHERE id = :id")
    suspend fun clearRejection(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(submission: PendingDebitSubmissionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(submissions: List<PendingDebitSubmissionEntity>)

    @Query("DELETE FROM pending_debit_submissions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
