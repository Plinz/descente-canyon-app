package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.ShareCodec
import fr.descentecanyon.app.domain.model.SharedOuting
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the screen of a received link opens: the plan of the outing taken from it, an outing, a stay, a canyon. */
data class SharedOutingRequest(
    val canyonId: Int,
    /** The outing ([ShareCodec.pack]ed). */
    val payload: String,
    val code: String?,
    val version: Int,
    /** Added at once (an outing on its day), else prepared (a route, a day gone by). */
    val plan: Boolean,
    /** The day of a stay it is added to (0: none). */
    val stayId: Long = 0,
)

/**
 * A link someone shared: what it holds, and the one action that takes it into the app (add the
 * outing, the stay, the canyons to the favorites). The times of an outing are those of the phone
 * that shared it.
 */
@Composable
fun SharedLinkScreen(
    onBackClick: () -> Unit,
    onOpenCanyon: (Int) -> Unit,
    onAddOuting: (SharedOutingRequest) -> Unit,
    onOpenOuting: (canyonId: Int, outingId: Long) -> Unit,
    onOpenStay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SharedLinkViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    val title = when (state) {
        is SharedLinkUiState.Stay -> stringResource(R.string.shared_stay_title)
        is SharedLinkUiState.CanyonList -> stringResource(R.string.shared_list_title)
        else -> stringResource(R.string.shared_outing_title)
    }
    Scaffold(
        modifier = modifier.testTag(TestTags.SHARED_LINK),
        topBar = {
            CompactAppBar(
                title = title,
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            when (val current = state) {
                SharedLinkUiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(32.dp))

                is SharedLinkUiState.Failed -> FailedLink(
                    current.failure,
                    onRetry = viewModel::retry,
                    modifier = Modifier.width(contentWidth),
                )

                is SharedLinkUiState.Outing -> LazyColumn(
                    modifier = Modifier.fillMaxHeight().width(contentWidth),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { OutingFacts(current.outing, current.canyons.joinToString(" + ") { it.name }) }
                    item { OutingActions(current, onAddOuting, onOpenOuting) }
                    items(current.canyons.size) { index ->
                        val canyon = current.canyons[index]
                        CanyonRow(canyon, onOpenCanyon)
                    }
                }

                is SharedLinkUiState.Stay -> LazyColumn(
                    modifier = Modifier.fillMaxHeight().width(contentWidth),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { StayHeader(current, onAddStay = viewModel::addStay, onOpenStay = onOpenStay) }
                    current.stay.outings.sortedBy { it.date }.forEach { outing ->
                        item {
                            StayDayCard(outing, current, onAddOuting = onAddOuting)
                        }
                    }
                }

                is SharedLinkUiState.CanyonList -> LazyColumn(
                    modifier = Modifier.fillMaxHeight().width(contentWidth),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { ListHeader(current, onAdd = viewModel::addListToFavorites) }
                    items(current.canyons.size) { index -> CanyonRow(current.canyons[index], onOpenCanyon) }
                }
            }
        }
    }
}

@Composable
private fun FailedLink(failure: SharedLinkFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(
                when (failure) {
                    SharedLinkFailure.OFFLINE -> R.string.shared_link_offline
                    SharedLinkFailure.NOT_FOUND -> R.string.shared_link_not_found
                    SharedLinkFailure.UNREADABLE -> R.string.shared_link_unreadable
                    SharedLinkFailure.UNAVAILABLE -> R.string.shared_link_unavailable
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(TestTags.SHARED_LINK_FAILURE),
        )
        if (failure == SharedLinkFailure.OFFLINE || failure == SharedLinkFailure.UNAVAILABLE) {
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun OutingFacts(outing: SharedOuting, title: String) {
    val colors = LocalDcColors.current
    val locale = LocaleList.current.localeList.firstOrNull()?.platformLocale ?: Locale.getDefault()
    Surface(color = colors.surfaceRaised, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(if (outing.isRoute) R.string.shared_route_kicker else R.string.shared_outing_kicker),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(TestTags.SHARED_OUTING_TITLE))
            Text(
                text = outing.date?.let { longDay(it, locale) } ?: stringResource(R.string.shared_route_no_date),
                style = MaterialTheme.typography.titleSmall,
            )
            if (!outing.isRoute) {
                val meet = outing.summary.meet ?: outing.anchorTime
                Fact(
                    stringResource(R.string.shared_outing_meet),
                    if (outing.meetAtPlace && outing.place != null) {
                        stringResource(R.string.shared_outing_meet_place, time(meet, locale), outing.place?.name.orEmpty())
                    } else {
                        stringResource(R.string.shared_outing_meet_parking, time(meet, locale))
                    },
                )
                outing.summary.walk?.let { Fact(stringResource(R.string.shared_outing_walk), time(it, locale)) }
                outing.summary.back?.let { back ->
                    val latest = outing.summary.backLatest?.takeIf { it != back }
                    Fact(
                        stringResource(R.string.shared_outing_back),
                        latest?.let { stringResource(R.string.shared_outing_back_range, time(back, locale), time(it, locale)) }
                            ?: time(back, locale),
                    )
                }
            }
            Fact(stringResource(R.string.shared_outing_group), groupText(outing))
            if (outing.note.isNotBlank()) {
                Surface(color = colors.backgroundBase, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Text(outing.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp))
                }
            }
            if (!outing.isRoute) {
                Text(
                    stringResource(R.string.shared_outing_times_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun groupText(outing: SharedOuting): String {
    val size = pluralStringResource(R.plurals.shared_outing_people, outing.groupSize, outing.groupSize)
    return when (outing.groupLevel) {
        GroupLevel.AUTONOMOUS -> size
        GroupLevel.WITH_BEGINNERS -> "$size · ${stringResource(R.string.outing_level_beginners)}"
        GroupLevel.DISCOVERY -> "$size · ${stringResource(R.string.outing_level_discovery)}"
    }
}

@Composable
private fun Fact(term: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            term,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalDcColors.current.textSecondary,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OutingActions(
    state: SharedLinkUiState.Outing,
    onAddOuting: (SharedOutingRequest) -> Unit,
    onOpenOuting: (canyonId: Int, outingId: Long) -> Unit,
) {
    val colors = LocalDcColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !state.canPlan -> Text(stringResource(R.string.shared_outing_missing_canyon), style = MaterialTheme.typography.bodyMedium)

            state.existing != null -> {
                Button(
                    onClick = { onOpenOuting(state.existing.canyonId, state.existing.id) },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.SHARED_OUTING_OPEN),
                ) { Text(stringResource(R.string.shared_outing_open)) }
                Text(
                    stringResource(R.string.shared_outing_already_added),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }

            state.outing.isRoute || state.isPast -> Button(
                onClick = { onAddOuting(request(state, plan = false)) },
                modifier = Modifier.fillMaxWidth().testTag(TestTags.SHARED_OUTING_PREPARE),
            ) { Text(stringResource(R.string.shared_outing_prepare)) }

            else -> Button(
                onClick = { onAddOuting(request(state, plan = true)) },
                modifier = Modifier.fillMaxWidth().testTag(TestTags.SHARED_OUTING_ADD),
            ) { Text(stringResource(R.string.shared_outing_add)) }
        }
        if (state.isPast && state.existing == null) {
            Text(stringResource(R.string.shared_outing_past), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

private fun request(state: SharedLinkUiState.Outing, plan: Boolean) = SharedOutingRequest(
    canyonId = state.outing.canyonId,
    payload = ShareCodec.pack(state.outing),
    code = state.code,
    version = state.version,
    plan = plan,
)

@Composable
private fun CanyonRow(canyon: SharedCanyon, onOpenCanyon: (Int) -> Unit) {
    val colors = LocalDcColors.current
    Surface(color = colors.surfaceRaised, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .then(if (canyon.item != null) Modifier.clickable { onOpenCanyon(canyon.id) } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(canyon.name.ifBlank { "#${canyon.id}" }, style = MaterialTheme.typography.titleSmall)
                val item = canyon.item
                Text(
                    text = if (item == null) {
                        stringResource(R.string.shared_canyon_not_in_catalog)
                    } else {
                        listOf(item.cotation, item.departement.orEmpty(), item.pays).filter {
                            it.isNotBlank()
                        }.distinct().joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                if (item?.isForbidden == true) {
                    Text(stringResource(R.string.nearby_forbidden), style = MaterialTheme.typography.labelMedium, color = colors.warning)
                }
            }
            if (canyon.item != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textMuted)
        }
    }
}

@Composable
private fun StayHeader(state: SharedLinkUiState.Stay, onAddStay: () -> Unit, onOpenStay: (Long) -> Unit) {
    val colors = LocalDcColors.current
    val locale = LocaleList.current.localeList.firstOrNull()?.platformLocale ?: Locale.getDefault()
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    Surface(color = colors.surfaceRaised, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.stay.place.name, style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.shared_stay_days, formatter.format(state.stay.start), formatter.format(state.stay.end)),
                style = MaterialTheme.typography.titleSmall,
            )
            when {
                state.addedStayId != null -> Button(onClick = { onOpenStay(state.addedStayId) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shared_stay_open))
                }

                state.isPast -> Text(stringResource(R.string.shared_stay_past), style = MaterialTheme.typography.bodyMedium)

                state.overlaps -> Text(stringResource(R.string.shared_stay_overlaps), style = MaterialTheme.typography.bodyMedium)

                else -> Button(onClick = onAddStay, modifier = Modifier.fillMaxWidth().testTag(TestTags.SHARED_STAY_ADD)) {
                    Text(stringResource(R.string.shared_stay_add))
                }
            }
            Text(stringResource(R.string.shared_stay_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun StayDayCard(outing: SharedOuting, state: SharedLinkUiState.Stay, onAddOuting: (SharedOutingRequest) -> Unit) {
    val colors = LocalDcColors.current
    val locale = LocaleList.current.localeList.firstOrNull()?.platformLocale ?: Locale.getDefault()
    val names = outing.canyonIds.map { state.canyons[it]?.name.orEmpty() }.joinToString(" + ")
    val inCatalog = outing.canyonIds.all { state.canyons[it]?.item != null }
    Surface(color = colors.surfaceRaised, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                outing.date?.let {
                    longDay(it, locale)
                }.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
            )
            Text(names, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.shared_outing_meet_parking, time(outing.summary.meet ?: outing.anchorTime, locale)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (inCatalog && !state.isPast) {
                OutlinedButton(
                    onClick = {
                        onAddOuting(
                            SharedOutingRequest(
                                canyonId = outing.canyonId,
                                payload = ShareCodec.pack(outing),
                                code = null,
                                version = 0,
                                plan = true,
                                stayId = state.addedStayId ?: 0,
                            ),
                        )
                    },
                ) { Text(stringResource(R.string.shared_outing_add)) }
            }
        }
    }
}

@Composable
private fun ListHeader(state: SharedLinkUiState.CanyonList, onAdd: () -> Unit) {
    val colors = LocalDcColors.current
    val addable = state.canyons.filter { it.item != null }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Text(state.title.ifBlank { stringResource(R.string.shared_list_title) }, style = MaterialTheme.typography.titleLarge)
        Text(
            pluralStringResource(R.plurals.shared_list_count, state.canyons.size, state.canyons.size),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        if (state.added || addable.all { it.id in state.favoriteIds }) {
            Text(stringResource(R.string.shared_list_in_favorites), style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().testTag(TestTags.SHARED_LIST_ADD)) {
                Text(stringResource(R.string.shared_list_add))
            }
        }
    }
}

private fun longDay(day: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(day).replaceFirstChar { it.titlecase(locale) }

private fun time(value: LocalTime, locale: Locale): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(value)
