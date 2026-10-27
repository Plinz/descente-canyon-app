package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.CanyonNoteDao
import fr.descentecanyon.app.data.local.user.dao.FavoriteDao
import fr.descentecanyon.app.data.local.user.dao.LogbookDao
import fr.descentecanyon.app.data.local.user.dao.PlaceDao
import fr.descentecanyon.app.data.local.user.dao.StayDao
import fr.descentecanyon.app.data.local.user.entity.CanyonNoteEntity
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.data.local.user.entity.PlaceEntity
import fr.descentecanyon.app.data.local.user.entity.StayEntity
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PersonalDataImport
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.PersonalDataRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON and CSV files of the user's data; nothing leaves the phone unless the user shares the file. */
@Singleton
class PersonalDataRepositoryImpl @Inject constructor(
    private val logbookDao: LogbookDao,
    private val canyonNoteDao: CanyonNoteDao,
    private val favoriteDao: FavoriteDao,
    private val placeDao: PlaceDao,
    private val stayDao: StayDao,
    private val logbookRepository: LogbookRepository,
    private val clock: Clock,
) : PersonalDataRepository {

    override suspend fun exportJson(): String = JSON.encodeToString(
        PersonalDataFile.serializer(),
        PersonalDataFile(
            exportedAt = Instant.ofEpochMilli(clock.millis()).toString(),
            logbook = logbookDao.getAll().mapNotNull { it.toDomain() }.map { it.toItem() },
            memos = canyonNoteDao.getAll().map { MemoItem(it.canyonId, it.text, it.updatedAt) },
            favorites = favoriteDao.observeIds().first(),
            places = placeDao.getAll().map { PlaceItem(it.name, it.latitude, it.longitude) },
            stays = placeDao.getAll().associateBy { it.id }.let { places ->
                stayDao.getAll().mapNotNull { stay ->
                    places[stay.placeId]?.let { place -> StayItem(place.name, place.latitude, place.longitude, stay.start, stay.end) }
                }
            },
        ),
    )

    override suspend fun exportLogbookCsv(): String {
        val rows = logbookDao.getAll().mapNotNull { it.toDomain() }.sortedBy { it.date }.map { entry ->
            listOf(
                entry.date.toString(),
                entry.canyonName,
                entry.canyonId.toString(),
                entry.debitLevel?.label.orEmpty(),
                entry.companions.orEmpty(),
                entry.durationMinutes?.toString().orEmpty(),
                entry.note.orEmpty(),
                entry.source.name.lowercase(),
            )
        }
        return (listOf(CSV_HEADER) + rows).joinToString("\r\n", postfix = "\r\n") { cells -> cells.joinToString(",") { it.csvCell() } }
    }

    override suspend fun importJson(content: String): Result<PersonalDataImport> = suspendRunCatching {
        val file = runCatching { JSON.decodeFromString(PersonalDataFile.serializer(), content) }.getOrNull()
            ?.takeIf { it.format == FORMAT }
            ?: appError(AppError.UnexpectedResponse)
        val outings = file.logbook.mapNotNull { it.toEntry() }.count { logbookRepository.record(it) }
        val memos = file.memos.count { memo ->
            if (memo.text.isBlank() || canyonNoteDao.get(memo.canyonId) != null) return@count false
            canyonNoteDao.upsert(CanyonNoteEntity(memo.canyonId, memo.text.trim(), memo.updatedAt))
            true
        }
        val knownFavorites = favoriteDao.observeIds().first().toSet()
        val newFavorites = file.favorites.distinct().filterNot { it in knownFavorites }
        favoriteDao.insertAll(newFavorites.map { FavoriteEntity(canyonId = it, addedAt = clock.millis()) })
        val placeIds = placeDao.getAll().associate { Triple(it.name, it.latitude, it.longitude) to it.id }.toMutableMap()
        file.places.filter { it.name.isNotBlank() }.forEach { place ->
            val key = Triple(place.name, place.latitude, place.longitude)
            if (key !in placeIds) {
                placeIds[key] = placeDao.upsert(
                    PlaceEntity(name = place.name, latitude = place.latitude, longitude = place.longitude, createdAt = clock.millis()),
                )
            }
        }
        // Files of version 2.7 kept the stay dates with the place.
        val legacyStays = file.places.mapNotNull { place ->
            place.stayStart?.let { start ->
                place.stayEnd?.let { end -> StayItem(place.name, place.latitude, place.longitude, start, end) }
            }
        }
        val knownStays = stayDao.getAll().map { Triple(it.placeId, it.start, it.end) }.toMutableSet()
        (file.stays + legacyStays).forEach { stay ->
            val placeId = placeIds[Triple(stay.placeName, stay.latitude, stay.longitude)] ?: return@forEach
            val start = runCatching { LocalDate.parse(stay.start) }.getOrNull() ?: return@forEach
            val end = runCatching { LocalDate.parse(stay.end) }.getOrNull()?.takeIf { !it.isBefore(start) } ?: return@forEach
            if (knownStays.add(Triple(placeId, start.toString(), end.toString()))) {
                stayDao.upsert(StayEntity(placeId = placeId, start = start.toString(), end = end.toString(), createdAt = clock.millis()))
            }
        }
        PersonalDataImport(outings = outings, memos = memos, favorites = newFavorites.size)
    }

    override suspend fun clearLogbookAndMemos() {
        logbookDao.deleteAll()
        canyonNoteDao.deleteAll()
    }

    private fun LogbookEntry.toItem() = LogbookItem(
        canyonId = canyonId,
        canyonName = canyonName,
        date = date.toString(),
        note = note,
        debitLevel = debitLevel?.name,
        companions = companions,
        durationMinutes = durationMinutes,
        source = source.name,
        siteObservationId = siteObservationId,
    )

    private fun LogbookItem.toEntry(): LogbookEntry? {
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        return LogbookEntry(
            canyonId = canyonId,
            canyonName = canyonName,
            date = day,
            note = note,
            debitLevel = debitLevel?.let { name -> NiveauDebit.entries.firstOrNull { it.name == name } },
            companions = companions,
            durationMinutes = durationMinutes,
            source = LogbookSource.FILE,
            siteObservationId = siteObservationId,
        )
    }

    private fun String.csvCell(): String = if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + replace("\"", "\"\"") + "\""
    } else {
        this
    }

    private companion object {
        const val FORMAT = "descente-canyon-personal-data"
        val CSV_HEADER = listOf("date", "canyon", "canyon_id", "flow", "companions", "duration_min", "note", "source")
        val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

@Serializable
internal data class PersonalDataFile(
    val format: String = "descente-canyon-personal-data",
    val version: Int = 1,
    val exportedAt: String,
    val logbook: List<LogbookItem> = emptyList(),
    val memos: List<MemoItem> = emptyList(),
    val favorites: List<Int> = emptyList(),
    /** Starting places of outings; absent from the files of older versions. */
    val places: List<PlaceItem> = emptyList(),
    /** Stays at the places, from version 2.8. */
    val stays: List<StayItem> = emptyList(),
)

@Serializable
internal data class LogbookItem(
    val canyonId: Int,
    val canyonName: String,
    val date: String,
    val note: String? = null,
    val debitLevel: String? = null,
    val companions: String? = null,
    val durationMinutes: Int? = null,
    val source: String? = null,
    val siteObservationId: Long? = null,
)

@Serializable
internal data class MemoItem(val canyonId: Int, val text: String, val updatedAt: Long)

@Serializable
internal data class PlaceItem(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** Version 2.7 only: the stay at the place. */
    val stayStart: String? = null,
    val stayEnd: String? = null,
)

/** A stay at the place of that name and position; ISO dates. */
@Serializable
internal data class StayItem(val placeName: String, val latitude: Double, val longitude: Double, val start: String, val end: String)
