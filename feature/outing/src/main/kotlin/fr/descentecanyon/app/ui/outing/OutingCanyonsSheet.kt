package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Water
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonChain
import fr.descentecanyon.app.domain.model.CanyonLink
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.util.Locale

/**
 * The canyons of the outing in their order, and how the group gets from one to the next: an
 * "intégrale" (upper then lower part) or two canyons of a valley share one plan and one alert time.
 * "Add a canyon" offers the canyons around the last one, or finds one by its name.
 */
@Composable
internal fun CanyonsSheet(
    state: OutingPlanUiState,
    picker: CanyonPickerState,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onClosePicker: () -> Unit,
    onAdd: (CanyonCandidate) -> Unit,
    onRemove: (Int) -> Unit,
    onLinkSelected: (Int, CanyonLink) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val names = canyonNames(state)
    val dismiss = {
        onClosePicker()
        onDismiss()
    }
    if (picking) {
        OutingSheet(title = stringResource(R.string.outing_canyons_add), onDismiss = dismiss) {
            CanyonPicker(
                origin = names.last(),
                picker = picker,
                onSearch = onSearch,
                onBack = {
                    picking = false
                    onClosePicker()
                },
                onPick = { candidate ->
                    picking = false
                    onAdd(candidate)
                },
            )
        }
        return
    }
    OutingSheet(title = stringResource(R.string.outing_canyons_label), onDismiss = dismiss) {
        val colors = LocalDcColors.current
        Text(stringResource(R.string.outing_canyons_intro), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        CanyonLine(position = 1, name = names.first(), cotation = state.canyon?.canyon?.cotation)
        state.nextCanyons.forEachIndexed { index, next ->
            LinkChoice(
                next = next,
                previousName = names[index],
                onLinkSelected = { onLinkSelected(index, it) },
                modifier = Modifier.testTag(TestTags.outingCanyonLink(index)),
            )
            CanyonLine(
                position = index + 2,
                name = next.canyon.name,
                cotation = next.detail?.canyon?.cotation,
                onRemove = { onRemove(index) },
                modifier = Modifier.testTag(TestTags.outingNextCanyon(index)),
            )
        }
        if (state.canAddCanyon) {
            OutlinedButton(
                onClick = {
                    picking = true
                    onSearch("")
                },
                modifier = Modifier.testTag(TestTags.OUTING_ADD_CANYON),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.outing_canyons_add), modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Text(
                pluralStringResource(R.plurals.outing_canyons_max, OutingChoices.MAX_CANYONS, OutingChoices.MAX_CANYONS),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun CanyonLine(position: Int, name: String, cotation: String?, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$position", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            cotation?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textSecondary)
            }
        }
        onRemove?.let {
            IconButton(onClick = it) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.outing_canyons_remove, name))
            }
        }
    }
}

/**
 * Straight on from the canyon before (parts of one stream), on foot from one to the other, or back to
 * the car and a drive; what is counted between the two is said under the choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkChoice(next: NextCanyonState, previousName: String, onLinkSelected: (CanyonLink) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    val link = next.canyon.link
    Column(modifier = modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = link == CanyonLink.CONTINUOUS,
                onClick = { onLinkSelected(CanyonLink.CONTINUOUS) },
                label = { Text(stringResource(R.string.outing_link_continuous)) },
                leadingIcon = { Icon(Icons.Default.Water, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = link == CanyonLink.ON_FOOT,
                onClick = { onLinkSelected(CanyonLink.ON_FOOT) },
                label = { Text(stringResource(R.string.outing_link_on_foot)) },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            FilterChip(
                selected = link == CanyonLink.BY_CAR,
                onClick = { onLinkSelected(CanyonLink.BY_CAR) },
                label = {
                    val drive = next.drive?.minutes?.let { formatMinutes(it.toLong()) }
                    Text(
                        drive?.let {
                            stringResource(R.string.outing_link_by_car_drive, it)
                        } ?: stringResource(R.string.outing_link_by_car),
                    )
                },
                leadingIcon = { Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        val known = next.chain?.minutesFor(link)?.takeIf { it > 0 }
        Text(
            when {
                known != null -> stringResource(
                    R.string.outing_link_known_hint,
                    previousName,
                    next.canyon.name,
                    formatMinutes(known.toLong()),
                )

                link == CanyonLink.CONTINUOUS -> stringResource(R.string.outing_link_continuous_hint, previousName, next.canyon.name)

                link == CanyonLink.ON_FOOT -> stringResource(R.string.outing_link_on_foot_hint, previousName, next.canyon.name)

                else -> stringResource(R.string.outing_link_by_car_hint, previousName, next.canyon.name)
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        if (link == CanyonLink.CONTINUOUS && next.chain?.way == CanyonChain.Way.NOT_IN_STREAM) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = colors.warning, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.outing_link_not_in_stream), style = MaterialTheme.typography.bodySmall, color = colors.warning)
            }
        }
    }
}

/** The canyons around the last one of the outing, nearest first, or those matching the name typed. */
@Composable
private fun ColumnScope.CanyonPicker(
    origin: String,
    picker: CanyonPickerState,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    onPick: (CanyonCandidate) -> Unit,
) {
    val colors = LocalDcColors.current
    val locale = LocalLocale.current.platformLocale
    // The text stays here: fed back by the search, the field would lose the letters typed meanwhile.
    var query by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onSearch(it)
            },
            placeholder = { Text(stringResource(R.string.outing_canyons_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag(TestTags.OUTING_CANYON_SEARCH),
        )
    }
    if (query.isBlank()) {
        Text(stringResource(R.string.outing_canyons_nearby, origin), style = MaterialTheme.typography.titleSmall)
    }
    when {
        picker.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))

        picker.candidates.isEmpty() -> Text(
            stringResource(R.string.outing_canyons_none),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )

        else -> Column(modifier = Modifier.testTag(TestTags.OUTING_CANYON_CANDIDATES)) {
            picker.candidates.forEachIndexed { index, candidate ->
                if (index > 0) HorizontalDivider(color = colors.borderSubtle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onPick(candidate) }
                        .padding(vertical = 8.dp)
                        .testTag(TestTags.outingCanyonCandidate(candidate.id)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(candidate.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        val detail = listOfNotNull(
                            candidate.cotation.takeIf { it.isNotBlank() },
                            candidate.distanceKm?.let { distanceText(it, locale) },
                            candidate.chain?.let { chainLabel(it) },
                        ).joinToString(" · ")
                        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** "800 m", "2,4 km", "12 km". */
private fun distanceText(km: Double, locale: Locale): String = when {
    km < 1 -> "${((km * 1000).toInt() / 50 * 50).coerceAtLeast(50)} m"
    km < 10 -> String.format(locale, "%.1f km", km)
    else -> String.format(locale, "%d km", km.toInt())
}

/** "À la suite", or "À pied · 45 min": how the topos say the group goes on to a canyon. */
@Composable
private fun chainLabel(chain: CanyonChain): String? {
    val minutes = chain.minutes?.takeIf { it > 0 }?.let { formatMinutes(it.toLong()) }
    return when (chain.link) {
        CanyonLink.CONTINUOUS -> stringResource(R.string.outing_link_continuous)

        CanyonLink.ON_FOOT -> minutes?.let { stringResource(R.string.outing_link_on_foot_minutes, it) }
            ?: stringResource(R.string.outing_link_on_foot)

        else -> null
    }
}
