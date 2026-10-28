package fr.descentecanyon.app.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** "Mardi 14 juillet", or "mardi 14 juillet" inside a sentence when not [capitalized]. */
@Composable
fun longDayLabel(date: LocalDate, capitalized: Boolean = true): String {
    val locale = LocalLocale.current.platformLocale
    val label = DateTimeFormatter.ofPattern("EEEE d MMMM", locale).format(date)
    return if (capitalized) label.replaceFirstChar { it.titlecase(locale) } else label
}

/** "lun. 26 oct.". */
@Composable
fun shortDayLabel(date: LocalDate): String {
    val locale = LocalLocale.current.platformLocale
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale).format(date)
}
