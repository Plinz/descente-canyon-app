package fr.descentecanyon.app.data.local.catalog.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "forum_users",
    indices = [
        Index(value = ["normalizedUsername"], unique = true),
        Index(value = ["forumUserId"]),
    ],
)
data class ForumUserEntity(
    @PrimaryKey val username: String,
    val normalizedUsername: String,
    val forumUserId: Int? = null,
    val profileUrl: String? = null,
    val source: String,
    val hasForumActivity: Boolean = false,
    val hasDebitActivity: Boolean = false,
    val forumPostCount: Int = 0,
    val debitObservationCount: Int = 0,
    val lastForumPostAt: String? = null,
    val lastForumPostUrl: String? = null,
    val lastDebitObservationAt: String? = null,
    val lastDebitObservationUrl: String? = null,
    val updatedAt: String,
)
