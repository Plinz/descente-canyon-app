package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.AccidentReport
import kotlinx.coroutines.flow.Flow

/**
 * The report of the accident under way, kept on the phone so that it survives the emergency screen
 * being closed or the app being stopped. Forgotten [AccidentReport.EXPIRY] after its last change.
 */
interface AccidentReportRepository {
    fun observe(): Flow<AccidentReport?>

    suspend fun save(report: AccidentReport)

    suspend fun clear()
}
