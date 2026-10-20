package fr.descentecanyon.app.ui.outing

import android.content.res.Resources
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDuration
import fr.descentecanyon.app.domain.model.DailyWeather
import fr.descentecanyon.app.domain.model.GearGroup
import fr.descentecanyon.app.domain.model.OutingPhase
import fr.descentecanyon.app.domain.model.WeatherCondition
import fr.descentecanyon.app.ui.components.debitLevelLabelRes
import fr.descentecanyon.app.ui.components.debitRangeWordRes
import fr.descentecanyon.app.ui.components.relativeDayLabel
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** [OutingMessageStrings] from the app resources, in the language of [resources]. */
internal class ResourceOutingMessageStrings(private val resources: Resources) : OutingMessageStrings {
    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    override fun headerRelative(name: String, cotation: String) = text(R.string.outing_message_header, name, cotation)
    override fun headerTeam(name: String, cotation: String, day: String) = text(R.string.outing_message_header_team, name, cotation, day)
    override fun headerGuests(name: String, day: String) = text(R.string.outing_message_header_guests, name, day)
    override fun place(place: String) = text(R.string.outing_message_place, place)
    override fun meetingAtParking(time: String, coordinates: String) = text(R.string.outing_message_meeting_parking, time, coordinates)
    override fun meetingAtParkingUnknown(time: String) = text(R.string.outing_message_meeting_parking_unknown, time)
    override fun meetingAtPlace(time: String, place: String) = text(R.string.outing_message_meeting_place, time, place)
    override fun scheduleDay(day: String, steps: String) = text(R.string.outing_message_schedule_day, day, steps)
    override fun schedule(steps: String) = text(R.string.outing_message_schedule, steps)
    override fun stepLeavePlace(place: String, time: String) = text(R.string.outing_message_step_leave_place, place, time)
    override fun stepWalk(time: String) = text(R.string.outing_message_step_walk, time)
    override fun stepDescent(time: String) = text(R.string.outing_message_step_descent, time)
    override fun stepBack(time: String) = text(R.string.outing_message_step_back, time)
    override fun stepBackRange(time: String, earliest: String, latest: String) =
        text(R.string.outing_message_step_back_range, time, earliest, latest)
    override fun stepBackAtPlace(place: String, time: String) = text(R.string.outing_message_step_back_place, place, time)
    override fun stepApproach(duration: String) = text(R.string.outing_message_step_approach, duration)
    override fun stepInCanyon(duration: String) = text(R.string.outing_message_step_in_canyon, duration)
    override fun group(size: Int): String = resources.getQuantityString(R.plurals.outing_message_group, size, size)
    override fun safetyRelative(alertTime: String, number: String) = text(R.string.outing_message_alert, alertTime, number)
    override fun safetyOthers(alertTime: String, number: String) = text(R.string.outing_message_alert_others, alertTime, number)
    override fun downstreamParking(coordinates: String) = text(R.string.outing_message_parking_downstream, coordinates)
    override fun upstreamParking(coordinates: String) = text(R.string.outing_message_parking_upstream, coordinates)
    override fun shuttle(km: Int) = text(R.string.outing_message_shuttle, km)
    override fun canyon(approach: String, descent: String, back: String) = text(R.string.outing_message_canyon, approach, descent, back)
    override fun canyonFigures(cascadeMax: Int?, rope: Int?): String? = when {
        cascadeMax != null && rope != null -> text(R.string.outing_message_figures, cascadeMax, rope)
        cascadeMax != null -> text(R.string.outing_message_figure_cascade, cascadeMax)
        rope != null -> text(R.string.outing_message_figure_rope, rope)
        else -> null
    }
    override fun weather(forecast: String) = text(R.string.outing_message_weather, forecast)
    override fun storm() = text(R.string.outing_message_storm)
    override fun flowEstimate(level: String) = text(R.string.outing_message_flow_estimate, level)
    override fun lastReport(level: String, day: String) = text(R.string.outing_message_last_report, level, day)
    override fun confirmTheDayBefore() = text(R.string.outing_message_confirm)
    override fun sharedGear(items: String) = text(R.string.outing_message_gear_shared, items)
    override fun guestsBring(items: String) = text(R.string.outing_message_gear_bring, items)
    override fun guestsProvided(items: String) = text(R.string.outing_message_gear_provided, items)
    override fun sheet(url: String) = text(R.string.outing_message_sheet, url)
    override fun back(name: String, time: String) = text(R.string.outing_message_back, name, time)
    override fun delay(name: String, alertTime: String, number: String) = text(R.string.outing_message_delay, name, alertTime, number)
}

/** What the messages say about the outing on screen, written with [resources] and [locale] (the language chosen). */
internal fun outingMessageData(state: OutingPlanUiState, resources: Resources, locale: Locale): OutingMessageData? {
    val canyon = state.canyon?.canyon ?: return null
    val schedule = state.schedule ?: return null
    val times = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    fun LocalDateTime.time() = toLocalTime().format(times)
    val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(state.choices.date)
        .replaceFirstChar { it.titlecase(locale) }
    val back = schedule.backAtCar
    val gear = state.gear
    fun gearOf(group: GearGroup) = gear.filter { it.group == group }.map { gearLabel(resources, it) }
    val place = listOfNotNull(canyon.commune.takeIf { it.isNotBlank() }, canyon.departement, canyon.pays.takeIf { it.isNotBlank() })
        .joinToString(", ")
    return OutingMessageData(
        canyonName = canyon.nom,
        cotation = canyon.cotation,
        place = place,
        day = day,
        startingPlace = state.place?.name,
        leavePlace = schedule.leavePlace?.time(),
        backAtPlace = schedule.backAtPlace?.estimate?.time(),
        meetingTime = schedule.arriveParking.time(),
        meetAtPlace = state.choices.meetAtPlace,
        meetingParking = state.meetingParking,
        walkStart = schedule.walkStart.time(),
        descentStart = schedule.descentStart?.estimate?.time(),
        back = back?.estimate?.time(),
        backEarliest = back?.earliest?.time(),
        backLatest = back?.latest?.time(),
        approachDuration = schedule.spans[OutingPhase.APPROACH]
            ?.takeIf { it.likely > 0 }?.let { formatMinutes(it.likely) },
        inCanyonDuration = schedule.descentStart?.let { start ->
            schedule.descentEnd?.let { end -> formatDuration(Duration.between(start.estimate, end.estimate)) }
        },
        groupSize = state.choices.groupSize,
        alertTime = state.alertAt?.let { timeOfOuting(it, state.choices.date, locale) }.orEmpty(),
        emergencyNumber = state.emergencyNumbers.first().number,
        downstreamParking = state.downstreamParking,
        upstreamParking = state.upstreamParking.takeIf { state.withShuttle || state.downstreamParking == null },
        shuttleKm = state.sheetShuttle?.takeIf { state.withShuttle }?.distanceKm?.roundToInt(),
        sheetApproach = state.approach.takeUnless { it == CanyonDuration.Unknown }?.let { durationLabel(resources, it) },
        sheetDescent = state.descent.takeUnless { it == CanyonDuration.Unknown }?.let { durationLabel(resources, it) },
        sheetBack = state.back.takeUnless { it == CanyonDuration.Unknown }?.let { durationLabel(resources, it) },
        cascadeMax = canyon.cascadeMax,
        rope = canyon.cordeMin,
        forecast = state.dayWeather?.let { forecastText(resources, it, locale) },
        storm = state.dayWeather?.condition == WeatherCondition.THUNDERSTORM,
        flowEstimate = state.estimate?.let { estimate ->
            estimate.likelyRange()?.let { range ->
                resources.getString(
                    R.string.prediction_range,
                    resources.getString(debitRangeWordRes(range.low)),
                    resources.getString(debitRangeWordRes(range.high)),
                )
            } ?: estimate.ordinalLevel?.let { resources.getString(debitRangeWordRes(it)) }
        },
        lastReport = state.lastReport?.let { report ->
            resources.getString(debitLevelLabelRes(report.niveau)) to relativeDayLabel(resources, report.date, state.today, locale)
        },
        sharedGear = gearOf(GearGroup.SHARED),
        guestsBring = gearOf(GearGroup.GUESTS_BRING),
        guestsProvided = gearOf(GearGroup.GUESTS_PROVIDED),
        note = state.choices.note,
        sheetUrl = canyon.url,
    )
}

/** "Sunny, rain 0.0 mm, 12° to 24°". */
internal fun forecastText(resources: Resources, day: DailyWeather, locale: Locale): String {
    val rain = day.precipitationSumMm?.let { String.format(locale, "%.1f mm", it) } ?: "--"
    val minimum = day.temperatureMinC?.let { "${it.roundToInt()}°" } ?: "--"
    val maximum = day.temperatureMaxC?.let { "${it.roundToInt()}°" } ?: "--"
    return resources.getString(
        R.string.outing_conditions_forecast,
        resources.getString(weatherConditionRes(day.condition)),
        rain,
        minimum,
        maximum,
    )
        .replaceFirstChar { it.titlecase(locale) }
}

internal fun weatherConditionRes(condition: WeatherCondition): Int = when (condition) {
    WeatherCondition.CLEAR -> R.string.weather_condition_clear
    WeatherCondition.PARTLY_CLOUDY -> R.string.weather_condition_partly_cloudy
    WeatherCondition.CLOUDY -> R.string.weather_condition_cloudy
    WeatherCondition.FOG -> R.string.weather_condition_fog
    WeatherCondition.DRIZZLE -> R.string.weather_condition_drizzle
    WeatherCondition.RAIN -> R.string.weather_condition_rain
    WeatherCondition.SNOW -> R.string.weather_condition_snow
    WeatherCondition.THUNDERSTORM -> R.string.weather_condition_thunderstorm
    WeatherCondition.UNKNOWN -> R.string.weather_condition_unknown
}

/** A time of the day of the outing, with its date when it falls on another day. */
internal fun timeOfOuting(dateTime: LocalDateTime, day: LocalDate, locale: Locale): String = if (dateTime.toLocalDate() == day) {
    dateTime.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
} else {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(dateTime)
}
