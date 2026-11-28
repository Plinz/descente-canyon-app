package fr.descentecanyon.app.data.repository

import android.os.Build
import fr.descentecanyon.app.data.AppBuildInfo
import fr.descentecanyon.app.data.BackendConfig
import fr.descentecanyon.app.data.local.catalog.toInstantOrNull
import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.remote.backend.BackendClient
import fr.descentecanyon.app.data.remote.backend.FeedbackContactDto
import fr.descentecanyon.app.data.remote.backend.FeedbackDto
import fr.descentecanyon.app.data.remote.backend.FeedbackListDto
import fr.descentecanyon.app.data.remote.backend.FeedbackRequestDto
import fr.descentecanyon.app.data.remote.backend.FeedbackTechnicalDto
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.isTransient
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.FeedbackContact
import fr.descentecanyon.app.domain.model.FeedbackDraft
import fr.descentecanyon.app.domain.model.FeedbackKind
import fr.descentecanyon.app.domain.model.FeedbackLimits
import fr.descentecanyon.app.domain.model.FeedbackMessage
import fr.descentecanyon.app.domain.model.FeedbackRefusal
import fr.descentecanyon.app.domain.model.FeedbackSendResult
import fr.descentecanyon.app.domain.model.FeedbackStatus
import fr.descentecanyon.app.domain.model.FeedbackTechnicalInfo
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.FeedbackRepository
import fr.descentecanyon.app.domain.repository.SiteAccountRepository
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Sends the messages kept on the phone once the network is back (WorkManager in the app module). */
interface FeedbackOutboxScheduler {
    fun scheduleSend()
}

/**
 * The idea box. A message goes to the app's server with the installation key; without network it is
 * kept on the phone and leaves as soon as the network is back. The list of the installation's
 * messages, with the replies, is kept to show it offline.
 */
@Singleton
class FeedbackRepositoryImpl @Inject constructor(
    private val client: BackendClient,
    private val config: BackendConfig,
    private val installKeys: InstallKeyStore,
    private val keyValueDao: KeyValueDao,
    private val appServer: AppServerRepository,
    private val siteAccount: SiteAccountRepository,
    private val sessionManager: SessionManager,
    private val buildInfo: AppBuildInfo,
    private val scheduler: FeedbackOutboxScheduler,
    private val json: Json,
    private val clock: Clock,
) : FeedbackRepository {

    /** One send at a time: the screen and the background job could send the same queued message twice. */
    private val sending = Mutex()

    override fun observeAvailable(): Flow<Boolean> = appServer.observeState().map { it.feedbackAvailable }.distinctUntilChanged()

    private suspend fun canSend(): Boolean = config.isConfigured && observeAvailable().first()

    override fun observeMine(): Flow<List<FeedbackMessage>> = combine(
        keyValueDao.observe(KEY_OUTBOX),
        keyValueDao.observe(KEY_MINE),
        keyValueDao.observe(KEY_SEEN),
    ) { outbox, mine, seen ->
        val seenReplies = decodeSeen(seen)
        decodeOutbox(outbox).sortedByDescending { it.createdAtMs }.mapNotNull { it.toDomainOrNull() } +
            decodeList(mine).feedback.mapNotNull { it.toDomainOrNull(seenReplies) }
    }.distinctUntilChanged()

    override suspend fun refreshMine(): Result<List<FeedbackMessage>> = suspendRunCatching {
        if (config.isConfigured && appServer.observeState().first().isActive) {
            saveMine(client.myFeedback(installKeys.get()))
        }
        observeMine().first()
    }

    override suspend fun send(draft: FeedbackDraft): Result<FeedbackSendResult> = suspendRunCatching {
        val length = FeedbackLimits.length(draft.text)
        if (length < FeedbackLimits.TEXT_MIN) appError(AppError.FeedbackRefused(FeedbackRefusal.TEXT_TOO_SHORT))
        if (length > FeedbackLimits.TEXT_MAX) appError(AppError.FeedbackRefused(FeedbackRefusal.TEXT_TOO_LONG))
        if (!canSend()) appError(AppError.DataUnavailable)
        val request = draft.toRequest()
        sending.withLock {
            val failure = submitting { remember(client.submitFeedback(request, installKeys.get())) }
                ?: return@withLock FeedbackSendResult.SENT
            // Refused by the server: told now. A network failure: kept, it leaves later.
            if (!failure.toAppError().isTransient()) throw failure
            val queued = QueuedFeedback(UUID.randomUUID().toString(), request, clock.millis())
            saveOutbox(decodeOutbox(keyValueDao.get(KEY_OUTBOX)) + queued)
            scheduler.scheduleSend()
            FeedbackSendResult.QUEUED
        }
    }

    override suspend fun sendQueued(): Result<Unit> = suspendRunCatching {
        // Switched off meanwhile: they stay on the phone until the server is back on.
        if (!canSend()) return@suspendRunCatching
        sending.withLock {
            for (queued in decodeOutbox(keyValueDao.get(KEY_OUTBOX)).sortedBy { it.createdAtMs }) {
                val failure = submitting { remember(client.submitFeedback(queued.request, installKeys.get())) }
                if (failure != null && failure.toAppError().isTransient()) throw failure
                // Sent, or refused for good (too long, blocked...): it no longer waits.
                saveOutbox(decodeOutbox(keyValueDao.get(KEY_OUTBOX)).filterNot { it.localId == queued.localId })
            }
        }
    }

    override suspend fun delete(message: FeedbackMessage): Result<Unit> = suspendRunCatching {
        sending.withLock {
            if (message.isQueued) {
                saveOutbox(decodeOutbox(keyValueDao.get(KEY_OUTBOX)).filterNot { it.localId == message.id })
                return@withLock
            }
            val failure = submitting { client.deleteFeedback(message.id, installKeys.get()) }
            // Already gone from the server (deleted by the operator, closed for a year): gone here too.
            if (failure != null && failure.toAppError() != AppError.NotFound) throw failure
            saveMine(FeedbackListDto(decodeList(keyValueDao.get(KEY_MINE)).feedback.filterNot { it.id == message.id }))
        }
    }

    override suspend fun markRepliesSeen() {
        val replies = decodeList(keyValueDao.get(KEY_MINE)).feedback.mapNotNull { it.replyKey() }.toSet()
        if (replies != decodeSeen(keyValueDao.get(KEY_SEEN))) {
            keyValueDao.put(KeyValueEntity(KEY_SEEN, json.encodeToString(SetSerializer(String.serializer()), replies)))
        }
    }

    override fun technicalInfo(): FeedbackTechnicalInfo = FeedbackTechnicalInfo(
        appVersion = buildInfo.versionName,
        versionCode = buildInfo.versionCode.toLong(),
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.orEmpty().trim() }
            .filter { it.isNotEmpty() }
            // "Google Pixel 5", not "Google Google Pixel 5".
            .let { parts -> if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) listOf(parts[1]) else parts }
            .joinToString(" ")
            .replaceFirstChar { it.titlecase(Locale.ROOT) },
        language = Locale.getDefault().toLanguageTag(),
    )

    override suspend fun contact(): FeedbackContact? {
        val username = sessionManager.loggedInUsername ?: return null
        return FeedbackContact(username, siteAccount.accountEmail().getOrNull())
    }

    /** The failure of [block], null when it worked; a cancellation is never taken for a failure. */
    private suspend fun submitting(block: suspend () -> Unit): Exception? = try {
        block()
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        exception
    }

    private suspend fun remember(sent: FeedbackDto) {
        val current = decodeList(keyValueDao.get(KEY_MINE)).feedback
        saveMine(FeedbackListDto(listOf(sent) + current.filterNot { it.id == sent.id }))
    }

    private fun FeedbackDraft.toRequest() = FeedbackRequestDto(
        kind = kind.name,
        text = text.trim(),
        technical = technical?.let { FeedbackTechnicalDto(it.appVersion, it.versionCode, it.android, it.device, it.language) },
        contact = contact?.let { FeedbackContactDto(it.username, it.email) },
    )

    private suspend fun saveMine(list: FeedbackListDto) {
        keyValueDao.put(KeyValueEntity(KEY_MINE, json.encodeToString(FeedbackListDto.serializer(), list)))
    }

    private suspend fun saveOutbox(outbox: List<QueuedFeedback>) {
        keyValueDao.put(KeyValueEntity(KEY_OUTBOX, json.encodeToString(ListSerializer(QueuedFeedback.serializer()), outbox)))
    }

    private fun decodeList(payload: String?): FeedbackListDto = payload?.takeIf { it.isNotBlank() }
        ?.let { runCatching { json.decodeFromString(FeedbackListDto.serializer(), it) }.getOrNull() }
        ?: FeedbackListDto()

    private fun decodeOutbox(payload: String?): List<QueuedFeedback> = payload?.takeIf { it.isNotBlank() }
        ?.let { runCatching { json.decodeFromString(ListSerializer(QueuedFeedback.serializer()), it) }.getOrNull() }
        .orEmpty()

    private fun decodeSeen(payload: String?): Set<String> = payload?.takeIf { it.isNotBlank() }
        ?.let { runCatching { json.decodeFromString(SetSerializer(String.serializer()), it) }.getOrNull() }
        .orEmpty()

    /** A message kept on the phone until the network is back, as it will be sent. */
    @Serializable
    internal data class QueuedFeedback(val localId: String, val request: FeedbackRequestDto, val createdAtMs: Long) {
        fun toDomainOrNull(): FeedbackMessage? = FeedbackMessage(
            id = localId,
            kind = FeedbackKind.entries.firstOrNull { it.name == request.kind } ?: return null,
            text = request.text,
            status = FeedbackStatus.NEW,
            createdAt = Instant.ofEpochMilli(createdAtMs),
            isQueued = true,
        )
    }

    companion object {
        private const val KEY_MINE = "feedback_mine"
        private const val KEY_OUTBOX = "feedback_outbox"
        private const val KEY_SEEN = "feedback_replies_seen"
    }
}

/** A reply is "seen" for its text's date: a new reply to the same message is new again. */
private fun FeedbackDto.replyKey(): String? = reply?.let { "$id|${repliedAt.orEmpty()}" }

internal fun FeedbackDto.toDomainOrNull(seenReplies: Set<String> = emptySet()): FeedbackMessage? {
    val created = createdAt.toInstantOrNull() ?: return null
    return FeedbackMessage(
        id = id,
        kind = FeedbackKind.entries.firstOrNull { it.name == kind } ?: return null,
        text = text,
        status = FeedbackStatus.entries.firstOrNull { it.name == status } ?: return null,
        createdAt = created,
        updatedAt = updatedAt?.toInstantOrNull() ?: created,
        reply = reply,
        repliedAt = repliedAt?.toInstantOrNull(),
        isReplyNew = replyKey()?.let { it !in seenReplies } ?: false,
    )
}
