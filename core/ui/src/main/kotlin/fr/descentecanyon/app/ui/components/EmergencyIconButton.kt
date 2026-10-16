package fr.descentecanyon.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.ui.test.TestTags

/** One tap to the emergency screen from the screens used during an outing. */
@Composable
fun EmergencyIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.testTag(TestTags.EMERGENCY_BUTTON)) {
        Icon(
            imageVector = Icons.Default.Sos,
            contentDescription = stringResource(R.string.emergency_open),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}
