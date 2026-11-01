package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.DebitSendDao
import fr.descentecanyon.app.data.local.user.dao.LogbookDao
import fr.descentecanyon.app.data.local.user.dao.MySiteReportDao
import fr.descentecanyon.app.data.local.user.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.user.entity.LogbookEntryEntity
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.repository.LogbookRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class LogbookRepositoryImpl @Inject constructor(
    private val logbookDao: LogbookDao,
    private val mySiteReportDao: MySiteReportDao,
    private val pendingDebitDao: PendingDebitSubmissionDao,
    private val debitSendDao: DebitSendDao,
    private val clock: Clock,
) : LogbookRepository {

    /** The outings with, for each, the flow report the site (or the send queue) holds for that canyon and day. */
    override fun observeAll(): Flow<List<LogbookEntry>> = combine(
        logbookDao.observeAll(),
        mySiteReportDao.observeAll(),
        pendingDebitDao.observeAll(),
        debitSendDao.observeAll(),
    ) { rows, reports, pending, sends ->
        val lookup = LogbookDebitLookup(reports, pending, sends)
        rows.mapNotNull { row -> row.toDomain()?.let { it.copy(debit = lookup.of(it.canyonId, it.date)) } }
    }

    override fun observeForCanyon(canyonId: Int): Flow<List<LogbookEntry>> =
        observeAll().map { entries -> entries.filter { it.canyonId == canyonId } }

    override fun observeDescendedCanyonIds(): Flow<Set<Int>> = logbookDao.observeCanyonIds().map { it.toSet() }.distinctUntilChanged()

    override suspend fun save(entry: LogbookEntry) {
        val cleaned = entry.cleaned()
        val edited = cleaned.id.takeIf { it != 0L }?.let { logbookDao.findById(it) }
        val sameDay = logbookDao.find(cleaned.canyonId, cleaned.date.toString())?.takeIf { it.id != cleaned.id }
        val now = clock.millis()
        if (sameDay == null) {
            logbookDao.upsert(cleaned.toEntity(createdAt = edited?.createdAt ?: now, updatedAt = now))
        } else {
            // Moved onto the day of another outing of the canyon: both become one, what was just written first.
            val merged = cleaned.completedWith(sameDay.toDomain() ?: cleaned).copy(id = sameDay.id)
            val entity = merged.toEntity(createdAt = sameDay.createdAt, updatedAt = now)
            if (edited != null) logbookDao.replace(edited.id, entity) else logbookDao.upsert(entity)
        }
    }

    override suspend fun record(entry: LogbookEntry): Boolean {
        val cleaned = entry.cleaned()
        val existing = logbookDao.find(cleaned.canyonId, cleaned.date.toString())
        val now = clock.millis()
        if (existing == null) {
            logbookDao.upsert(cleaned.copy(id = 0).toEntity(createdAt = now, updatedAt = null))
            return true
        }
        val current = existing.toDomain() ?: return false
        val completed = current.completedWith(cleaned)
        if (completed != current) logbookDao.upsert(completed.toEntity(createdAt = existing.createdAt, updatedAt = now))
        return false
    }

    override suspend fun delete(id: Long) = logbookDao.delete(id)

    override suspend fun knownCompanions(): List<String> = logbookDao.getCompanions()
        .flatMap(LogbookEntry::splitCompanions)
        .groupBy { it.lowercase() }
        .values
        .sortedWith(compareByDescending<List<String>> { it.size }.thenBy { it.first().lowercase() })
        .map { names -> names.first() }

    override suspend fun clear() = logbookDao.deleteAll()

    private fun LogbookEntry.cleaned(): LogbookEntry = copy(
        canyonName = canyonName.trim(),
        companions = LogbookEntry.splitCompanions(companions).joinToString(", ").ifEmpty { null },
        durationMinutes = durationMinutes?.takeIf { it > 0 },
    )

    private fun LogbookEntry.toEntity(createdAt: Long, updatedAt: Long?) = LogbookEntryEntity(
        id = id,
        canyonId = canyonId,
        canyonName = canyonName,
        date = date.toString(),
        createdAt = createdAt,
        companions = companions,
        durationMinutes = durationMinutes,
        source = source.name,
        updatedAt = updatedAt,
    )
}

internal fun LogbookEntryEntity.toDomain(): LogbookEntry? = runCatching { LocalDate.parse(date) }.getOrNull()?.let { day ->
    LogbookEntry(
        id = id,
        canyonId = canyonId,
        canyonName = canyonName,
        date = day,
        companions = companions,
        durationMinutes = durationMinutes,
        source = LogbookSource.entries.firstOrNull { it.name == source } ?: LogbookSource.MANUAL,
    )
}
