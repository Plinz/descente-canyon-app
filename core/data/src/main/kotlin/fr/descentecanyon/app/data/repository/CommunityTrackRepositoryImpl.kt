package fr.descentecanyon.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.catalog.toInstantOrNull
import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.remote.backend.BackendClient
import fr.descentecanyon.app.data.remote.backend.TrackDto
import fr.descentecanyon.app.data.remote.backend.TrackListDto
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CommunityTrack
import fr.descentecanyon.app.domain.model.CommunityTracks
import fr.descentecanyon.app.domain.model.GeoBounds
import fr.descentecanyon.app.domain.model.TrackPoint
import fr.descentecanyon.app.domain.model.TrackRejection
import fr.descentecanyon.app.domain.model.TrackRole
import fr.descentecanyon.app.domain.model.TrackStanding
import fr.descentecanyon.app.domain.model.TrackVote
import fr.descentecanyon.app.domain.repository.CommunityTrackRepository
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Shared tracks: read from the server, cached on the phone (outside the Android backup: they can be
 * read again), with this installation's own uploads and votes kept in the user database.
 */
@Singleton
class CommunityTrackRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: BackendClient,
    private val keyValueDao: KeyValueDao,
    private val installKeys: InstallKeyStore,
    private val json: Json,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CommunityTrackRepository {

    private val cacheDirectory: File get() = File(context.noBackupFilesDir, CACHE_DIRECTORY)
    private val cache = MutableStateFlow<Map<Int, CachedTracks>>(emptyMap())

    private val votesSerializer = MapSerializer(String.serializer(), Int.serializer())
    private val mineSerializer = SetSerializer(String.serializer())

    override fun observeTracks(canyonId: Int): Flow<CommunityTracks?> = combine(
        cache.map { it[canyonId] }.distinctUntilChanged().onStart { loadFromDisk(canyonId) },
        keyValueDao.observe(KEY_VOTES).map { decodeOr(it, votesSerializer, emptyMap()) },
        keyValueDao.observe(KEY_MINE).map { decodeOr(it, mineSerializer, emptySet()) },
    ) { cached, votes, mine -> cached?.toDomain(votes, mine) }

    override suspend fun refresh(canyonId: Int): Result<CommunityTracks> = suspendRunCatching {
        val list = client.listTracks(canyonId)
        val cached = CachedTracks(list, clock.millis())
        store(canyonId, cached)
        cached.toDomain(loadVotes(), loadMine())
    }

    override suspend fun upload(canyonId: Int, role: TrackRole, gpx: ByteArray): Result<CommunityTrack> = suspendRunCatching {
        if (gpx.size > MAX_UPLOAD_BYTES) appError(AppError.TrackRejected(TrackRejection.TOO_LARGE))
        val track = client.uploadTrack(canyonId, role.name, gpx, installKey())
        save(KEY_MINE, mineSerializer, loadMine() + track.id)
        replaceInCache(track)
        track.toDomain(loadVotes(), loadMine())
    }

    override suspend fun vote(track: CommunityTrack, vote: TrackVote): Result<CommunityTrack> = suspendRunCatching {
        val updated = client.vote(track.id, vote.value, installKey())
        val votes = loadVotes()
        save(KEY_VOTES, votesSerializer, if (vote == TrackVote.NONE) votes - track.id else votes + (track.id to vote.value))
        replaceInCache(updated)
        updated.toDomain(loadVotes(), loadMine())
    }

    override suspend fun delete(track: CommunityTrack): Result<Unit> = suspendRunCatching {
        client.deleteTrack(track.id, installKey())
        save(KEY_MINE, mineSerializer, loadMine() - track.id)
        save(KEY_VOTES, votesSerializer, loadVotes() - track.id)
        val current = cache.value[track.canyonId] ?: return@suspendRunCatching
        store(track.canyonId, current.copy(list = current.list.copy(tracks = current.list.tracks.filterNot { it.id == track.id })))
    }

    private suspend fun installKey(): String = installKeys.get()

    private suspend fun replaceInCache(track: TrackDto) {
        val current = cache.value[track.canyonId] ?: CachedTracks(TrackListDto(track.canyonId), clock.millis())
        val others = current.list.tracks.filterNot { it.id == track.id }
        store(track.canyonId, current.copy(list = current.list.copy(tracks = others + track)))
    }

    private suspend fun store(canyonId: Int, cached: CachedTracks) {
        cache.update { it + (canyonId to cached) }
        withContext(ioDispatcher) {
            runCatching {
                cacheDirectory.mkdirs()
                val target = File(cacheDirectory, "$canyonId.json")
                val temporary = File(cacheDirectory, "$canyonId.json.part")
                temporary.writeText(json.encodeToString(CachedTracks.serializer(), cached))
                if (!temporary.renameTo(target)) temporary.delete()
            }
        }
    }

    private suspend fun loadFromDisk(canyonId: Int) {
        if (cache.value.containsKey(canyonId)) return
        val cached = withContext(ioDispatcher) {
            runCatching { json.decodeFromString(CachedTracks.serializer(), File(cacheDirectory, "$canyonId.json").readText()) }.getOrNull()
        } ?: return
        cache.update { if (it.containsKey(canyonId)) it else it + (canyonId to cached) }
    }

    private suspend fun loadVotes(): Map<String, Int> = decodeOr(keyValueDao.get(KEY_VOTES), votesSerializer, emptyMap())

    private suspend fun loadMine(): Set<String> = decodeOr(keyValueDao.get(KEY_MINE), mineSerializer, emptySet())

    private suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T) {
        keyValueDao.put(KeyValueEntity(key, json.encodeToString(serializer, value)))
    }

    private fun <T> decodeOr(payload: String?, serializer: KSerializer<T>, default: T): T =
        payload?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default

    private fun CachedTracks.toDomain(votes: Map<String, Int>, mine: Set<String>) = CommunityTracks(
        canyonId = list.canyonId,
        tracks = list.tracks.mapNotNull { it.toDomainOrNull(votes, mine) }.sortedWith(DISPLAY_ORDER),
        fetchedAt = Instant.ofEpochMilli(fetchedAtMs),
    )

    private fun TrackDto.toDomain(votes: Map<String, Int>, mine: Set<String>): CommunityTrack =
        toDomainOrNull(votes, mine) ?: appError(AppError.UnexpectedResponse)

    @Serializable
    private data class CachedTracks(val list: TrackListDto, val fetchedAtMs: Long)

    companion object {
        private const val CACHE_DIRECTORY = "community-tracks"
        private const val KEY_VOTES = "community_track_votes"
        private const val KEY_MINE = "community_tracks_mine"
        const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024

        /** Reference tracks first, then by votes; set-aside tracks last (the server orders the same way). */
        private val DISPLAY_ORDER = compareBy<CommunityTrack>(
            { it.standing == TrackStanding.DISCREDITED },
            { !it.isReference },
            { -(it.upVotes - it.downVotes) },
        ).thenByDescending { it.createdAt }
    }
}

/** Maps a server track; null when it uses a role or a standing this version does not know. */
internal fun TrackDto.toDomainOrNull(votes: Map<String, Int>, mine: Set<String>): CommunityTrack? {
    val trackRole = TrackRole.entries.firstOrNull { it.name == role } ?: return null
    val trackStanding = TrackStanding.entries.firstOrNull { it.name == standing } ?: return null
    if (bbox.size != 4) return null
    val segments = geometry.map { segment ->
        segment.mapNotNull { coordinate ->
            if (coordinate.size <
                2
            ) {
                null
            } else {
                TrackPoint(latitude = coordinate[1], longitude = coordinate[0], elevation = coordinate.getOrNull(2))
            }
        }
    }.filter { it.size >= 2 }
    if (segments.isEmpty()) return null
    return CommunityTrack(
        id = id,
        canyonId = canyonId,
        role = trackRole,
        createdAt = createdAt.toInstantOrNull() ?: Instant.EPOCH,
        recordedMonth = recordedMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() },
        lengthMeters = lengthM,
        ascentMeters = ascentM,
        descentMeters = descentM,
        segments = segments,
        bounds = GeoBounds(minLongitude = bbox[0], minLatitude = bbox[1], maxLongitude = bbox[2], maxLatitude = bbox[3]),
        upVotes = up,
        downVotes = down,
        standing = trackStanding,
        isReference = isReference,
        isMine = id in mine,
        myVote = TrackVote.of(votes[id] ?: 0),
    )
}
