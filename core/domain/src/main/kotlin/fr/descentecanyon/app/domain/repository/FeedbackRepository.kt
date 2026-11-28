package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.FeedbackContact
import fr.descentecanyon.app.domain.model.FeedbackDraft
import fr.descentecanyon.app.domain.model.FeedbackMessage
import fr.descentecanyon.app.domain.model.FeedbackSendResult
import fr.descentecanyon.app.domain.model.FeedbackTechnicalInfo
import kotlinx.coroutines.flow.Flow

/** The idea box: messages sent to the app's server, and what the operator answered. */
interface FeedbackRepository {
    /** Messages can be sent: the app has its server, the person keeps it on, and the server takes them. */
    fun observeAvailable(): Flow<Boolean>

    /** The messages waiting for the network first, then those of the server as last read, newest first. */
    fun observeMine(): Flow<List<FeedbackMessage>>

    suspend fun refreshMine(): Result<List<FeedbackMessage>>

    /**
     * Sends [draft]; without network it is kept on the phone and leaves later ([FeedbackSendResult.QUEUED]).
     * A refusal of the server fails with [fr.descentecanyon.app.domain.error.AppError.FeedbackRefused].
     */
    suspend fun send(draft: FeedbackDraft): Result<FeedbackSendResult>

    /** Sends the messages kept on the phone; fails when some are still waiting (to retry later). */
    suspend fun sendQueued(): Result<Unit>

    /** Deletes a message, from the server too when it reached it. */
    suspend fun delete(message: FeedbackMessage): Result<Unit>

    /** The replies shown so far are no longer new. */
    suspend fun markRepliesSeen()

    /** The details joined by default: app and Android versions, device, language. */
    fun technicalInfo(): FeedbackTechnicalInfo

    /**
     * The descente-canyon.com account the app is logged in with and its e-mail address, read from the
     * account page of the site (kept once read); null when logged out. The address is null when the
     * page could not be read.
     */
    suspend fun contact(): FeedbackContact?
}
