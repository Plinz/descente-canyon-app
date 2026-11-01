package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import fr.descentecanyon.app.data.local.user.entity.DebitSendEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DebitSendDao {
    @Query("SELECT * FROM debit_sends ORDER BY sentAt DESC")
    fun observeAll(): Flow<List<DebitSendEntity>>

    @Query("SELECT MAX(sentAt) FROM debit_sends")
    fun observeLastSentAt(): Flow<Long?>

    @Insert
    suspend fun insert(send: DebitSendEntity): Long

    @Query("DELETE FROM debit_sends")
    suspend fun deleteAll()
}
