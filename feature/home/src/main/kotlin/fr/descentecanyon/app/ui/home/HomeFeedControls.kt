package fr.descentecanyon.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.ui.design.excludeFromBackGesture

@Composable
internal fun HomeFeedPicker(selectedFeed: HomeFeedType, onFeedSelected: (HomeFeedType) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth(),
    ) {
        val options = listOf(HomeFeedType.DEBITS, HomeFeedType.FORUM)
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selectedFeed == option,
                onClick = { onFeedSelected(option) },
                modifier = Modifier.weight(1f),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        if (option == HomeFeedType.DEBITS) {
                            stringResource(R.string.home_feed_debits)
                        } else {
                            stringResource(R.string.home_feed_forum)
                        },
                    )
                },
            )
        }
    }
}

@Composable
internal fun HomeFeedHeader(selectedFeed: HomeFeedType, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selectedFeed == HomeFeedType.DEBITS) {
                    stringResource(R.string.last_debits)
                } else {
                    stringResource(R.string.home_forum_title)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = onRefresh, modifier = Modifier.excludeFromBackGesture()) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                )
            }
        }
        Text(
            text = if (selectedFeed == HomeFeedType.DEBITS) {
                stringResource(R.string.home_debits_subtitle)
            } else {
                stringResource(R.string.home_forum_subtitle)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
