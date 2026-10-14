package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.catalog.dao.ForumUserDao
import fr.descentecanyon.app.data.local.catalog.entity.ForumUserEntity
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.repository.ForumUserRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForumUserRepositoryImpl @Inject constructor(private val forumUserDao: ForumUserDao) : ForumUserRepository {

    override suspend fun search(query: String, limit: Int): List<ForumUser> {
        val normalizedQuery = query.normalizeForSearch()
        if (normalizedQuery.isBlank()) return emptyList()
        return forumUserDao.search(normalizedQuery, limit).map(ForumUserEntity::toDomain)
    }

    override suspend fun getByNormalizedUsername(username: String): ForumUser? =
        forumUserDao.getByNormalizedUsername(username.normalizeForSearch())?.toDomain()
}

private fun ForumUserEntity.toDomain() = ForumUser(
    username = username,
    normalizedUsername = normalizedUsername,
    forumUserId = forumUserId,
    profileUrl = profileUrl,
    hasForumActivity = hasForumActivity,
    hasDebitActivity = hasDebitActivity,
    forumPostCount = forumPostCount,
    debitObservationCount = debitObservationCount,
    lastForumPostAt = lastForumPostAt,
    lastForumPostUrl = lastForumPostUrl,
    lastDebitObservationAt = lastDebitObservationAt,
    lastDebitObservationUrl = lastDebitObservationUrl,
)
