package fr.descentecanyon.app.domain.model

/** Who a piece of gear is for. */
enum class GearGroup {
    /** The user's own gear. */
    PERSONAL,

    /** Gear of the group: ropes, rescue kit. */
    SHARED,

    /** What accompanied beginners bring. */
    GUESTS_BRING,

    /** What the user lends to accompanied beginners. */
    GUESTS_PROVIDED,
}

/** Gear the app suggests; the screens name it in the user's language. */
enum class GearKey(val group: GearGroup) {
    HELMET(GearGroup.PERSONAL),
    HARNESS(GearGroup.PERSONAL),
    DESCENDER(GearGroup.PERSONAL),
    LANYARDS(GearGroup.PERSONAL),
    WETSUIT(GearGroup.PERSONAL),
    SHOES(GearGroup.PERSONAL),
    DRY_BAG(GearGroup.PERSONAL),
    KNIFE(GearGroup.PERSONAL),
    WHISTLE(GearGroup.PERSONAL),
    HEADLAMP(GearGroup.PERSONAL),
    RESCUE_KIT(GearGroup.SHARED),
    FIRST_AID(GearGroup.SHARED),
    SURVIVAL_BLANKET(GearGroup.SHARED),
    SWIMSUIT(GearGroup.GUESTS_BRING),
    WATER_SHOES(GearGroup.GUESTS_BRING),
    WATER(GearGroup.GUESTS_BRING),
    PICNIC(GearGroup.GUESTS_BRING),
    DRY_CLOTHES(GearGroup.GUESTS_BRING),
    TOWEL(GearGroup.GUESTS_BRING),
    GUEST_WETSUIT(GearGroup.GUESTS_PROVIDED),
    GUEST_HELMET(GearGroup.GUESTS_PROVIDED),
    GUEST_HARNESS(GearGroup.GUESTS_PROVIDED),
}

/** Gear an outing calls for by itself. */
enum class GearHint(val group: GearGroup) {
    /** The rope length of the sheet. */
    ROPE(GearGroup.SHARED),

    /** A shuttle needs two cars and their keys. */
    SHUTTLE_CARS(GearGroup.SHARED),

    /** A long day: water and food for everyone. */
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
