package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.entity.DebitSendEntity
import fr.descentecanyon.app.data.local.user.entity.MySiteReportEntity
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.domain.model.LogbookDebit
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.SiteObservation
import java.time.LocalDate

/**
 * Finds what the site holds about the flow of an outing: the report waiting to be sent first, then
 * the report listed on the account, then the trace of a report sent from the app. The logbook has
 * no flow of its own.
 */
internal class LogbookDebitLookup(
    reports: List<MySiteReportEntity>,
    pending: List<PendingDebitSubmissionEntity>,
    sends: List<DebitSendEntity>,
) {
    private val waiting = pending.filter { it.rejectedAt == null }.map { it.canyonId to it.observationDate }.toSet()
    private val published = reports.filter { it.canyonId != null }.groupBy { it.canyonId to it.date }
    private val sent = sends.groupBy { it.canyonId to it.observationDate }

    fun of(canyonId: Int, date: LocalDate): LogbookDebit {
        val key = canyonId to date.toString()
        if (key in waiting) return LogbookDebit.Waiting
        // A report of the day the person descended wins over one where the flow was only looked at.
        published[key]?.maxWithOrNull(compareBy<MySiteReportEntity> { it.isDescended }.thenBy { it.id })?.let {
            return LogbookDebit.Published(it.toObservation())
        }
        return sent[key]?.maxOf { it.sentAt }?.let { LogbookDebit.Sent(it) } ?: LogbookDebit.None
    }
}

internal fun MySiteReportEntity.toObservation(): SiteObservation = SiteObservation(
    id = id,
    canyonId = canyonId,
    canyonName = canyonName,
    date = LocalDate.parse(date),
    isDescended = isDescended,
    debitLevel = debitLevel?.let { name -> NiveauDebit.entries.firstOrNull { it.name == name } },
    waterTemperature = waterTemperature,
    airTemperature = airTemperature,
    publicRemark = publicRemark,
    personalComment = personalComment,
)

internal fun SiteObservation.toEntity(fetchedAt: Long): MySiteReportEntity = MySiteReportEntity(
    id = id,
    canyonId = canyonId,
    canyonName = canyonName,
    date = date.toString(),
    isDescended = isDescended,
    debitLevel = debitLevel?.name,
    waterTemperature = waterTemperature,
    airTemperature = airTemperature,
    publicRemark = publicRemark,
    personalComment = personalComment,
    fetchedAt = fetchedAt,
)
