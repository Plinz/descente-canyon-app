package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.OutingRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class OutingRepositoryImpl @Inject constructor(private val keyValueDao: KeyValueDao, private val clock: Clock) : OutingRepository {

    override fun observeCurrent(): Flow<PlannedOuting?> = keyValueDao.observe(KEY)
        .map { payload -> payload?.let(::decode)?.takeUnless { it.isStale(clock.instant()) } }
        .distinctUntilChanged()

    override suspend fun save(outing: PlannedOuting) {
        val stored = StoredOuting(
            canyonId = outing.canyonId,
            canyonName = outing.canyonName,
            startEpochMs = outing.start.toEpochMilli(),
            expectedBackEpochMs = outing.expectedBack.toEpochMilli(),
            alertAtEpochMs = outing.alertAt.toEpochMilli(),
        )
        keyValueDao.put(KeyValueEntity(KEY, json.encodeToString(StoredOuting.serializer(), stored), updatedAt = clock.millis()))
    }

    override suspend fun clear() {
        keyValueDao.put(KeyValueEntity(KEY, "", updatedAt = clock.millis()))
    }

    private fun decode(payload: String): PlannedOuting? = if (payload.isBlank()) {
        null
    } else {
        runCatching { json.decodeFromString(StoredOuting.serializer(), payload) }.getOrNull()?.let { stored ->
            PlannedOuting(
                canyonId = stored.canyonId,
                canyonName = stored.canyonName,
                start = Instant.ofEpochMilli(stored.startEpochMs),
                expectedBack = Instant.ofEpochMilli(stored.expectedBackEpochMs),
                alertAt = Instant.ofEpochMilli(stored.alertAtEpochMs),
            )
        }
    }

    @Serializable
    private data class StoredOuting(
        val canyonId: Int,
        val canyonName: String,
        val startEpochMs: Long,
        val expectedBackEpochMs: Long,
        val alertAtEpochMs: Long,
    )

    private companion object {
        const val KEY = "outing.current"
        val json = Json { ignoreUnknownKeys = true }
    }
}
