package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.ForumUserPost
import fr.descentecanyon.app.domain.repository.ForumRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Singleton
class FakeForumRepository @Inject constructor() : ForumRepository {
    override fun getActiveTopics(limit: Int): Flow<Result<List<ForumActiveTopic>>> = flowOf(Result.success(emptyList()))

    override suspend fun getCachedActiveTopics(limit: Int): CachedItems<ForumActiveTopic> = CachedItems()

    override suspend fun refreshActiveTopics(limit: Int): Result<CachedItems<ForumActiveTopic>> = Result.success(CachedItems())

    override suspend fun getCachedCategories(): CachedItems<ForumCategory> = CachedItems()

    override suspend fun refreshCategories(): Result<CachedItems<ForumCategory>> = Result.success(CachedItems())

    override suspend fun refreshUserPosts(username: String, authorId: Int?): Result<List<ForumUserPost>> = Result.success(emptyList())
}
