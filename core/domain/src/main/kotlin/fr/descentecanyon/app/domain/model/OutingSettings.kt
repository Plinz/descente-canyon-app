package fr.descentecanyon.app.domain.model

import kotlin.math.roundToInt

/** How experienced the group is: beginners slow the descent and the gearing up. */
enum class GroupLevel {
    AUTONOMOUS,
    WITH_BEGINNERS,

    /** Only beginners, guided: a discovery outing. */
    DISCOVERY,
}

/**
 * The user's coefficients for the times of an outing, set in the settings. The times of a sheet are
 * those of a small autonomous group: the coefficients stretch them for the group of the day.
 */
data class OutingSettings(
    /** Gearing up and briefing at the parking, for an autonomous group of up to four. */
    val prepMinutes: Int = 20,
    /** Changing and packing at the car after the outing. */
    val packUpMinutes: Int = 20,
    /** Leaving a car at the end of the canyon and getting into the other one, on top of the drive. */
    val shuttleHandlingMinutes: Int = 10,
    /** Descent (and time at the parking) of a group of five or six. */
    val mediumGroupFactor: Double = 1.15,
    /** Of seven or eight. */
    val largeGroupFactor: Double = 1.3,
    /** Of nine and more. */
    val hugeGroupFactor: Double = 1.5,
    val beginnersFactor: Double = 1.3,
    val discoveryFactor: Double = 1.6,
    /** Gearing up and packing take longer with beginners (fitting wetsuits and harnesses). */
    val beginnersPrepFactor: Double = 1.5,
    /** How much earlier than the sheet a walk or descent may end, in percent. */
    val earlierPercent: Int = 10,
    /** How much later, in percent: the latest return and the daylight check use it. */
    val laterPercent: Int = 25,
    /** Stretches or shortens the sheet times by the pace measured in the logbook. */
    val usePersonalPace: Boolean = true,
    /** The group planned by default. */
    val defaultGroupSize: Int = SMALL_GROUP_MAX,
    val defaultGroupLevel: GroupLevel = GroupLevel.AUTONOMOUS,
) {
    /** Coefficient of the descent for a group of [size]: rappels take longer with each person. */
    fun groupFactor(size: Int): Double = when {
        size <= SMALL_GROUP_MAX -> 1.0
        size <= MEDIUM_GROUP_MAX -> mediumGroupFactor
        size <= LARGE_GROUP_MAX -> largeGroupFactor
        else -> hugeGroupFactor
    }

    fun levelFactor(level: GroupLevel): Double = when (level) {
        GroupLevel.AUTONOMOUS -> 1.0
        GroupLevel.WITH_BEGINNERS -> beginnersFactor
        GroupLevel.DISCOVERY -> discoveryFactor
    }

    fun prepLevelFactor(level: GroupLevel): Double = if (level == GroupLevel.AUTONOMOUS) 1.0 else beginnersPrepFactor

    /** The same settings with every value brought back within its bounds (a file edited by hand, an old version). */
    fun sanitized(): OutingSettings = copy(
        prepMinutes = prepMinutes.coerceIn(MINUTES_RANGE),
        packUpMinutes = packUpMinutes.coerceIn(MINUTES_RANGE),
        shuttleHandlingMinutes = shuttleHandlingMinutes.coerceIn(MINUTES_RANGE),
        mediumGroupFactor = mediumGroupFactor.roundedFactor(),
        largeGroupFactor = largeGroupFactor.roundedFactor(),
        hugeGroupFactor = hugeGroupFactor.roundedFactor(),
        beginnersFactor = beginnersFactor.roundedFactor(),
        discoveryFactor = discoveryFactor.roundedFactor(),
        beginnersPrepFactor = beginnersPrepFactor.roundedFactor(),
        earlierPercent = earlierPercent.coerceIn(0, MAX_EARLIER_PERCENT),
        laterPercent = laterPercent.coerceIn(0, MAX_LATER_PERCENT),
        defaultGroupSize = defaultGroupSize.coerceIn(GROUP_SIZE_RANGE),
    )

    companion object {
        val DEFAULT = OutingSettings()

        const val SMALL_GROUP_MAX = 4
        const val MEDIUM_GROUP_MAX = 6
        const val LARGE_GROUP_MAX = 8

        val GROUP_SIZE_RANGE = 1..30
        val MINUTES_RANGE = 0..120
        const val MIN_FACTOR = 1.0
        const val MAX_FACTOR = 3.0
        const val FACTOR_STEP = 0.05
        const val MAX_EARLIER_PERCENT = 50
        const val MAX_LATER_PERCENT = 100

        private fun Double.roundedFactor(): Double = ((coerceIn(MIN_FACTOR, MAX_FACTOR) / FACTOR_STEP).roundToInt() * FACTOR_STEP)
            .let { (it * 100).roundToInt() / 100.0 }
    }
}

/**
 * The user's own pace, from the outings timed in the logbook: how long they took against the times
 * of the sheets. 1.1 means 10 % longer than the sheets.
 */
data class PersonalPace(val ratio: Double, val outings: Int) {
    companion object {
        /** Fewer timed outings say nothing reliable. */
        const val MIN_OUTINGS = 3
        const val MIN_RATIO = 0.6
        const val MAX_RATIO = 2.0

        /** Measured durations beyond these bounds of the sheet are a forgotten "I'm back" or a night out, not a pace. */
        private const val MIN_PLAUSIBLE_RATIO = 0.3
        private const val MAX_PLAUSIBLE_RATIO = 4.0

        /**
         * The median ratio of each timed outing (minutes from leaving the car to being back at it)
         * to the middle of its sheet total; null with fewer than [MIN_OUTINGS] usable outings.
         */
        fun of(samples: List<Pair<Int, DurationRange>>): PersonalPace? {
            val ratios = samples.mapNotNull { (measured, sheet) ->
                val middle = (sheet.minMinutes + sheet.maxMinutes) / 2.0
                if (middle <= 0.0 || measured <= 0) return@mapNotNull null
                (measured / middle).takeIf { it in MIN_PLAUSIBLE_RATIO..MAX_PLAUSIBLE_RATIO }
            }.sorted()
            if (ratios.size < MIN_OUTINGS) return null
            val median = if (ratios.size % 2 == 1) {
                ratios[ratios.size / 2]
            } else {
                (ratios[ratios.size / 2 - 1] + ratios[ratios.size / 2]) / 2
            }
            return PersonalPace(ratio = (median.coerceIn(MIN_RATIO, MAX_RATIO) * 100).roundToInt() / 100.0, outings = ratios.size)
        }
    }
}

/**
 * The coefficients applied to the sheet for the group of the day, kept apart so the screen can say
 * where an estimate comes from.
 */
data class OutingPace(
    val groupSize: Int,
    val level: GroupLevel,
    val groupFactor: Double,
    val levelFactor: Double,
    val prepFactor: Double,
    val personalPace: PersonalPace? = null,
) {
    private val personalRatio: Double get() = personalPace?.ratio ?: 1.0

    /** The descent: rappels and obstacles take longer with more people and with beginners. */
    val descentFactor: Double get() = groupFactor * levelFactor * personalRatio

    /** Approach and return walks: only the user's own pace. */
    val walkFactor: Double get() = personalRatio

    /** Gearing up and packing: longer with more people and with beginners. */
    val parkingFactor: Double get() = groupFactor * prepFactor

    companion object {
        fun of(settings: OutingSettings, groupSize: Int, level: GroupLevel, personalPace: PersonalPace?) = OutingPace(
            groupSize = groupSize,
            level = level,
            groupFactor = settings.groupFactor(groupSize),
            levelFactor = settings.levelFactor(level),
            prepFactor = settings.prepLevelFactor(level),
            personalPace = personalPace?.takeIf { settings.usePersonalPace },
        )
    }
}
