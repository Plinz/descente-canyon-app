package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.LogbookDao
import fr.descentecanyon.app.data.local.user.entity.LogbookEntryEntity
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.repository.LogbookRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class LogbookRepositoryImpl @Inject constructor(private val logbookDao: LogbookDao, private val clock: Clock) : LogbookRepository {

    override fun observeAll(): Flow<List<LogbookEntry>> = logbookDao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeForCanyon(canyonId: Int): Flow<List<LogbookEntry>> =
        logbookDao.observeForCanyon(canyonId).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun add(canyonId: Int, canyonName: String, date: LocalDate, note: String?) {
        val existing = logbookDao.find(canyonId, date.toString())
        logbookDao.upsert(
            LogbookEntryEntity(
                id = existing?.id ?: 0,
                canyonId = canyonId,
                canyonName = canyonName,
                date = date.toString(),
                note = note?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.note,
                createdAt = existing?.createdAt ?: clock.millis(),
            ),
        )
    }

    override suspend fun delete(id: Long) = logbookDao.delete(id)

    private fun LogbookEntryEntity.toDomain(): LogbookEntry? = runCatching { LocalDate.parse(date) }.getOrNull()?.let { day ->
        LogbookEntry(id = id, canyonId = canyonId, canyonName = canyonName, date = day, note = note)
    }
}
