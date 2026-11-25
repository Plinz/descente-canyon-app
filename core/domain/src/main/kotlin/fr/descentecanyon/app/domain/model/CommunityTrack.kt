package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.time.YearMonth

/**
 * A GPS track shared through the app's server by someone who walked it. There is no moderator:
 * the server rejects what cannot be a track of the canyon, and the votes of the people who used a
 * track endorse it or set it aside. Tracks carry no name or comment: nothing personal, nothing to
 * moderate.
 */
data class CommunityTrack(
    val id: String,
    val canyonId: Int,
    val role: TrackRole,
    val createdAt: Instant,
    /** Month of the outing, read from the GPX file when it had times (the times themselves are dropped). */
    val recordedMonth: YearMonth?,
    val lengthMeters: Int,
    val ascentMeters: Int?,
    val descentMeters: Int?,
    val segments: List<List<TrackPoint>>,
    val bounds: GeoBounds,
    val upVotes: Int,
    val downVotes: Int,
    val standing: TrackStanding,
    /** The best endorsed track of its role for this canyon. */
    val isReference: Boolean,
    /** Sent from this installation of the app (it can delete it, it cannot vote for it). */
    val isMine: Boolean,
    val myVote: TrackVote,
)

data class TrackPoint(val latitude: Double, val longitude: Double, val elevation: Double? = null)

enum class TrackRole {
    /** From the car park to the start of the descent. */
    APPROACH,

    /** From the end of the descent back to a car park. */
    RETURN,

    /** A way out of the canyon before its end. */
    ESCAPE,

    /** The whole outing: approach, descent and return. */
    FULL,
}

enum class TrackStanding {
    /** Few or no votes yet. */
    NEW,

    /** Confirmed by several people. */
    ENDORSED,

    /** At least one person found it wrong. */
    DISPUTED,

    /** Found wrong by several people: hidden unless asked for. */
    DISCREDITED,
}

enum class TrackVote(val value: Int) {
    UP(1),
    NONE(0),
    DOWN(-1),
    ;

    companion object {
        fun of(value: Int): TrackVote = entries.firstOrNull { it.value == value } ?: NONE
    }
}

/** The shared tracks of a canyon as last read from the server (shown offline too). */
data class CommunityTracks(val canyonId: Int, val tracks: List<CommunityTrack>, val fetchedAt: Instant) {
    val visible: List<CommunityTrack> get() = tracks.filter { it.standing != TrackStanding.DISCREDITED }
    val discredited: List<CommunityTrack> get() = tracks.filter { it.standing == TrackStanding.DISCREDITED }
}

/** Why the server refused a track or a vote. */
enum class TrackRejection {
    INVALID_GPX,
    TOO_FEW_POINTS,
    TOO_MANY_POINTS,
    TOO_SHORT,
    TOO_LONG,
    TOO_FAR,
    UNLOCATED_CANYON,
    DUPLICATE,
    TOO_LARGE,
    RATE_LIMITED,
    OWN_TRACK,
    NOT_AUTHOR,
}
