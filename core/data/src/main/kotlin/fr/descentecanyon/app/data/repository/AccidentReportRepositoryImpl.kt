package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.domain.model.AccidentCircumstance
import fr.descentecanyon.app.domain.model.AccidentReport
import fr.descentecanyon.app.domain.model.BreathingTrouble
import fr.descentecanyon.app.domain.model.InjuryArea
import fr.descentecanyon.app.domain.model.Responsiveness
import fr.descentecanyon.app.domain.model.VictimCheck
import fr.descentecanyon.app.domain.repository.AccidentReportRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The accident report as one JSON value of the user database. Health details of a third person:
 * it is dropped once [AccidentReport.EXPIRY] has passed since its last change.
 */
@Singleton
class AccidentReportRepositoryImpl @Inject constructor(private val keyValueDao: KeyValueDao, private val clock: Clock) :
    AccidentReportRepository {

    override fun observe(): Flow<AccidentReport?> = keyValueDao.observe(KEY).map { payload ->
        val stored = payload?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
        stored?.takeIf { clock.millis() - it.savedAtEpochMs < AccidentReport.EXPIRY.toMillis() }?.toDomain()
    }.distinctUntilChanged()

    override suspend fun save(report: AccidentReport) {
        if (report.isEmpty) {
            clear()
            return
        }
        val now = clock.millis()
        keyValueDao.put(KeyValueEntity(KEY, json.encodeToString(Stored.serializer(), Stored.of(report, now)), updatedAt = now))
    }

    override suspend fun clear() = keyValueDao.deleteByPrefixOlderThan(KEY, Long.MAX_VALUE)

    /** Enum values are stored by name: one removed later reads as unanswered. */
    @Serializable
    private data class Stored(
        val savedAtEpochMs: Long = 0,
        val accidentTimeEpochMs: Long? = null,
        val injuredCount: Int? = null,
        val peopleOnSite: Int? = null,
        val circumstances: List<String> = emptyList(),
        val conscious: Boolean? = null,
        val breathing: Boolean? = null,
        val heavyBleeding: Boolean? = null,
        val canWalk: Boolean? = null,
        val injuries: List<String> = emptyList(),
        val waterRising: Boolean? = null,
        val location: String = "",
        val checks: List<StoredCheck> = emptyList(),
    ) {
        fun toDomain() = AccidentReport(
            accidentTime = accidentTimeEpochMs?.let(Instant::ofEpochMilli),
            injuredCount = injuredCount,
            peopleOnSite = peopleOnSite,
            circumstances = circumstances.mapNotNull { name -> AccidentCircumstance.entries.firstOrNull { it.name == name } }.toSet(),
            conscious = conscious,
            breathing = breathing,
            heavyBleeding = heavyBleeding,
            canWalk = canWalk,
            injuries = injuries.mapNotNull { name -> InjuryArea.entries.firstOrNull { it.name == name } }.toSet(),
            waterRising = waterRising,
            location = location,
            checks = checks.map { it.toDomain() },
        )

        companion object {
            fun of(report: AccidentReport, savedAtEpochMs: Long) = Stored(
                savedAtEpochMs = savedAtEpochMs,
                accidentTimeEpochMs = report.accidentTime?.toEpochMilli(),
                injuredCount = report.injuredCount,
                peopleOnSite = report.peopleOnSite,
                circumstances = report.circumstances.map { it.name }.sorted(),
                conscious = report.conscious,
                breathing = report.breathing,
                heavyBleeding = report.heavyBleeding,
                canWalk = report.canWalk,
                injuries = report.injuries.map { it.name }.sorted(),
                waterRising = report.waterRising,
                location = report.location,
                checks = report.checks.map(StoredCheck::of),
            )
        }
    }

    @Serializable
    private data class StoredCheck(
        val atEpochMs: Long,
        val responds: String? = null,
        val breathing: String? = null,
        val pain: Int? = null,
        val cold: Boolean? = null,
        val note: String = "",
    ) {
        fun toDomain() = VictimCheck(
            at = Instant.ofEpochMilli(atEpochMs),
            responds = Responsiveness.entries.firstOrNull { it.name == responds },
            breathing = BreathingTrouble.entries.firstOrNull { it.name == breathing },
            pain = pain,
            cold = cold,
            note = note,
        )

        companion object {
            fun of(check: VictimCheck) = StoredCheck(
                atEpochMs = check.at.toEpochMilli(),
                responds = check.responds?.name,
                breathing = check.breathing?.name,
                pain = check.pain,
                cold = check.cold,
                note = check.note,
            )
        }
    }

    private companion object {
        const val KEY = "emergency.accident_report"
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
