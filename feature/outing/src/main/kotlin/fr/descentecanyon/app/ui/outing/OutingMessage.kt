package fr.descentecanyon.app.ui.outing

import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.MessageAudience
import fr.descentecanyon.app.domain.model.MessageSection
import java.util.Locale

/** Localized sentences of the outing messages: Android resources in the app (in the language chosen), plain text in tests. */
internal interface OutingMessageStrings {
    fun headerRelative(name: String, cotation: String): String
    fun headerTeam(name: String, cotation: String, day: String): String
    fun headerGuests(name: String, day: String): String
    fun place(place: String): String
    fun meetingAtParking(time: String, coordinates: String): String
    fun meetingAtParkingUnknown(time: String): String
    fun meetingAtPlace(time: String, place: String): String
    fun scheduleDay(day: String, steps: String): String
    fun schedule(steps: String): String
    fun stepLeavePlace(place: String, time: String): String
    fun stepWalk(time: String): String
    fun stepDescent(time: String): String
    fun stepBack(time: String): String
    fun stepBackRange(time: String, earliest: String, latest: String): String
    fun stepBackAtPlace(place: String, time: String): String
    fun stepApproach(duration: String): String
    fun stepInCanyon(duration: String): String
    fun group(size: Int): String
    fun safetyRelative(alertTime: String, number: String): String
    fun safetyOthers(alertTime: String, number: String): String
    fun downstreamParking(coordinates: String): String
    fun upstreamParking(coordinates: String): String
    fun shuttle(km: Int): String
    fun canyon(approach: String, descent: String, back: String): String
    fun canyonFigures(cascadeMax: Int?, rope: Int?): String?
    fun weather(forecast: String): String
    fun storm(): String
    fun flowEstimate(level: String): String
    fun lastReport(level: String, day: String): String
    fun confirmTheDayBefore(): String
    fun sharedGear(items: String): String
    fun guestsBring(items: String): String
    fun guestsProvided(items: String): String
    fun sheet(url: String): String
    fun back(name: String, time: String): String
    fun delay(name: String, alertTime: String, number: String): String
}

/** Everything a message may say about the outing, already formatted for its language. */
internal data class OutingMessageData(
    val canyonName: String,
    val cotation: String,
    val place: String,
    val day: String,
    /** The starting place and when it is left, when the outing starts from one. */
    val startingPlace: String? = null,
    val leavePlace: String? = null,
    val backAtPlace: String? = null,
    val meetingTime: String,
    val meetAtPlace: Boolean = false,
    val meetingParking: GeoPoint? = null,
    val walkStart: String,
    val descentStart: String? = null,
    val back: String? = null,
    val backEarliest: String? = null,
    val backLatest: String? = null,
    val approachDuration: String? = null,
    val inCanyonDuration: String? = null,
    val groupSize: Int,
    val alertTime: String,
    val emergencyNumber: String,
    val downstreamParking: GeoPoint? = null,
    val upstreamParking: GeoPoint? = null,
    val shuttleKm: Int? = null,
    val sheetApproach: String? = null,
    val sheetDescent: String? = null,
    val sheetBack: String? = null,
    val cascadeMax: Int? = null,
    val rope: Int? = null,
    val forecast: String? = null,
    val storm: Boolean = false,
    val flowEstimate: String? = null,
    val lastReport: Pair<String, String>? = null,
    val sharedGear: List<String> = emptyList(),
    val guestsBring: List<String> = emptyList(),
    val guestsProvided: List<String> = emptyList(),
    val note: String = "",
    val sheetUrl: String,
)

/**
 * The message about an outing for [audience], with the [sections] the user kept, in a fixed order:
 * meeting, schedule, group, alert, parkings, the canyon, conditions, gear, note and sheet. Plain
 * text, so it reads the same in a text message, WhatsApp or Signal.
 */
internal fun buildOutingMessage(
    strings: OutingMessageStrings,
    data: OutingMessageData,
    audience: MessageAudience,
    sections: Set<MessageSection>,
): String = buildList {
    when (audience) {
        MessageAudience.RELATIVE -> {
            add(strings.headerRelative(data.canyonName, data.cotation))
            if (data.place.isNotBlank()) add(strings.place(data.place))
        }

        MessageAudience.TEAM -> add(strings.headerTeam(data.canyonName, data.cotation, data.day))

        MessageAudience.GUESTS -> add(strings.headerGuests(data.canyonName, data.day))
    }
    if (MessageSection.MEETING in sections) add(meeting(strings, data))
    if (MessageSection.SCHEDULE in sections) add(schedule(strings, data, audience))
    if (MessageSection.GROUP in sections) add(strings.group(data.groupSize))
    if (MessageSection.SAFETY in sections) {
        add(
            if (audience == MessageAudience.RELATIVE) {
                strings.safetyRelative(data.alertTime, data.emergencyNumber)
            } else {
                strings.safetyOthers(data.alertTime, data.emergencyNumber)
            },
        )
    }
    if (MessageSection.PLACE in sections) {
        data.downstreamParking?.let { add(strings.downstreamParking(it.coordinatesWithMapLink())) }
        data.upstreamParking?.let { add(strings.upstreamParking(it.coordinatesWithMapLink())) }
        data.shuttleKm?.let { add(strings.shuttle(it)) }
    }
    if (MessageSection.CANYON in sections) {
        if (data.sheetApproach != null && data.sheetDescent != null && data.sheetBack != null) {
            add(strings.canyon(data.sheetApproach, data.sheetDescent, data.sheetBack))
        }
        strings.canyonFigures(data.cascadeMax, data.rope)?.let(::add)
    }
    if (MessageSection.CONDITIONS in sections) {
        data.forecast?.let { add(strings.weather(it)) }
        if (data.storm) add(strings.storm())
        data.flowEstimate?.let { add(strings.flowEstimate(it)) }
        data.lastReport?.let { (level, day) -> add(strings.lastReport(level, day)) }
        if (audience == MessageAudience.GUESTS) add(strings.confirmTheDayBefore())
    }
    if (MessageSection.GEAR in sections) {
        if (audience == MessageAudience.GUESTS) {
            if (data.guestsBring.isNotEmpty()) add(strings.guestsBring(data.guestsBring.joinToString(", ")))
            if (data.guestsProvided.isNotEmpty()) add(strings.guestsProvided(data.guestsProvided.joinToString(", ")))
        } else if (data.sharedGear.isNotEmpty()) {
            add(strings.sharedGear(data.sharedGear.joinToString(", ")))
        }
    }
    data.note.trim().takeIf { it.isNotEmpty() }?.let(::add)
    if (MessageSection.SHEET in sections) add(strings.sheet(data.sheetUrl))
}.joinToString("\n")

private fun meeting(strings: OutingMessageStrings, data: OutingMessageData): String = when {
    data.meetAtPlace && data.startingPlace != null -> strings.meetingAtPlace(data.leavePlace ?: data.meetingTime, data.startingPlace)
    data.meetingParking != null -> strings.meetingAtParking(data.meetingTime, data.meetingParking.coordinatesWithMapLink())
    else -> strings.meetingAtParkingUnknown(data.meetingTime)
}

private fun schedule(strings: OutingMessageStrings, data: OutingMessageData, audience: MessageAudience): String {
    val back = when {
        data.back == null -> null

        audience == MessageAudience.TEAM && data.backEarliest != null && data.backLatest != null ->
            strings.stepBackRange(data.back, data.backEarliest, data.backLatest)

        else -> strings.stepBack(data.back)
    }
    val steps = when (audience) {
        MessageAudience.RELATIVE -> listOfNotNull(
            data.startingPlace?.let { place -> data.leavePlace?.let { strings.stepLeavePlace(place, it) } },
            strings.stepWalk(data.walkStart),
            back,
            data.startingPlace?.let { place -> data.backAtPlace?.let { strings.stepBackAtPlace(place, it) } },
        )

        MessageAudience.TEAM -> listOfNotNull(
            strings.stepWalk(data.walkStart),
            data.descentStart?.let(strings::stepDescent),
            back,
        )

        MessageAudience.GUESTS -> listOfNotNull(
            data.approachDuration?.let(strings::stepApproach),
            data.inCanyonDuration?.let(strings::stepInCanyon),
            back,
        )
    }.joinToString(", ")
    return if (audience == MessageAudience.RELATIVE) strings.scheduleDay(data.day, steps) else strings.schedule(steps)
}

/** Decimal coordinates readable over the phone, and a link that opens a map anywhere. */
internal fun GeoPoint.coordinatesWithMapLink(): String {
    val coordinates = String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)
    val link = String.format(
        Locale.ROOT,
        "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=15/%.5f/%.5f",
        latitude,
        longitude,
        latitude,
        longitude,
    )
    return "$coordinates ($link)"
}
