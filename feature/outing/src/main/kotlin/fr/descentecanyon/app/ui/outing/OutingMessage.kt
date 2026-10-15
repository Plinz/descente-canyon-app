package fr.descentecanyon.app.ui.outing

import fr.descentecanyon.app.domain.model.GeoPoint
import java.util.Locale

/** Localized sentences of the message sent to a relative, each with its placeholders filled by [buildOutingMessage]. */
internal data class OutingMessageTexts(
    val header: (name: String, cotation: String) -> String,
    val place: (place: String) -> String,
    val whenWithReturn: (day: String, start: String, back: String) -> String,
    val whenWithoutReturn: (day: String, start: String) -> String,
    val alert: (alertTime: String, number: String) -> String,
    val downstreamParking: (coordinates: String) -> String,
    val upstreamParking: (coordinates: String) -> String,
    val sheet: (url: String) -> String,
)

internal data class OutingMessageData(
    val canyonName: String,
    val cotation: String,
    val place: String,
    val day: String,
    val start: String,
    val back: String?,
    val alertTime: String,
    val emergencyNumber: String,
    val downstreamParking: GeoPoint?,
    val upstreamParking: GeoPoint?,
    val sheetUrl: String,
)

/**
 * What a relative needs to raise the alert: where, when, until when, whom to call, and where the
 * cars are (rescuers start from the parkings).
 */
internal fun buildOutingMessage(texts: OutingMessageTexts, data: OutingMessageData): String = buildList {
    add(texts.header(data.canyonName, data.cotation))
    if (data.place.isNotBlank()) add(texts.place(data.place))
    add(data.back?.let { texts.whenWithReturn(data.day, data.start, it) } ?: texts.whenWithoutReturn(data.day, data.start))
    add(texts.alert(data.alertTime, data.emergencyNumber))
    data.downstreamParking?.let { add(texts.downstreamParking(it.coordinatesWithMapLink())) }
    data.upstreamParking?.let { add(texts.upstreamParking(it.coordinatesWithMapLink())) }
    add(texts.sheet(data.sheetUrl))
}.joinToString("\n")

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
