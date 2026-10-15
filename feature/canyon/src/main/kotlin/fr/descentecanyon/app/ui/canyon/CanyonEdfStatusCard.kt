package fr.descentecanyon.app.ui.canyon

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.EdfPracticabilityCondition
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitCrue
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun CanyonEdfStatusCard(
    status: CanyonEdfPracticability?,
    isLoading: Boolean,
    error: String?,
    sourceUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.edf_status_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val subtitle = status?.lastSample?.value?.let { value ->
                        "${status.title} - ${stringResource(R.string.edf_status_current_level, formatLevel(value))}"
                    } ?: status?.title ?: stringResource(R.string.edf_status_source_name)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                status?.let {
                    ConditionBadge(condition = it.state)
                }
            }

            when {
                isLoading && status == null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.edf_status_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                error != null && status == null -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                status != null -> {
                    status.lastSample?.let { sample ->
                        Text(
                            text = stringResource(
                                R.string.edf_status_updated_at,
                                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                                    .withLocale(LocalLocale.current.platformLocale)
                                    .format(sample.recordedAt.atZone(ZoneId.systemDefault())),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (status.amenagementTitle.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.edf_status_amenagement, status.amenagementTitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = statusSummary(status),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    status.lastSample?.value?.let { value ->
                        Text(
                            text = stringResource(R.string.edf_status_current_level, formatLevel(value)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val appropriateMax = status.thresholds.firstOrNull { it.condition == EdfPracticabilityCondition.APPROPRIATE }?.max
                    val restrictedMin = status.thresholds.firstOrNull { it.condition == EdfPracticabilityCondition.NOT_APPROPRIATE }?.min
                    appropriateMax?.let {
                        Text(
                            text = stringResource(R.string.edf_status_green_threshold, formatLevel(it)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    restrictedMin?.let {
                        Text(
                            text = stringResource(R.string.edf_status_red_threshold, formatLevel(it)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (status.hasPublishedEventInProgress) {
                        Text(
                            text = stringResource(R.string.edf_status_active_event),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    error?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                else -> {
                    Text(
                        text = stringResource(R.string.edf_status_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TextButton(
                onClick = {
                    context.openUrl(sourceUrl)
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.edf_status_open_source))
            }
        }
    }
}

@Composable
private fun ConditionBadge(condition: EdfPracticabilityCondition, modifier: Modifier = Modifier) {
    val (label, color) = when (condition) {
        EdfPracticabilityCondition.APPROPRIATE -> stringResource(R.string.edf_status_state_appropriate) to DebitCorrect

        EdfPracticabilityCondition.NOT_APPROPRIATE -> stringResource(R.string.edf_status_state_not_appropriate) to DebitCrue

        EdfPracticabilityCondition.NOT_INTERPRETED -> stringResource(R.string.edf_status_state_not_interpreted) to
            MaterialTheme.colorScheme.outline

        EdfPracticabilityCondition.UNKNOWN -> stringResource(R.string.edf_status_state_unknown) to MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.48f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun statusSummary(status: CanyonEdfPracticability): String = when (status.state) {
    EdfPracticabilityCondition.APPROPRIATE -> stringResource(R.string.edf_status_risk_low)
    EdfPracticabilityCondition.NOT_APPROPRIATE -> stringResource(R.string.edf_status_risk_high)
    EdfPracticabilityCondition.NOT_INTERPRETED -> stringResource(R.string.edf_status_not_interpreted)
    EdfPracticabilityCondition.UNKNOWN -> stringResource(R.string.edf_status_unavailable)
}

private fun formatLevel(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
