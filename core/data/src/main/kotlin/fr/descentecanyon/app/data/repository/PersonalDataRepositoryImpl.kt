package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.CanyonNoteDao
import fr.descentecanyon.app.data.local.user.dao.FavoriteDao
import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.dao.LogbookDao
import fr.descentecanyon.app.data.local.user.dao.PlaceDao
import fr.descentecanyon.app.data.local.user.dao.PlannedOutingDao
import fr.descentecanyon.app.data.local.user.dao.StayDao
import fr.descentecanyon.app.data.local.user.entity.CanyonNoteEntity
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.local.user.entity.PlaceEntity
import fr.descentecanyon.app.data.local.user.entity.PlannedOutingEntity
import fr.descentecanyon.app.data.local.user.entity.StayEntity
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.PersonalDataImport
import fr.descentecanyon.app.domain.model.Stay
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
    private val plannedOutingDao: PlannedOutingDao,
    private val keyValueDao: KeyValueDao,
    private val logbookRepository: LogbookRepository,
    private val clock: Clock,
) : PersonalDataRepository {

    override suspend fun exportJson(): String {
        val places = placeDao.getAll().associateBy { it.id }
        val stays = stayDao.getAll().associateBy { it.id }
        return exportJson(places, stays)
    }

    private suspend fun exportJson(places: Map<Long, PlaceEntity>, stays: Map<Long, StayEntity>): String = JSON.encodeToString(
        PersonalDataFile.serializer(),
        PersonalDataFile(
            exportedAt = Instant.ofEpochMilli(clock.millis()).toString(),
            logbook = logbookDao.getAll().mapNotNull { it.toDomain() }.map { it.toItem() },
            memos = canyonNoteDao.getAll().map { MemoItem(it.canyonId, it.text, it.updatedAt) },
            favorites = favoriteDao.observeIds().first(),
            places = places.values.map { PlaceItem(it.name, it.latitude, it.longitude) },
            stays = stays.values.mapNotNull { stay ->
                places[stay.placeId]?.let { place -> StayItem(place.name, place.latitude, place.longitude, stay.start, stay.end) }
            },
            plannedOutings = plannedOutingDao.observeAll().first().map { outing ->
                val stay = outing.stayId?.let(stays::get)
                val place = stay?.let { places[it.placeId] }
                outing.toItem(
                    stay = if (stay != null &&
                        place != null
                    ) {
                        StayRef(place.name, place.latitude, place.longitude, stay.start)
                    } else {
                        null
                    },
                )
            },
            outingPreferences = OutingPreferencesRepositoryImpl.KEYS.mapNotNull { key -> keyValueDao.get(key)?.let { key to it } }.toMap(),
        ),
    )

    override suspend fun exportLogbookCsv(): String {
        val rows = logbookDao.getAll().mapNotNull { it.toDomain() }.sortedBy { it.date }.map { entry ->
            listOf(
                entry.date.toString(),
                entry.canyonName,
                entry.canyonId.toString(),
                entry.companions.orEmpty(),
                entry.durationMinutes?.toString().orEmpty(),
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
        // One is at one place a day: a stay sharing a day with one already there is left out.
        val knownStays = stayDao.getAll().mapNotNull { entity ->
            runCatching { Stay(entity.placeId, LocalDate.parse(entity.start), LocalDate.parse(entity.end)) }.getOrNull()
        }.toMutableList()
        (file.stays + legacyStays).forEach { item ->
            val placeId = placeIds[Triple(item.placeName, item.latitude, item.longitude)] ?: return@forEach
            val start = runCatching { LocalDate.parse(item.start) }.getOrNull() ?: return@forEach
            val end = runCatching { LocalDate.parse(item.end) }.getOrNull()?.takeIf { !it.isBefore(start) } ?: return@forEach
            val stay = Stay(placeId, start, end)
            if (knownStays.none { it.overlaps(stay) }) {
                knownStays += stay
                stayDao.upsert(StayEntity(placeId = placeId, start = start.toString(), end = end.toString(), createdAt = clock.millis()))
            }
        }
        // The plans still to come, on the stay of the same place and first day; an outing already
        // planned (same canyon, same start) is left as it is here.
        val now = clock.millis()
        val staysByRef = stayDao.getAll().associateBy { stay -> stay.placeId to stay.start }
        val knownOutings = plannedOutingDao.observeAll().first().map { it.canyonId to it.start }.toSet()
        val plannedOutings = file.plannedOutings.count { item ->
            if (item.latestBack < now || (item.canyonId to item.start) in knownOutings) return@count false
            val stayId = item.stay?.let { ref ->
                placeIds[Triple(ref.placeName, ref.latitude, ref.longitude)]?.let { placeId -> staysByRef[placeId to ref.start]?.id }
            }
            plannedOutingDao.upsert(item.toEntity(stayId = stayId, updatedAt = now))
            true
        }
        // Outing preferences: a phone that has its own keeps them.
        keyValueDao.putAllIfAbsent(
            file.outingPreferences.filterKeys {
                it in OutingPreferencesRepositoryImpl.KEYS
            }.map { (key, value) -> KeyValueEntity(key, value, now) },
        )
        PersonalDataImport(outings = outings, memos = memos, favorites = newFavorites.size, plannedOutings = plannedOutings)
    }

    override suspend fun clearLogbookAndMemos() {
        logbookDao.deleteAll()
        canyonNoteDao.deleteAll()
    }

    private fun PlannedOutingEntity.toItem(stay: StayRef?) = PlannedOutingItem(
        canyonId = canyonId,
        canyonName = canyonName,
        start = start,
        expectedBack = expectedBack,
        latestBack = latestBack,
        alertAt = alertAt,
        departure = departure,
        groupSize = groupSize,
        informedAlertAt = informedAlertAt,
        choicesJson = choicesJson,
        stay = stay,
    )

    private fun PlannedOutingItem.toEntity(stayId: Long?, updatedAt: Long) = PlannedOutingEntity(
        canyonId = canyonId,
        canyonName = canyonName,
        start = start,
        expectedBack = expectedBack,
        latestBack = latestBack,
        alertAt = alertAt,
        departure = departure,
        groupSize = groupSize,
        informedAlertAt = informedAlertAt,
        choicesJson = choicesJson,
        stayId = stayId,
        updatedAt = updatedAt,
    )

    private fun LogbookEntry.toItem() = LogbookItem(
        canyonId = canyonId,
        canyonName = canyonName,
        date = date.toString(),
        companions = companions,
        durationMinutes = durationMinutes,
        source = source.name,
    )

    private fun LogbookItem.toEntry(): LogbookEntry? {
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        return LogbookEntry(
            canyonId = canyonId,
            canyonName = canyonName,
            date = day,
            companions = companions,
            durationMinutes = durationMinutes,
            source = LogbookSource.FILE,
        )
    }

    private fun String.csvCell(): String = if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + replace("\"", "\"\"") + "\""
    } else {
        this
    }

    private companion object {
        const val FORMAT = "descente-canyon-personal-data"
        val CSV_HEADER = listOf("date", "canyon", "canyon_id", "companions", "duration_min", "source")
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
    /** 2 from version 2.9: planned outings and outing preferences. */
    val version: Int = 2,
    val exportedAt: String,
    val logbook: List<LogbookItem> = emptyList(),
    val memos: List<MemoItem> = emptyList(),
    val favorites: List<Int> = emptyList(),
    /** Starting places of outings; absent from the files of older versions. */
    val places: List<PlaceItem> = emptyList(),
    /** Stays at the places, from version 2.8. */
    val stays: List<StayItem> = emptyList(),
    /** Outings planned, from version 2.9: a phone changed before a trip keeps its plans. */
    val plannedOutings: List<PlannedOutingItem> = emptyList(),
    /** Outing settings, bag list and message choices as kept (JSON values by key), from version 2.9. */
    val outingPreferences: Map<String, String> = emptyMap(),
)

/** A planned outing as kept on the phone; times in epoch ms. */
@Serializable
internal data class PlannedOutingItem(
    val canyonId: Int,
    val canyonName: String,
    val start: Long,
    val expectedBack: Long,
    val latestBack: Long,
    val alertAt: Long,
    val departure: Long? = null,
    val groupSize: Int? = null,
    val informedAlertAt: Long? = null,
    val choicesJson: String? = null,
    /** The stay it was planned for. */
    val stay: StayRef? = null,
)

/** A stay by its place and first day. */
@Serializable
internal data class StayRef(val placeName: String, val latitude: Double, val longitude: Double, val start: String)

@Serializable
internal data class LogbookItem(
    val canyonId: Int,
    val canyonName: String,
    val date: String,
    val companions: String? = null,
    val durationMinutes: Int? = null,
    val source: String? = null,
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
