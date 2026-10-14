package fr.descentecanyon.app.domain.model

data class ForumUser(
    val username: String,
    val normalizedUsername: String,
    val forumUserId: Int? = null,
    val profileUrl: String? = null,
    val hasForumActivity: Boolean = false,
    val hasDebitActivity: Boolean = false,
    val forumPostCount: Int = 0,
    val debitObservationCount: Int = 0,
    val lastForumPostAt: String? = null,
    val lastForumPostUrl: String? = null,
    val lastDebitObservationAt: String? = null,
    val lastDebitObservationUrl: String? = null,
)
