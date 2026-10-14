package fr.descentecanyon.app.data.remote.dto

data class ScrapedForumUserPost(
    val postId: Int,
    val topicId: Int,
    val forumId: Int? = null,
    val topicTitle: String,
    val author: String,
    val postedAtText: String,
    val postUrl: String,
)
