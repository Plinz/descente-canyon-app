package fr.descentecanyon.app.domain.model

import java.time.LocalDate

/**
 * A flow report of the logged-in member, as the site lists them in "Ma liste de courses"
 * (/perso/observations): the site's own logbook of the account.
 */
data class SiteObservation(
    /** Id of the report on the site. */
    val id: Long,
    /** Null when the row has no link to a canyon sheet. */
    val canyonId: Int?,
    val canyonName: String,
    val date: LocalDate,
    /** Whether the member descended the canyon, or only looked at the flow. */
    val isDescended: Boolean,
    val debitLevel: NiveauDebit?,
    val waterTemperature: String? = null,
    val airTemperature: String? = null,
    val publicRemark: String? = null,
    /** The comment only the member sees. */
    val personalComment: String? = null,
)

/** Outcome of importing the site's list of reports into the logbook. */
data class SiteLogbookImport(
    /** Descents found on the site. */
    val descents: Int,
    /** Outings that were not in the logbook yet. */
    val added: Int,
    /** Outings already logged that gained the flow or the comment of the site. */
    val completed: Int,
)

/** What a file exported by the app brought back. */
data class PersonalDataImport(val outings: Int, val memos: Int, val favorites: Int)
