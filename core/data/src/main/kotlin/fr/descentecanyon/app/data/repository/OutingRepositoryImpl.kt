package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.PlannedOutingDao
import fr.descentecanyon.app.data.local.user.entity.PlannedOutingEntity
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.OutingAnchor
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.OutingPhase
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.OutingRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class OutingRepositoryImpl @Inject constructor(private val dao: PlannedOutingDao, private val clock: Clock) : OutingRepository {

    override fun observeAll(): Flow<List<PlannedOuting>> = dao.observeAll()
        .map { entities -> entities.map { it.toDomain() }.filterNot { it.isStale(clock.instant()) } }
        .distinctUntilChanged()

    override suspend fun get(id: Long): PlannedOuting? = dao.get(id)?.toDomain()

    override suspend fun save(outing: PlannedOuting): Long {
        // Outings long past their alert time are forgotten as new ones come.
        dao.deleteAlertedBefore(clock.instant().minus(PlannedOuting.STALE_AFTER).toEpochMilli())
        return dao.upsert(outing.toEntity())
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun clear() = dao.deleteAll()

    private fun PlannedOuting.toEntity() = PlannedOutingEntity(
        id = id,
        canyonId = canyonId,
        canyonName = canyonName,
        start = start.toEpochMilli(),
        expectedBack = expectedBack.toEpochMilli(),
        latestBack = latestBack.toEpochMilli(),
        alertAt = alertAt.toEpochMilli(),
        departure = departure?.toEpochMilli(),
        groupSize = groupSize,
        informedAlertAt = informedAlertAt?.toEpochMilli(),
        choicesJson = choices?.let { json.encodeToString(StoredChoices.serializer(), StoredChoices.of(it)) },
        tracked = tracked,
        stayId = stayId,
        updatedAt = clock.millis(),
    )

    private fun PlannedOutingEntity.toDomain() = PlannedOuting(
        id = id,
        canyonId = canyonId,
        canyonName = canyonName,
        start = Instant.ofEpochMilli(start),
        expectedBack = Instant.ofEpochMilli(expectedBack),
        latestBack = Instant.ofEpochMilli(latestBack),
        alertAt = Instant.ofEpochMilli(alertAt),
        departure = departure?.let(Instant::ofEpochMilli),
        groupSize = groupSize,
        informedAlertAt = informedAlertAt?.let(Instant::ofEpochMilli),
        choices = choicesJson?.let { payload ->
            runCatching { json.decodeFromString(StoredChoices.serializer(), payload) }.getOrNull()?.toDomain()
        },
        tracked = tracked,
        stayId = stayId,
    )

    /** [OutingChoices] as text: enum names and ISO dates, readable by later versions. */
    @Serializable
    private data class StoredChoices(
        val date: String,
        val anchorTime: String,
        val anchor: String = OutingAnchor.ARRIVE_PARKING.name,
        val placeId: Long? = null,
        val groupSize: Int = OutingSettings.SMALL_GROUP_MAX,
        val groupLevel: String = GroupLevel.AUTONOMOUS.name,
        val withShuttle: Boolean? = null,
        val alertMarginHours: Int = OutingChoices.DEFAULT_ALERT_MARGIN_HOURS,
        val alertDelayMinutes: Int = 0,
        val adjustments: Map<String, Int> = emptyMap(),
        val checkedGear: List<String> = emptyList(),
        val meetAtPlace: Boolean = false,
        val note: String = "",
    ) {
        fun toDomain(): OutingChoices? {
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
            val time = runCatching { LocalTime.parse(anchorTime) }.getOrNull() ?: return null
            return OutingChoices(
                date = day,
                anchorTime = time,
                anchor = OutingAnchor.entries.firstOrNull { it.name == anchor } ?: OutingAnchor.ARRIVE_PARKING,
                placeId = placeId,
                groupSize = groupSize,
                groupLevel = GroupLevel.entries.firstOrNull { it.name == groupLevel } ?: GroupLevel.AUTONOMOUS,
                withShuttle = withShuttle,
                alertMarginHours = alertMarginHours,
                alertDelayMinutes = alertDelayMinutes,
                adjustments = adjustments.mapNotNull { (name, minutes) ->
                    OutingPhase.entries.firstOrNull { it.name == name }?.let { it to minutes }
                }.toMap(),
                checkedGear = checkedGear.toSet(),
                meetAtPlace = meetAtPlace,
                note = note,
            )
        }

        companion object {
            fun of(choices: OutingChoices) = StoredChoices(
                date = choices.date.toString(),
                anchorTime = choices.anchorTime.toString(),
                anchor = choices.anchor.name,
                placeId = choices.placeId,
                groupSize = choices.groupSize,
                groupLevel = choices.groupLevel.name,
                withShuttle = choices.withShuttle,
                alertMarginHours = choices.alertMarginHours,
                alertDelayMinutes = choices.alertDelayMinutes,
                adjustments = choices.adjustments.mapKeys { it.key.name },
                checkedGear = choices.checkedGear.sorted(),
                meetAtPlace = choices.meetAtPlace,
                note = choices.note,
            )
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
