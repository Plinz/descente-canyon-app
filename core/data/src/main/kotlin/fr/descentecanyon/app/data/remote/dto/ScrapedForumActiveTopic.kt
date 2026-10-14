package fr.descentecanyon.app.data.remote.dto

data class ScrapedForumActiveTopic(
    val topicId: Int,
    val title: String,
    val forumId: Int?,
    val forumName: String,
    val replyCount: Int,
    val viewCount: Int,
    val lastAuthor: String? = null,
    val lastPostedAtText: String,
    val lastPostedAtEpochMs: Long? = null,
    val topicUrl: String,
    val lastMessageUrl: String,
)
