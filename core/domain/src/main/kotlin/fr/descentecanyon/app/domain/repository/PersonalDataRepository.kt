package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.PersonalDataImport

/** The user's own data as files: to keep a copy, read it elsewhere or move to another phone. */
interface PersonalDataRepository {
    /** Logbook, memos and favorites in a JSON file that [importJson] reads back. */
    suspend fun exportJson(): String

    /** The logbook as a spreadsheet (CSV), one outing per line. */
    suspend fun exportLogbookCsv(): String

    /** Reads a file written by [exportJson]: outings, memos and favorites are added, never removed. */
    suspend fun importJson(content: String): Result<PersonalDataImport>

    /** Deletes the logbook and the memos. */
    suspend fun clearLogbookAndMemos()
}
