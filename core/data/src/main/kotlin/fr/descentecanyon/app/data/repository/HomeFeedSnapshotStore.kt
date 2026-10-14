package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.NiveauDebit
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class HomeFeedSnapshotStore @Inject constructor(private val keyValueDao: KeyValueDao) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun readLatestDebits(limit: Int): CachedItems<Debit> {
        val payload = keyValueDao.get(LATEST_DEBITS_KEY) ?: return CachedItems()
        return runCatching {
            val snapshot = json.decodeFromString<LatestDebitsSnapshotDto>(payload)
            CachedItems(
                items = snapshot.items.take(limit).map { item ->
                    Debit(
                        canyonId = item.canyonId,
                        canyonNom = item.canyonNom,
                        date = LocalDate.parse(item.date),
                        niveau = runCatching { NiveauDebit.valueOf(item.niveau) }.getOrDefault(NiveauDebit.INCONNU),
                        auteur = item.auteur,
                        isDescended = item.isDescended,
                        waterTemperature = item.waterTemperature,
                        airTemperature = item.airTemperature,
                        commentaire = item.commentaire,
                    )
                },
                syncedAtEpochMs = snapshot.syncedAtEpochMs,
            )
        }.getOrDefault(CachedItems())
    }

    suspend fun writeLatestDebits(debits: List<Debit>, syncedAtEpochMs: Long) {
        val payload = json.encodeToString(
            LatestDebitsSnapshotDto(
                syncedAtEpochMs = syncedAtEpochMs,
                items = debits.map { debit ->
                    LatestDebitItemDto(
                        canyonId = debit.canyonId,
                        canyonNom = debit.canyonNom,
                        date = debit.date.toString(),
                        niveau = debit.niveau.name,
                        auteur = debit.auteur,
                        isDescended = debit.isDescended,
                        waterTemperature = debit.waterTemperature,
                        airTemperature = debit.airTemperature,
                        commentaire = debit.commentaire,
                    )
                },
            ),
        )
        keyValueDao.put(KeyValueEntity(LATEST_DEBITS_KEY, payload))
    }

    suspend fun readActiveTopics(limit: Int): CachedItems<ForumActiveTopic> {
        val payload = keyValueDao.get(ACTIVE_TOPICS_KEY) ?: return CachedItems()
        return runCatching {
            val snapshot = json.decodeFromString<ActiveTopicsSnapshotDto>(payload)
            CachedItems(
                items = snapshot.items.take(limit).map { item ->
                    ForumActiveTopic(
                        topicId = item.topicId,
                        title = item.title,
                        forumId = item.forumId,
                        forumName = item.forumName,
                        replyCount = item.replyCount,
                        viewCount = item.viewCount,
                        lastAuthor = item.lastAuthor,
                        lastPostedAtText = item.lastPostedAtText,
                        lastPostedAtEpochMs = item.lastPostedAtEpochMs,
                        topicUrl = item.topicUrl,
                        lastMessageUrl = item.lastMessageUrl,
                    )
                },
                syncedAtEpochMs = snapshot.syncedAtEpochMs,
            )
        }.getOrDefault(CachedItems())
    }

    suspend fun writeActiveTopics(topics: List<ForumActiveTopic>, syncedAtEpochMs: Long) {
        val payload = json.encodeToString(
            ActiveTopicsSnapshotDto(
                syncedAtEpochMs = syncedAtEpochMs,
                items = topics.map { topic ->
                    ActiveTopicItemDto(
                        topicId = topic.topicId,
                        title = topic.title,
                        forumId = topic.forumId,
                        forumName = topic.forumName,
                        replyCount = topic.replyCount,
                        viewCount = topic.viewCount,
                        lastAuthor = topic.lastAuthor,
                        lastPostedAtText = topic.lastPostedAtText,
                        lastPostedAtEpochMs = topic.lastPostedAtEpochMs,
                        topicUrl = topic.topicUrl,
                        lastMessageUrl = topic.lastMessageUrl,
                    )
                },
            ),
        )
        keyValueDao.put(KeyValueEntity(ACTIVE_TOPICS_KEY, payload))
    }

    suspend fun readForumCategories(): CachedItems<ForumCategory> {
        val payload = keyValueDao.get(FORUM_CATEGORIES_KEY) ?: return CachedItems()
        return runCatching {
            val snapshot = json.decodeFromString<ForumCategoriesSnapshotDto>(payload)
            CachedItems(
                items = snapshot.items.map { item ->
                    ForumCategory(
                        forumId = item.forumId,
                        forumName = item.forumName,
                        forumUrl = item.forumUrl,
                    )
                },
                syncedAtEpochMs = snapshot.syncedAtEpochMs,
            )
        }.getOrDefault(CachedItems())
    }

    suspend fun writeForumCategories(categories: List<ForumCategory>, syncedAtEpochMs: Long) {
        val payload = json.encodeToString(
            ForumCategoriesSnapshotDto(
                syncedAtEpochMs = syncedAtEpochMs,
                items = categories.map { category ->
                    ForumCategoryItemDto(
                        forumId = category.forumId,
                        forumName = category.forumName,
                        forumUrl = category.forumUrl,
                    )
                },
            ),
        )
        keyValueDao.put(KeyValueEntity(FORUM_CATEGORIES_KEY, payload))
    }

    suspend fun readSelectedFeedType(): HomeFeedType? = keyValueDao.get(SELECTED_FEED_KEY)
        ?.takeIf { it.isNotBlank() }
        ?.let { raw -> runCatching { HomeFeedType.valueOf(raw) }.getOrNull() }

    suspend fun writeSelectedFeedType(type: HomeFeedType) {
        keyValueDao.put(KeyValueEntity(SELECTED_FEED_KEY, type.name))
    }

    @Serializable
    private data class LatestDebitsSnapshotDto(val syncedAtEpochMs: Long, val items: List<LatestDebitItemDto>)

    @Serializable
    private data class LatestDebitItemDto(
        val canyonId: Int,
        val canyonNom: String? = null,
        val date: String,
        val niveau: String,
        val auteur: String? = null,
        val isDescended: Boolean? = null,
        val waterTemperature: String? = null,
        val airTemperature: String? = null,
        val commentaire: String? = null,
    )

    @Serializable
    private data class ActiveTopicsSnapshotDto(val syncedAtEpochMs: Long, val items: List<ActiveTopicItemDto>)

    @Serializable
    private data class ActiveTopicItemDto(
        val topicId: Int,
        val title: String,
        val forumId: Int? = null,
        val forumName: String,
        val replyCount: Int,
        val viewCount: Int,
        val lastAuthor: String? = null,
        val lastPostedAtText: String,
        val lastPostedAtEpochMs: Long? = null,
        val topicUrl: String,
        val lastMessageUrl: String,
    )

    @Serializable
    private data class ForumCategoriesSnapshotDto(val syncedAtEpochMs: Long, val items: List<ForumCategoryItemDto>)

    @Serializable
    private data class ForumCategoryItemDto(val forumId: Int? = null, val forumName: String, val forumUrl: String)

    private companion object {
        const val LATEST_DEBITS_KEY = "home.latest_debits.snapshot"
        const val ACTIVE_TOPICS_KEY = "home.active_topics.snapshot"
        const val FORUM_CATEGORIES_KEY = "home.forum_categories.snapshot"
        const val SELECTED_FEED_KEY = "home.selected_feed_type"
    }
}
