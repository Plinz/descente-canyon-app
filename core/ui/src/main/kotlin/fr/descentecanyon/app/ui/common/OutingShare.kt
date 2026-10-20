package fr.descentecanyon.app.ui.common

import android.content.Context
import android.content.Intent
import fr.descentecanyon.app.core.ui.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Back from Riolan at 17:10, all is well", through any messaging app: the relative who received
 * the plan of the outing waits for this news before the alert time.
 */
fun Context.shareOutingBackMessage(canyonName: String) {
    val time = LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(resources.configuration.locales[0]))
    val message = getString(R.string.outing_message_back, canyonName, time)
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
    startActivitySafely(Intent.createChooser(intent, getString(R.string.outing_back_share_title)))
}
