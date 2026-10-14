package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.ForumUserPost
import kotlinx.coroutines.flow.Flow

interface ForumRepository {

    fun getActiveTopics(limit: Int = 10): Flow<Result<List<ForumActiveTopic>>>

    suspend fun getCachedActiveTopics(limit: Int = 10): CachedItems<ForumActiveTopic>

    suspend fun refreshActiveTopics(limit: Int = 10): Result<CachedItems<ForumActiveTopic>>

    suspend fun getCachedCategories(): CachedItems<ForumCategory>

    suspend fun refreshCategories(): Result<CachedItems<ForumCategory>>

    suspend fun refreshUserPosts(authorId: Int): Result<List<ForumUserPost>>
}
