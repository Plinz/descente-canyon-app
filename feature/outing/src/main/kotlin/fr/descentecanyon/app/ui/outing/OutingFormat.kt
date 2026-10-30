package fr.descentecanyon.app.ui.outing

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDuration
import fr.descentecanyon.app.domain.model.GearHint
import fr.descentecanyon.app.domain.model.GearItem
import fr.descentecanyon.app.domain.model.GearKey
import fr.descentecanyon.app.domain.model.TimeSpan
import fr.descentecanyon.app.domain.model.WeatherCondition
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** "45 min", "2 h", "1 h 30". */
internal fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = (duration.toMinutes() % 60).toInt()
    return when {
        hours == 0L -> "$minutes min"
        minutes == 0 -> "$hours h"
        else -> String.format(Locale.ROOT, "%d h %02d", hours, minutes)
    }
}

internal fun formatMinutes(minutes: Long): String = formatDuration(Duration.ofMinutes(minutes))

/** "0,6", "12": a tenth below ten kilometres, whole kilometres above. */
@Composable
internal fun formatKm(km: Double): String {
    val locale = LocalLocale.current.platformLocale
    return if (km < 10) String.format(locale, "%.1f", km) else km.roundToInt().toString()
}

/** "×1,15" with the decimal separator of [locale]. */
internal fun formatFactor(factor: Double, locale: Locale): String = "×" + String.format(locale, "%.2f", factor)

@Composable
internal fun rememberTimeFormatter(): DateTimeFormatter {
    val locale = LocalLocale.current.platformLocale
    return remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
}

/** A duration of the sheet as read: "30 min", "1 h – 1 h 30", "none", "unknown". */
@Composable
internal fun durationLabel(duration: CanyonDuration): String = durationLabel(LocalResources.current, duration)

internal fun durationLabel(resources: Resources, duration: CanyonDuration): String = when (duration) {
    CanyonDuration.None -> resources.getString(R.string.duration_none)

    CanyonDuration.Unknown -> resources.getString(R.string.duration_unknown)

    is CanyonDuration.Known -> {
        val range = duration.range
        if (range.minMinutes == range.maxMinutes) {
            formatMinutes(range.minMinutes.toLong())
        } else {
            resources.getString(
                R.string.outing_time_range,
                formatMinutes(range.minMinutes.toLong()),
                formatMinutes(range.maxMinutes.toLong()),
            )
        }
    }
}

/** The likely duration of a step, with its range when it has one: "4 h 29 (4 h 02 – 5 h 36)". */
@Composable
internal fun spanLabel(span: TimeSpan): String = if (span.shortest == span.longest) {
    formatMinutes(span.likely)
} else {
    stringResource(
        R.string.outing_span_range,
        formatMinutes(span.likely),
        formatMinutes(span.shortest),
        formatMinutes(span.longest),
    )
}

/** The name of a piece of gear in the language of [resources]. */
internal fun gearLabel(resources: Resources, item: GearItem): String = when (item) {
    is GearItem.Custom -> item.label

    is GearItem.Suggested -> resources.getString(gearKeyLabel(item.key))

    is GearItem.Hint -> when (item.hint) {
        GearHint.ROPE -> resources.getString(R.string.gear_hint_rope, item.value ?: 0)
        GearHint.SHUTTLE_CARS -> resources.getString(R.string.gear_hint_shuttle_cars, item.value ?: 0)
        GearHint.FOOD_AND_WATER -> resources.getString(R.string.gear_hint_food_water)
    }
}

internal fun gearKeyLabel(key: GearKey): Int = when (key) {
    GearKey.HELMET -> R.string.gear_helmet
    GearKey.WETSUIT -> R.string.gear_wetsuit
    GearKey.NEOPRENE_SOCKS -> R.string.gear_neoprene_socks
    GearKey.SHOES -> R.string.gear_shoes
    GearKey.HARNESS -> R.string.gear_harness
    GearKey.LANYARDS -> R.string.gear_lanyards
    GearKey.DESCENDER -> R.string.gear_descender
    GearKey.KNIFE -> R.string.gear_knife
    GearKey.WHISTLE -> R.string.gear_whistle
    GearKey.CANYON_BAG -> R.string.gear_canyon_bag
    GearKey.DRY_BAG -> R.string.gear_dry_bag
    GearKey.SURVIVAL_BLANKET -> R.string.gear_survival_blanket
    GearKey.WATER_AND_SNACK -> R.string.gear_water_and_snack
    GearKey.HEADLAMP -> R.string.gear_headlamp
    GearKey.SPARE_ROPE -> R.string.gear_spare_rope
    GearKey.ROPE_BAG -> R.string.gear_rope_bag
    GearKey.RELEASABLE_SYSTEM -> R.string.gear_releasable_system
    GearKey.ASCENDING_KIT -> R.string.gear_ascending_kit
    GearKey.PULLEY -> R.string.gear_pulley
    GearKey.ANCHOR_KIT -> R.string.gear_anchor_kit
    GearKey.SPARE_DESCENDER -> R.string.gear_spare_descender
    GearKey.DIVING_MASK -> R.string.gear_diving_mask
    GearKey.FIRST_AID -> R.string.gear_first_aid
    GearKey.PHONE -> R.string.gear_phone
    GearKey.WARMING_KIT -> R.string.gear_warming_kit
    GearKey.SWIMSUIT -> R.string.gear_swimsuit
    GearKey.WATER_SHOES -> R.string.gear_water_shoes
    GearKey.WATER -> R.string.gear_water
    GearKey.PICNIC -> R.string.gear_picnic
    GearKey.DRY_CLOTHES -> R.string.gear_dry_clothes
    GearKey.TOWEL -> R.string.gear_towel
    GearKey.GUEST_WETSUIT -> R.string.gear_guest_wetsuit
    GearKey.GUEST_SOCKS -> R.string.gear_guest_socks
    GearKey.GUEST_HELMET -> R.string.gear_guest_helmet
    GearKey.GUEST_HARNESS -> R.string.gear_guest_harness
}

/** The icon of a weather condition, as on the canyon page. */
internal fun weatherIcon(condition: WeatherCondition): ImageVector = when (condition) {
    WeatherCondition.CLEAR -> Icons.Default.WbSunny
    WeatherCondition.PARTLY_CLOUDY -> Icons.Default.WbCloudy
    WeatherCondition.CLOUDY, WeatherCondition.FOG -> Icons.Default.Cloud
    WeatherCondition.DRIZZLE, WeatherCondition.RAIN -> Icons.Default.WaterDrop
    WeatherCondition.SNOW -> Icons.Default.AcUnit
    WeatherCondition.THUNDERSTORM -> Icons.Default.Thunderstorm
    WeatherCondition.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
}
