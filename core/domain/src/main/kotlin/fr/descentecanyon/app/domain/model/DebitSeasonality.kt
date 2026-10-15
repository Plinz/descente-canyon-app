package fr.descentecanyon.app.domain.model

import java.time.Month

/** Flow reports of one calendar month, all years together. */
data class MonthlyDebitProfile(val month: Month, val reportCount: Int, val levelCounts: Map<NiveauDebit, Int>) {
    /** Share of the month's reports at [level], between 0 and 1. */
    fun share(level: NiveauDebit): Double = if (reportCount == 0) 0.0 else (levelCounts[level] ?: 0).toDouble() / reportCount
}

/** Monthly distribution of the reported flow levels, January first; reports without a level are ignored. */
fun List<Debit>.monthlyProfiles(): List<MonthlyDebitProfile> {
    val byMonth = filter { it.niveau != NiveauDebit.INCONNU }.groupBy { it.date.month }
    return Month.entries.map { month ->
        val reports = byMonth[month].orEmpty()
        MonthlyDebitProfile(month = month, reportCount = reports.size, levelCounts = reports.groupingBy { it.niveau }.eachCount())
    }
}
