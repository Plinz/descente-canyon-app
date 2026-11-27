package fr.descentecanyon.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.FirstStep
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags

/** "Premiers pas": four things to try, each opening its screen and checked once done, from wherever. */
@Composable
internal fun FirstStepsCard(stepsDone: Set<FirstStep>, onStep: (FirstStep) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    DcCard(modifier = modifier.fillMaxWidth().testTag(TestTags.HOME_FIRST_STEPS), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.first_steps_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.first_steps_progress, stepsDone.size, FirstStep.entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.first_steps_close), tint = colors.textMuted)
            }
        }
        FirstStep.entries.forEach { step ->
            val done = step in stepsDone
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(if (done) Modifier else Modifier.clickable { onStep(step) })
                    .testTag(TestTags.firstStep(step.name)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (done) colors.primaryAction else colors.textMuted,
                )
                Text(
                    stringResource(step.labelRes()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done) colors.textSecondary else colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (!done) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textMuted)
            }
        }
    }
}

private fun FirstStep.labelRes(): Int = when (this) {
    FirstStep.OPEN_CANYON -> R.string.first_step_nearby
    FirstStep.ADD_FAVORITE -> R.string.first_step_favorite
    FirstStep.PLAN_OUTING -> R.string.first_step_outing
    FirstStep.SEE_EMERGENCY -> R.string.first_step_emergency
}

/** What changed in this release, for those who update: each novelty opens where it is. */
@Composable
internal fun WhatsNewCard(
    onOpenExplorer: () -> Unit,
    onOpenOutings: () -> Unit,
    onOpenMe: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDcColors.current
    DcCard(modifier = modifier.fillMaxWidth().testTag(TestTags.HOME_WHATS_NEW), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.whats_new_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.whats_new_close), tint = colors.textMuted)
            }
        }
        WhatsNewRow(stringResource(R.string.whats_new_explore), onOpenExplorer)
        WhatsNewRow(stringResource(R.string.whats_new_outings), onOpenOutings)
        WhatsNewRow(stringResource(R.string.whats_new_me), onOpenMe)
        Text(
            stringResource(R.string.whats_new_canyon),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun WhatsNewRow(text: String, onTry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onTry) {
            Text(stringResource(R.string.whats_new_try))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
