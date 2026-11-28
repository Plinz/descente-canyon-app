package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags

/**
 * Sharing the outing by its link: on its day with its meeting (those who have the app add it with
 * the same times, the others read it in a browser), or as a route without day nor times.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShareLinkSheet(onDismiss: () -> Unit, onSend: (asRoute: Boolean) -> Unit, onShowQr: (asRoute: Boolean) -> Unit) {
    val colors = LocalDcColors.current
    var asRoute by rememberSaveable { mutableStateOf(false) }
    OutingSheet(
        title = stringResource(R.string.outing_link_sheet_title),
        onDismiss = onDismiss,
        modifier = Modifier.testTag(TestTags.OUTING_LINK_SHEET),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !asRoute, onClick = { asRoute = false }, label = { Text(stringResource(R.string.outing_link_dated)) })
            FilterChip(
                selected = asRoute,
                onClick = { asRoute = true },
                label = { Text(stringResource(R.string.outing_link_route)) },
                modifier = Modifier.testTag(TestTags.OUTING_LINK_ROUTE),
            )
        }
        Text(
            text = stringResource(if (asRoute) R.string.outing_link_route_hint else R.string.outing_link_dated_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Button(onClick = { onSend(asRoute) }, modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_LINK_SEND)) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            Text(stringResource(R.string.outing_link_send), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = { onShowQr(asRoute) }, modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_LINK_QR)) {
            Icon(Icons.Default.QrCode2, contentDescription = null)
            Text(stringResource(R.string.outing_link_qr), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** While the link is made (a second, more without network before it falls back on a long link). */
@Composable
internal fun PreparingLinkDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large, color = LocalDcColors.current.backgroundElevated) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.outing_link_preparing), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** A notice about the outing's link, with its action: a newer version received, or an old plan sent. */
@Composable
internal fun LinkNoticeCard(
    title: String,
    text: String?,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = LocalDcColors.current
    Surface(color = colors.surfaceRaised, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Link, contentDescription = null, tint = colors.primaryAction)
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                onDismiss?.let { TextButton(onClick = it) { Text(stringResource(R.string.outing_link_update_ignore)) } }
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}
