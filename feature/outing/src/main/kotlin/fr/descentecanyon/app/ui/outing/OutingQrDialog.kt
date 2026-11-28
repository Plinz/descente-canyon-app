package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.ui.test.TestTags
import io.nayuki.qrcodegen.QrCode

/**
 * The outing as a QR code, shown at the meeting point: a teammate's phone camera opens it in the app
 * without network (the outing is in the link). Black on white whatever the theme: what scanners read best.
 */
@Composable
internal fun OutingQrDialog(link: String, title: String, onDismiss: () -> Unit) {
    val qr = remember(link) { runCatching { QrCode.encodeText(link, QrCode.Ecc.LOW) }.getOrNull() }
    val description = stringResource(R.string.outing_link_qr_description, title)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (qr == null) {
                Text(stringResource(R.string.outing_link_qr_too_long))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    QrBox(qr, description)
                    Text(stringResource(R.string.outing_link_qr_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun QrBox(qr: QrCode, description: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .semantics { contentDescription = description }
            .testTag(TestTags.OUTING_LINK_QR_CODE),
    ) {
        QrCanvas(qr)
    }
}

@Composable
private fun QrCanvas(qr: QrCode) {
    Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        // A quiet zone of two modules around the code, as scanners need.
        val modules = qr.size + 4
        val cell = size.minDimension / modules
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.getModule(x, y)) {
                    drawRect(Color.Black, topLeft = Offset((x + 2) * cell, (y + 2) * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
