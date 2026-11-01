package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.SiteLogbookImport
import fr.descentecanyon.app.domain.model.SiteObservation
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.SiteAccountRepository
import javax.inject.Inject

/**
 * Brings the descents of the site account ("Ma liste de courses") into the logbook as outings (canyon
 * and day). Reports of a flow only looked at are left out. The flow and the comments stay on the
 * site: the logbook reads them back, it never copies them. Running it again adds nothing twice.
 */
class ImportSiteLogbookUseCase @Inject constructor(
    private val siteAccountRepository: SiteAccountRepository,
    private val logbookRepository: LogbookRepository,
) {
    suspend operator fun invoke(): Result<SiteLogbookImport> = siteAccountRepository.refreshMyObservations().map { observations ->
        val descents = observations.mapNotNull { it.toLogbookEntry() }
        val added = descents.count { logbookRepository.record(it) }
        SiteLogbookImport(descents = descents.size, added = added, completed = descents.size - added)
    }

    private fun SiteObservation.toLogbookEntry(): LogbookEntry? {
        if (!isDescended) return null
        val canyon = canyonId ?: return null
        return LogbookEntry(
            canyonId = canyon,
            canyonName = canyonName,
            date = date,
            source = LogbookSource.SITE,
        )
    }
}
