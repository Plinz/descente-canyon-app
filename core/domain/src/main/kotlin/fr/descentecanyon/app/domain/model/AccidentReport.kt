package fr.descentecanyon.app.domain.model

import java.time.Duration
import java.time.Instant

/**
 * What the rescuers ask about an accident, in the order of the alert sheets of the FFS and the FFCAM
 * canyon commissions: when, how many people, what happened, the state of the injured person, the
 * water, the exact place. Every answer is optional: the call comes first. [checks] follow the state
 * of the injured person while waiting, as the FFS follow-up sheet does.
 */
data class AccidentReport(
    val accidentTime: Instant? = null,
    val injuredCount: Int? = null,
    val peopleOnSite: Int? = null,
    val circumstances: Set<AccidentCircumstance> = emptySet(),
    val conscious: Boolean? = null,
    val breathing: Boolean? = null,
    val heavyBleeding: Boolean? = null,
    val canWalk: Boolean? = null,
    val injuries: Set<InjuryArea> = emptySet(),
    val waterRising: Boolean? = null,
    /** Last obstacle crossed, or the closest landmark of the topo. */
    val location: String = "",
    val checks: List<VictimCheck> = emptyList(),
) {
    /** Whether something of the accident itself was noted, the follow-up aside. */
    val hasAnswers: Boolean
        get() = accidentTime != null || injuredCount != null || peopleOnSite != null || circumstances.isNotEmpty() ||
            conscious != null || breathing != null || heavyBleeding != null || canWalk != null ||
            injuries.isNotEmpty() || waterRising != null || location.isNotBlank()

    val isEmpty: Boolean get() = !hasAnswers && checks.isEmpty()

    companion object {
        /** A report left untouched this long belongs to an accident that is over. */
        val EXPIRY: Duration = Duration.ofHours(24)

        const val MAX_COUNT = 30
        const val MAX_LOCATION_LENGTH = 120
        const val MAX_NOTE_LENGTH = 200
    }
}

/** How the accident happened, as listed by the alert sheets. */
enum class AccidentCircumstance {
    FALL,
    JUMP,
    SLIDE,
    STUCK_ON_ROPE,
    DROWNING,
    ROCKFALL,
    FLOOD,
    COLD_EXHAUSTION,
    ILLNESS,
}

/** Where the person is hurt. */
enum class InjuryArea {
    HEAD,
    BACK_NECK,
    ARM_SHOULDER,
    LEG_ANKLE,
    BLEEDING_WOUND,
}

/** Whether the injured person answers questions: yes, confused, no. */
enum class Responsiveness {
    NORMAL,
    CONFUSED,
    NONE,
}

/** Breathing difficulty: none, some, a lot. */
enum class BreathingTrouble {
    NONE,
    SOME,
    SEVERE,
}

/** One look at the injured person while waiting for the rescuers. */
data class VictimCheck(
    val at: Instant,
    val responds: Responsiveness? = null,
    val breathing: BreathingTrouble? = null,
    /** From 0 (none) to 10 (unbearable). */
    val pain: Int? = null,
    val cold: Boolean? = null,
    val note: String = "",
) {
    companion object {
        val PAIN_RANGE = 0..10
    }
}
