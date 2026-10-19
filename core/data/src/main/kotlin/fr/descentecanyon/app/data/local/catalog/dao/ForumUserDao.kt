package fr.descentecanyon.app.data.local.catalog.dao

import androidx.room.Dao
import androidx.room.Query
import fr.descentecanyon.app.data.local.catalog.entity.ForumUserEntity

@Dao
interface ForumUserDao {

    @Query("SELECT * FROM forum_users WHERE username = :username")
    suspend fun getByUsername(username: String): ForumUserEntity?

    /**
     * Members whose name contains [normalizedQuery]: the exact name first, then the names starting
     * with it, then a word starting with it, the most active first within each group.
     */
    @Query(
        """
        SELECT * FROM forum_users
        WHERE normalizedUsername LIKE '%' || :normalizedQuery || '%'
        ORDER BY
            CASE
                WHEN normalizedUsername = :normalizedQuery THEN 0
                WHEN normalizedUsername LIKE :normalizedQuery || '%' THEN 1
                WHEN normalizedUsername LIKE '% ' || :normalizedQuery || '%' THEN 2
                ELSE 3
            END,
            debitReportCount + forumPostCount DESC,
            username
        LIMIT :limit
        """,
    )
    suspend fun search(normalizedQuery: String, limit: Int): List<ForumUserEntity>

    /** Those of [usernames] that are members. */
    @Query("SELECT username FROM forum_users WHERE username IN (:usernames)")
    suspend fun findUsernames(usernames: List<String>): List<String>
}
