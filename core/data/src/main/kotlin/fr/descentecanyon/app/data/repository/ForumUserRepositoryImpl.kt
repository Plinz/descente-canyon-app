package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.catalog.dao.ForumUserDao
import fr.descentecanyon.app.data.local.catalog.entity.ForumUserEntity
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.model.MemberDebitReport
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.repository.ForumUserRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForumUserRepositoryImpl @Inject constructor(private val forumUserDao: ForumUserDao) : ForumUserRepository {

    override suspend fun search(query: String, limit: Int): List<ForumUser> {
        val normalizedQuery = query.normalizeForSearch()
        if (normalizedQuery.isBlank()) return emptyList()
        return forumUserDao.search(normalizedQuery, limit).map(ForumUserEntity::toDomain)
    }

    override suspend fun getByUsername(username: String): ForumUser? = forumUserDao.getByUsername(username)?.toDomain()

    override suspend fun findMembers(names: Collection<String>): Set<String> = names
        .filter { it.isNotBlank() }
        .distinct()
        // SQLite caps the number of bound arguments of a query.
        .chunked(MAX_NAMES_PER_QUERY)
        .flatMap { chunk -> forumUserDao.findUsernames(chunk) }
        .toSet()

    private companion object {
        const val MAX_NAMES_PER_QUERY = 500
    }
}

private fun ForumUserEntity.toDomain() = ForumUser(
    username = username,
    forumUserId = forumUserId,
    debitReportCount = debitReportCount,
    forumPostCount = forumPostCount,
    photoCount = photoCount,
    canyonSheetContributionCount = canyonSheetContributionCount,
    lastDebitReport = lastDebitReportDate?.toLocalDateOrNull()?.let { date ->
        MemberDebitReport(date = date, canyonId = lastDebitReportCanyonId, canyonName = lastDebitReportCanyonName)
    },
    lastForumPostDate = lastForumPostDate?.toLocalDateOrNull(),
    lastForumPostUrl = lastForumPostUrl,
    forumPostsUrl = if (forumPostCount > 0) forumUserId?.let(SiteUrls::forumUserPosts) ?: SiteUrls.forumUserPostsByName(username) else null,
)

private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(take(10)) }.getOrNull()
