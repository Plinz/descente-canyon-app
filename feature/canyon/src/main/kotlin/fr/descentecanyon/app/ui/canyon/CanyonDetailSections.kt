package fr.descentecanyon.app.ui.canyon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonDuration
import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.NearbyCanyon
import fr.descentecanyon.app.ui.components.longDayLabel
import fr.descentecanyon.app.ui.design.DcOutdoorActionCard
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun CanyonDetailContent(
    detail: CanyonDetail,
    isRefreshingDetail: Boolean,
    isOnline: Boolean,
    isLoadingPhotos: Boolean,
    photoError: String?,
    isLoadingDebits: Boolean,
    debitError: String?,
    edfStatus: CanyonEdfPracticability?,
    isLoadingEdfStatus: Boolean,
    edfStatusError: String?,
    edfStatusSourceUrl: String?,
    weather: fr.descentecanyon.app.domain.model.CanyonWeather?,
    isLoadingWeather: Boolean,
    weatherError: String?,
    predictions: CanyonDebitPredictions?,
    isLoadingPredictions: Boolean,
    predictionError: String?,
    onOpenPredictionInfo: () -> Unit,
    downloadingPhotoIds: Set<Long>,
    onOpenPhotoGallery: (Long) -> Unit,
    memberAuthors: Set<String>,
    onUserClick: (String) -> Unit,
    onPersistedPhotoMissing: (Long) -> Unit,
    openDebitsTabInitially: Boolean,
    modifier: Modifier = Modifier,
    personalNote: String? = null,
    onSavePersonalNote: (String) -> Unit = {},
    nearbyCanyons: List<NearbyCanyon> = emptyList(),
    onNearbyCanyonClick: (Int) -> Unit = {},
    onPlanOuting: () -> Unit = {},
    /** Opened for a day of a stay: the outing is planned for it. */
    planForDay: LocalDate? = null,
    onStartOutingNow: () -> Unit = {},
    logbook: List<LogbookEntry> = emptyList(),
    knownCompanions: List<String> = emptyList(),
    onSaveLogbookEntry: (LogbookEntry) -> Unit = {},
    onDeleteLogbookEntry: (Long) -> Unit = {},
    onReportFlow: (LogbookEntry) -> Unit = {},
    onOpenLogbook: () -> Unit = {},
    bottomContentPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
) {
    var selectedTab by rememberSaveable(openDebitsTabInitially) {
        mutableIntStateOf(if (openDebitsTabInitially) 2 else 0)
    }
    val scope = rememberCoroutineScope()
    val tabs = listOf(
        stringResource(R.string.tab_topo),
        if (photoError != null && detail.photos.isEmpty()) {
            stringResource(R.string.tab_photos_unavailable)
        } else if (isLoadingPhotos && detail.photos.isEmpty()) {
            stringResource(R.string.tab_photos_loading)
        } else {
            stringResource(R.string.tab_photos_with_count, detail.photos.size)
        },
        if (debitError != null && detail.debits.isEmpty()) {
            stringResource(R.string.tab_debits_unavailable)
        } else if (isLoadingDebits && detail.debits.isEmpty()) {
            stringResource(R.string.tab_debits_loading)
        } else {
            stringResource(R.string.tab_debits_with_count, detail.debits.size)
        },
    )

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag(TestTags.DETAIL_LIST),
        contentPadding = PaddingValues(bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Items only when shown: an empty first item would shift the first visible index at the top.
        if (!isOnline) {
            item(key = "offline_banner") { OfflineBanner(modifier = Modifier.animateItem()) }
        }
        if (isRefreshingDetail && isOnline) {
            item(key = "refreshing_banner") { DetailRefreshingBanner(modifier = Modifier.animateItem()) }
        }
        item {
            SummaryCard(
                detail = detail,
                onPersistedPhotoMissing = onPersistedPhotoMissing,
            )
        }
        item {
            DcOutdoorActionCard(
                title = planForDay?.let { stringResource(R.string.outing_plan_for_day, longDayLabel(it, capitalized = false)) }
                    ?: stringResource(R.string.outing_plan_action),
                hint = stringResource(R.string.outing_plan_action_hint),
                icon = Icons.Default.Hiking,
                onClick = onPlanOuting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(TestTags.DETAIL_PLAN_OUTING),
            )
        }
        item {
            // Already at the parking or about to leave: the follow-up starts at once, the plan can wait.
            TextButton(
                onClick = onStartOutingNow,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(TestTags.DETAIL_START_NOW),
            ) {
                Icon(Icons.AutoMirrored.Filled.DirectionsRun, contentDescription = null)
                Text(stringResource(R.string.outing_start_now), modifier = Modifier.padding(start = 8.dp))
            }
        }
        item {
            val canyon = detail.canyon
            MyCanyonCard(
                canyonId = canyon.id,
                canyonName = canyon.nom,
                memo = personalNote,
                entries = logbook,
                topoDuration = CanyonDuration.total(
                    CanyonDuration.parse(canyon.tempsApproche),
                    CanyonDuration.parse(canyon.tempsDescente),
                    CanyonDuration.parse(canyon.tempsRetour),
                ),
                knownCompanions = knownCompanions,
                onSaveMemo = onSavePersonalNote,
                onSaveEntry = onSaveLogbookEntry,
                onDeleteEntry = onDeleteLogbookEntry,
                onReportFlow = onReportFlow,
                onOpenLogbook = onOpenLogbook,
            )
        }
        if (edfStatusSourceUrl != null) {
            item {
                CanyonEdfStatusCard(
                    status = edfStatus,
                    isLoading = isLoadingEdfStatus,
                    error = edfStatusError,
                    sourceUrl = edfStatusSourceUrl,
                )
            }
        }
        item {
            CanyonWeatherCard(
                weather = weather,
                isLoading = isLoadingWeather,
                error = weatherError,
            )
        }
        item {
            CanyonDebitPredictionCard(
                predictions = predictions,
                isLoading = isLoadingPredictions,
                error = predictionError,
                onInfoClick = onOpenPredictionInfo,
                weather = weather,
            )
        }
        if (nearbyCanyons.isNotEmpty()) {
            item {
                NearbyCanyonsSection(nearby = nearbyCanyons, onCanyonClick = onNearbyCanyonClick)
            }
        }

        stickyHeader(key = TABS_HEADER_KEY) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            // With the tab row pinned, the tab opens at its top, not where the previous one was scrolled to.
                            val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == TABS_HEADER_KEY }
                            if (header != null && listState.firstVisibleItemIndex >= header.index) {
                                scope.launch { listState.scrollToItem(header.index) }
                            }
                        },
                        text = { Text(title) },
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> topoItems(detail)

            1 -> photosItems(
                photos = detail.photos,
                isOnline = isOnline,
                isLoadingPhotos = isLoadingPhotos,
                photoError = photoError,
                downloadingPhotoIds = downloadingPhotoIds,
                onOpenPhotoGallery = onOpenPhotoGallery,
                onPersistedPhotoMissing = onPersistedPhotoMissing,
            )

            2 -> {
                if (detail.debits.isNotEmpty()) {
                    item { DebitSeasonalityCard(debits = detail.debits) }
                }
                debitItems(detail.debits, isLoadingDebits, debitError, memberAuthors, onUserClick)
            }
        }
    }
}

/** Offline, everything shown comes from the phone: said once here rather than as errors in each section. */
@Composable
private fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.canyon_offline_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailRefreshingBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(R.string.refreshing_canyon_details),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun LazyListScope.debitItems(
    debits: List<Debit>,
    isLoadingDebits: Boolean,
    debitError: String?,
    memberAuthors: Set<String>,
    onUserClick: (String) -> Unit,
) {
    if (isLoadingDebits && debits.isEmpty()) {
        item {
            LoadingSectionItem(text = stringResource(R.string.loading_debits))
        }
    } else if (debits.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 32.dp, end = 32.dp, bottom = 96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = debitError ?: stringResource(R.string.no_debits),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        if (isLoadingDebits) {
            item {
                InlineLoadingBanner(
                    text = stringResource(R.string.loading_debits),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(
            items = debits,
            key = { it.id },
        ) { debit ->
            DebitListItem(
                debit = debit,
                isLatest = debit == debits.first(),
                // A name typed without an account has no profile.
                onAuthorClick = onUserClick.takeIf { debit.auteur in memberAuthors },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

@Composable
internal fun LoadingSectionItem(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 32.dp, top = 32.dp, end = 32.dp, bottom = 96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun InlineLoadingBanner(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val TABS_HEADER_KEY = "canyon_tabs"
