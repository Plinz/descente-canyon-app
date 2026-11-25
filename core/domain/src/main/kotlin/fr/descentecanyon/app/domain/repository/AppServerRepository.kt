package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.AppServerState
import kotlinx.coroutines.flow.Flow

/** The app's own server: signed manifest (messages, switches, catalog updates). */
interface AppServerRepository {
    fun observeState(): Flow<AppServerState>

    /**
     * Reads the manifest when the server is active and schedules the download of a newer catalog.
     * A failure keeps the previous state: the server is never needed for the app to work.
     */
    suspend fun refresh(): Result<Unit>

    /** [refresh] when the last check is older than [maxAge] (the debit estimates change a few times a day). */
    suspend fun refreshIfOlderThan(maxAge: java.time.Duration): Result<Unit> = refresh()

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setCatalogOnMobileData(enabled: Boolean)

    /** Hides a server message on this phone. */
    suspend fun dismissMessage(id: String)
}
