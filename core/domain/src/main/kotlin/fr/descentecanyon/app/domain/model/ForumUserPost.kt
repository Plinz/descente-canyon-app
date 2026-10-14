package fr.descentecanyon.app.domain.model

data class ForumUserPost(
    val postId: Int,
    val topicId: Int,
    val forumId: Int? = null,
    val topicTitle: String,
    val author: String,
    val postedAtText: String,
    val postUrl: String,
)
