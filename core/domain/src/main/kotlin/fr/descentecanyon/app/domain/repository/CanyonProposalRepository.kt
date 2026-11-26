package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonPlace
import fr.descentecanyon.app.domain.model.CanyonProposal
import fr.descentecanyon.app.domain.model.CanyonSheet
import fr.descentecanyon.app.domain.model.NearbyCanyon
import kotlinx.coroutines.flow.Flow

/**
 * Canyons proposed to the app's server: a new canyon, or a change to a canyon added in the app
 * (never to a sheet of the site). Nothing is published before the operator reviews it.
 */
interface CanyonProposalRepository {
    /**
     * Proposals can be sent: the app has its server, the person keeps it on, and the operator did
     * not close proposals.
     */
    fun observeAvailable(): Flow<Boolean>

    /** The proposals of this installation as last read from the server, newest first. */
    fun observeMine(): Flow<List<CanyonProposal>>

    suspend fun refreshMine(): Result<List<CanyonProposal>>

    /** Sends [sheet] as a new canyon ([canyonId] null) or as a change to the app's canyon [canyonId]. */
    suspend fun propose(sheet: CanyonSheet, canyonId: Int?): Result<CanyonProposal>

    /** Takes back a proposal not reviewed yet. */
    suspend fun withdraw(proposal: CanyonProposal): Result<Unit>

    /** The current sheet of a canyon added in the app, to start a change from it. */
    suspend fun sheetOf(canyonId: Int): Result<CanyonSheet>

    /** Country, region, department... of the catalog canyon nearest to a point, within a few km. */
    suspend fun placeNear(latitude: Double, longitude: Double): CanyonPlace?

    /** Catalog canyons around a point, nearest first: the canyon may be there already. */
    suspend fun canyonsNear(latitude: Double, longitude: Double, maxDistanceKm: Double): List<NearbyCanyon>
}
