package fr.descentecanyon.app.domain.model

/**
 * What the site holds about the flow of a logged outing. The app keeps no flow of its own: a flow
 * exists only as a report published on the site, which the logbook reads back.
 */
sealed interface LogbookDebit {
    /** No report for that canyon and day. */
    data object None : LogbookDebit

    /** The report is written and waits for the network to be sent. */
    data object Waiting : LogbookDebit

    /** Sent from the app at [atEpochMs]; the site's copy was not read back (no account, or not listed yet). */
    data class Sent(val atEpochMs: Long) : LogbookDebit

    /** The report as the site shows it on the account's list. */
    data class Published(val report: SiteObservation) : LogbookDebit
}

/** The flow of the day when the site gives one. */
val LogbookDebit.level: NiveauDebit?
    get() = (this as? LogbookDebit.Published)?.report?.debitLevel?.takeIf { it != NiveauDebit.INCONNU }
