package fr.descentecanyon.app.domain.model

/** Whom a message about an outing is for: each needs other information. */
enum class MessageAudience(val defaultSections: Set<MessageSection>) {
    /** A relative who does not come: when to worry and what to tell the rescue. */
    RELATIVE(
        setOf(MessageSection.SCHEDULE, MessageSection.GROUP, MessageSection.SAFETY, MessageSection.PLACE, MessageSection.SHEET),
    ),

    /** Autonomous teammates: meeting, shuttle, the canyon, conditions and shared gear. */
    TEAM(
        setOf(
            MessageSection.MEETING,
            MessageSection.SCHEDULE,
            MessageSection.PLACE,
            MessageSection.CANYON,
            MessageSection.CONDITIONS,
            MessageSection.GEAR,
            MessageSection.SHEET,
        ),
    ),

    /** Accompanied beginners: meeting, what to expect and what to bring. */
    GUESTS(setOf(MessageSection.MEETING, MessageSection.SCHEDULE, MessageSection.CONDITIONS, MessageSection.GEAR)),
}

/** Kinds of information a message can carry, chosen one by one. */
enum class MessageSection {
    MEETING,
    SCHEDULE,
    PLACE,
    CANYON,
    CONDITIONS,
    GEAR,
    GROUP,
    SAFETY,
    SHEET,
}
