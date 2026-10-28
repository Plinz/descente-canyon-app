package fr.descentecanyon.app.ui.components

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDuration
import fr.descentecanyon.app.domain.model.DebitLevelRange
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitCrue
import fr.descentecanyon.app.ui.theme.DebitFilet
import fr.descentecanyon.app.ui.theme.DebitGros
import fr.descentecanyon.app.ui.theme.DebitInconnu
import fr.descentecanyon.app.ui.theme.DebitSec
import fr.descentecanyon.app.ui.theme.DebitTresGros
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

fun debitLevelColor(niveau: NiveauDebit): Color = when (niveau) {
    NiveauDebit.SEC -> DebitSec
    NiveauDebit.FILET -> DebitFilet
    NiveauDebit.CORRECT -> DebitCorrect
    NiveauDebit.GROS -> DebitGros
    NiveauDebit.TRES_GROS -> DebitTresGros
    NiveauDebit.CRUE -> DebitCrue
    NiveauDebit.INCONNU -> DebitInconnu
}

@Composable
fun debitLevelLabel(niveau: NiveauDebit): String = stringResource(debitLevelLabelRes(niveau))

@StringRes
fun debitLevelLabelRes(niveau: NiveauDebit): Int = when (niveau) {
    NiveauDebit.SEC -> R.string.debit_level_sec
    NiveauDebit.FILET -> R.string.debit_level_filet
    NiveauDebit.CORRECT -> R.string.debit_level_correct
    NiveauDebit.GROS -> R.string.debit_level_gros
    NiveauDebit.TRES_GROS -> R.string.debit_level_tres_gros
    NiveauDebit.CRUE -> R.string.debit_level_crue
    NiveauDebit.INCONNU -> R.string.debit_level_inconnu
}

/** "Today", "Yesterday", "3 days ago", then the short date: how fresh an observation is. */
@Composable
fun relativeDayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String =
    relativeDayLabel(LocalResources.current, date, today, LocalLocale.current.platformLocale)

/** [relativeDayLabel] in the language of [resources], for text written in another language than the app's. */
fun relativeDayLabel(resources: Resources, date: LocalDate, today: LocalDate, locale: Locale): String {
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0L -> resources.getString(R.string.relative_today)
        days == 1L -> resources.getString(R.string.relative_yesterday)
        days < RELATIVE_DAYS_LIMIT -> resources.getQuantityString(R.plurals.relative_days_ago, days.toInt(), days.toInt())
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
    }
}

/** "auj.", "hier", "3 j", then the short date: [relativeDayLabel] where room is short, as in a badge. */
@Composable
fun shortRelativeDayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0L -> stringResource(R.string.relative_short_today)
        days == 1L -> stringResource(R.string.relative_short_yesterday)
        days < RELATIVE_DAYS_LIMIT -> stringResource(R.string.relative_short_days, days.toInt())
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(LocalLocale.current.platformLocale))
    }
}

private const val RELATIVE_DAYS_LIMIT = 30L

/** "Correct à gros": two adjacent levels the flow estimate hesitates between. */
@Composable
fun debitRangeLabel(range: DebitLevelRange): String =
    capitalized(stringResource(R.string.prediction_range, debitRangeWord(range.low), debitRangeWord(range.high)))

/** "Très gros": a level as the flow estimate names it, without the word "débit". */
@Composable
fun debitLevelShortLabel(level: NiveauDebit): String = capitalized(debitRangeWord(level))

@Composable
internal fun capitalized(text: String): String {
    val locale = LocalLocale.current.platformLocale
    return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/** Short lower-case name of a level, to build ranges and legends. */
@Composable
fun debitRangeWord(level: NiveauDebit): String = stringResource(debitRangeWordRes(level))

@StringRes
fun debitRangeWordRes(level: NiveauDebit): Int = when (level) {
    NiveauDebit.SEC -> R.string.prediction_range_level_sec
    NiveauDebit.FILET -> R.string.prediction_range_level_filet
    NiveauDebit.CORRECT -> R.string.prediction_range_level_correct
    NiveauDebit.GROS -> R.string.prediction_range_level_gros
    NiveauDebit.TRES_GROS -> R.string.prediction_range_level_tres_gros
    NiveauDebit.CRUE -> R.string.prediction_range_level_crue
    NiveauDebit.INCONNU -> R.string.debit_level_inconnu
}

/**
 * A duration of the sheet as shown to the user: "néant" and "??" translated, a bare number of
 * minutes given its unit ("5" becomes "5 min"), anything else as written.
 */
@Composable
fun canyonDurationText(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // Cut by the site at ten characters: shown as such.
    val shown = if (CanyonDuration.isCut(text)) "$text…" else text
    return when (CanyonDuration.parse(text)) {
        CanyonDuration.None -> stringResource(R.string.duration_none)
        CanyonDuration.Unknown -> if (text.all { it == '?' }) stringResource(R.string.duration_unknown) else shown
        is CanyonDuration.Known -> text.toIntOrNull()?.let { stringResource(R.string.duration_minutes, it) } ?: shown
    }
}
