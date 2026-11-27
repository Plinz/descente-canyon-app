package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CommunityTrack
import fr.descentecanyon.app.domain.model.CommunityTracks
import fr.descentecanyon.app.domain.model.TrackRole
import fr.descentecanyon.app.domain.model.TrackVote
import java.time.Duration
import kotlinx.coroutines.flow.Flow

/** GPS tracks shared by the people using the app, ranked by their votes (no moderation). */
interface CommunityTrackRepository {
    /** The tracks of [canyonId] last read from the server, kept for offline use; null before any read. */
    fun observeTracks(canyonId: Int): Flow<CommunityTracks?>

    suspend fun refresh(canyonId: Int): Result<CommunityTracks>

    /**
     * Reads again the tracks of the [canyonIds] never read or read more than [maxAge] ago, so they
     * are on the phone in the canyon, out of network; returns how many canyons were read. Stops at
     * the first failure of the network.
     */
    suspend fun prefetch(canyonIds: Collection<Int>, maxAge: Duration): Result<Int>

    /** Sends a GPX file; the server checks it belongs to the canyon and keeps only its geometry. */
    suspend fun upload(canyonId: Int, role: TrackRole, gpx: ByteArray): Result<CommunityTrack>

    suspend fun vote(track: CommunityTrack, vote: TrackVote): Result<CommunityTrack>

    /** Deletes a track sent from this installation. */
    suspend fun delete(track: CommunityTrack): Result<Unit>
}
