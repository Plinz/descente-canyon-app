package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.PublishedShare
import fr.descentecanyon.app.domain.model.ReceivedShare
import fr.descentecanyon.app.domain.model.SharedContent
import kotlinx.coroutines.flow.Flow

/**
 * Short links of outings, stays and lists, kept by the app's server under a random code. Only what
 * the person chose to share is sent, with the random key of this installation that lets it change
 * or delete its own links.
 */
interface ShareRepository {
    /** Short links can be made now (server built in, switched on, and offering them). */
    fun observeAvailable(): Flow<Boolean>

    /** The link of [content]: [code] updated when this phone made it, else a new one. */
    suspend fun publish(content: SharedContent, code: String? = null): Result<PublishedShare>

    /** What the link [code] holds now. */
    suspend fun fetch(code: String): Result<ReceivedShare>

    /** Deletes this phone's link [code]. */
    suspend fun delete(code: String): Result<Unit>
}
