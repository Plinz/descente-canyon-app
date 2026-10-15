package fr.descentecanyon.app.testutil

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Key-value table kept in memory, with the timestamps the cache pruning relies on. */
class InMemoryKeyValueDao : KeyValueDao {
    private val entries = MutableStateFlow<Map<String, KeyValueEntity>>(emptyMap())

    override suspend fun get(key: String): String? = entries.value[key]?.value

    override fun observe(key: String): Flow<String?> = entries.map { it[key]?.value }

    override suspend fun put(entry: KeyValueEntity) {
        entries.value = entries.value + (entry.key to entry)
    }

    override suspend fun putAll(entries: List<KeyValueEntity>) = entries.forEach { put(it) }

    override suspend fun putAllIfAbsent(entries: List<KeyValueEntity>) = entries.filter {
        it.key !in this.entries.value
    }.forEach { put(it) }

    override suspend fun deleteByPrefixOlderThan(prefix: String, beforeEpochMs: Long) {
        entries.value = entries.value.filterNot { (key, entry) -> key.startsWith(prefix) && entry.updatedAt < beforeEpochMs }
    }
}
