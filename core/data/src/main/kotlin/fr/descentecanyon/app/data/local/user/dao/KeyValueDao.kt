package fr.descentecanyon.app.data.local.user.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity

@Dao
interface KeyValueDao {
    @Query("SELECT value FROM key_values WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: KeyValueEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putAllIfAbsent(entries: List<KeyValueEntity>)
}
