package fr.descentecanyon.app.domain.model

/** Who a piece of gear is for. */
enum class GearGroup {
    /** The user's own gear. */
    PERSONAL,

    /** Gear of the group: ropes, gear of whoever rigs and assists. */
    SHARED,

    /** What the group needs to give the alert and wait for the rescuers. */
    SAFETY,

    /** What accompanied beginners bring. */
    GUESTS_BRING,

    /** What the user lends to accompanied beginners. */
    GUESTS_PROVIDED,
}

/**
 * Gear the app suggests, after the "Règles techniques et de sécurité canyonisme" (FFCAM, FFME, FFS,
 * 2024, §6.2) and the EFC gear list for its courses (2022); the screens name it in the user's
 * language. A key removed later is dropped from the stored settings.
 */
enum class GearKey(val group: GearGroup) {
    HELMET(GearGroup.PERSONAL),
    WETSUIT(GearGroup.PERSONAL),
    NEOPRENE_SOCKS(GearGroup.PERSONAL),
    SHOES(GearGroup.PERSONAL),
    HARNESS(GearGroup.PERSONAL),
    LANYARDS(GearGroup.PERSONAL),
    DESCENDER(GearGroup.PERSONAL),
    KNIFE(GearGroup.PERSONAL),
    WHISTLE(GearGroup.PERSONAL),
    CANYON_BAG(GearGroup.PERSONAL),
    DRY_BAG(GearGroup.PERSONAL),
    SURVIVAL_BLANKET(GearGroup.PERSONAL),
    WATER_AND_SNACK(GearGroup.PERSONAL),
    HEADLAMP(GearGroup.PERSONAL),
    SPARE_ROPE(GearGroup.SHARED),
    ROPE_BAG(GearGroup.SHARED),
    RELEASABLE_SYSTEM(GearGroup.SHARED),
    ASCENDING_KIT(GearGroup.SHARED),
    PULLEY(GearGroup.SHARED),
    ANCHOR_KIT(GearGroup.SHARED),
    SPARE_DESCENDER(GearGroup.SHARED),
    DIVING_MASK(GearGroup.SHARED),
    FIRST_AID(GearGroup.SAFETY),
    PHONE(GearGroup.SAFETY),
    WARMING_KIT(GearGroup.SAFETY),
    SWIMSUIT(GearGroup.GUESTS_BRING),
    WATER_SHOES(GearGroup.GUESTS_BRING),
    WATER(GearGroup.GUESTS_BRING),
    PICNIC(GearGroup.GUESTS_BRING),
    DRY_CLOTHES(GearGroup.GUESTS_BRING),
    TOWEL(GearGroup.GUESTS_BRING),
    GUEST_WETSUIT(GearGroup.GUESTS_PROVIDED),
    GUEST_SOCKS(GearGroup.GUESTS_PROVIDED),
    GUEST_HELMET(GearGroup.GUESTS_PROVIDED),
    GUEST_HARNESS(GearGroup.GUESTS_PROVIDED),
}

/** Gear an outing calls for by itself. */
enum class GearHint(val group: GearGroup) {
    /** The rope length of the sheet. */
    ROPE(GearGroup.SHARED),

    /** A shuttle needs two cars and their keys. */
    SHUTTLE_CARS(GearGroup.SHARED),

    /** A long day: a real meal and more water. */
    FOOD_AND_WATER(GearGroup.PERSONAL),
}

sealed interface GearItem {
    /** Stable id, kept in the outing when the item is checked. */
    val id: String
    val group: GearGroup

    data class Suggested(val key: GearKey) : GearItem {
        override val id: String get() = "gear:${key.name}"
        override val group: GearGroup get() = key.group
    }

    /** [value]: the rope length in metres, the shuttle distance in km. */
    data class Hint(val hint: GearHint, val value: Int? = null) : GearItem {
        override val id: String get() = "hint:${hint.name}"
        override val group: GearGroup get() = hint.group
    }

    data class Custom(override val id: String, override val group: GearGroup, val label: String) : GearItem
}

/** The user's changes to the suggested gear: items taken off and items added. */
data class GearListSettings(val hidden: Set<GearKey> = emptySet(), val custom: List<GearItem.Custom> = emptyList())

/** What the outing tells about the gear to take. */
data class GearContext(
    val ropeLengthMeters: Int? = null,
    val shuttleKm: Int? = null,
    /** From leaving the car to being back at it, at worst, in minutes. */
    val longestOnFootMinutes: Long? = null,
)

/**
 * The checklist of an outing: the user's list (suggested gear not taken off, then their own items)
 * and what this outing calls for, grouped as [GearGroup] orders them.
 */
fun gearChecklist(settings: GearListSettings, context: GearContext): List<GearItem> {
    val hints = buildList {
        context.ropeLengthMeters?.takeIf { it > 0 }?.let { add(GearItem.Hint(GearHint.ROPE, it)) }
        context.shuttleKm?.let { add(GearItem.Hint(GearHint.SHUTTLE_CARS, it)) }
        if ((context.longestOnFootMinutes ?: 0) >= LONG_OUTING_MINUTES) add(GearItem.Hint(GearHint.FOOD_AND_WATER))
    }
    val suggested = GearKey.entries.filterNot { it in settings.hidden }.map { GearItem.Suggested(it) }
    return GearGroup.entries.flatMap { group ->
        (hints + suggested + settings.custom).filter { it.group == group }
    }
}

/** From five hours on foot, the day is long enough to plan food and water. */
const val LONG_OUTING_MINUTES = 5 * 60L
