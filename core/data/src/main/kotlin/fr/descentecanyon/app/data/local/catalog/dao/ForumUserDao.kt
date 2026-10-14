package fr.descentecanyon.app.data.local.catalog.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.catalog.entity.ForumUserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ForumUserDao {

    @Query("SELECT * FROM forum_users WHERE normalizedUsername = :normalizedUsername")
    suspend fun getByNormalizedUsername(normalizedUsername: String): ForumUserEntity?

    @Query(
        "SELECT * FROM forum_users WHERE normalizedUsername LIKE '%' || :query || '%' OR username LIKE '%' || :query || '%' ORDER BY username LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int = 50): List<ForumUserEntity>
}
