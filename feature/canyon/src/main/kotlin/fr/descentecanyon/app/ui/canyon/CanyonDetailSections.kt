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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.sp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonDuration
import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.NearbyCanyon
import fr.descentecanyon.app.domain.model.isAddedInApp
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.components.debitLevelLabel
import fr.descentecanyon.app.ui.components.relativeDayPhrase
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
    /** The map of the points, offered on the header photo and at the head of the topo. */
    onShowMap: (() -> Unit)? = null,
    logbook: List<LogbookEntry> = emptyList(),
    knownCompanions: List<String> = emptyList(),
    onSaveLogbookEntry: (LogbookEntry) -> Unit = {},
    onDeleteLogbookEntry: (Long) -> Unit = {},
    onReportFlow: (LogbookEntry) -> Unit = {},
    onOpenLogbook: () -> Unit = {},
    bottomContentPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
) {
    // A canyon added in the app has neither photos nor flow reports: they live on the site, which does not know it.
    val isAddedInApp = detail.canyon.isAddedInApp
    var selectedTab by rememberSaveable(openDebitsTabInitially) {
        mutableIntStateOf(if (openDebitsTabInitially && !isAddedInApp) TAB_DEBITS else TAB_CONDITIONS)
    }
    val scope = rememberCoroutineScope()
    val tabs = listOf(
        stringResource(R.string.tab_conditions),
        stringResource(R.string.tab_topo),
        if (isAddedInApp) {
            stringResource(R.string.tab_photos)
        } else if (photoError != null && detail.photos.isEmpty()) {
            stringResource(R.string.tab_photos_unavailable)
        } else if (isLoadingPhotos && detail.photos.isEmpty()) {
            stringResource(R.string.tab_photos_loading)
        } else {
            stringResource(R.string.tab_photos_with_count, detail.photos.size)
        },
        if (isAddedInApp) {
            stringResource(R.string.tab_debits)
        } else if (debitError != null && detail.debits.isEmpty()) {
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
                onShowMap = onShowMap,
            )
        }
        stickyHeader(key = TABS_HEADER_KEY) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                tabs.forEachIndexed { index, title ->
                    // Its own content, without the 16 dp the text tab keeps on each side: four tabs and
                    // their counts fit a narrow phone, a long one ("Débits (253)") shrinks rather than wraps.
                    Tab(
                        selected = selectedTab == index,
                        enabled = (index != TAB_PHOTOS && index != TAB_DEBITS) || !isAddedInApp,
                        onClick = {
                            selectedTab = index
                            // With the tab row pinned, the tab opens at its top, not where the previous one was scrolled to.
                            val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == TABS_HEADER_KEY }
                            if (header != null && listState.firstVisibleItemIndex >= header.index) {
                                scope.launch { listState.scrollToItem(header.index) }
                            }
                        },
                    ) {
                        val style = MaterialTheme.typography.titleSmall
                        Text(
                            text = title,
                            style = style,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }

        when (selectedTab) {
            TAB_CONDITIONS -> conditionsItems(
                detail = detail,
                isAddedInApp = isAddedInApp,
                edfStatus = edfStatus,
                isLoadingEdfStatus = isLoadingEdfStatus,
                edfStatusError = edfStatusError,
                edfStatusSourceUrl = edfStatusSourceUrl,
                weather = weather,
                isLoadingWeather = isLoadingWeather,
                weatherError = weatherError,
                predictions = predictions,
                isLoadingPredictions = isLoadingPredictions,
                predictionError = predictionError,
                onOpenPredictionInfo = onOpenPredictionInfo,
                onOpenDebits = { selectedTab = TAB_DEBITS },
                personalNote = personalNote,
                onSavePersonalNote = onSavePersonalNote,
                logbook = logbook,
                knownCompanions = knownCompanions,
                onSaveLogbookEntry = onSaveLogbookEntry,
                onDeleteLogbookEntry = onDeleteLogbookEntry,
                onReportFlow = onReportFlow,
                onOpenLogbook = onOpenLogbook,
                nearbyCanyons = nearbyCanyons,
                onNearbyCanyonClick = onNearbyCanyonClick,
            )

            TAB_TOPO -> {
                onShowMap?.let { showMap ->
                    item(key = "topo_map") {
                        OutlinedButton(
                            onClick = showMap,
                            modifier = Modifier.fillMaxWidth().padding(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ).testTag(TestTags.DETAIL_TOPO_MAP),
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.show_map_points), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                topoItems(detail, onShowMap)
            }

            TAB_PHOTOS -> photosItems(
                photos = detail.photos,
                isOnline = isOnline,
                isLoadingPhotos = isLoadingPhotos,
                photoError = photoError,
                downloadingPhotoIds = downloadingPhotoIds,
                onOpenPhotoGallery = onOpenPhotoGallery,
                onPersistedPhotoMissing = onPersistedPhotoMissing,
            )

            TAB_DEBITS -> {
                if (detail.debits.isNotEmpty()) {
                    item { DebitSeasonalityCard(debits = detail.debits) }
                }
                debitItems(detail.debits, isLoadingDebits, debitError, memberAuthors, onUserClick)
            }
        }
    }
}

/**
 * The Conditions tab: can it be done now. The flow estimated and the last one reported, the weather,
 * the dam releases, the user's own notes and outings here, and the canyons around.
 */
private fun LazyListScope.conditionsItems(
    detail: CanyonDetail,
    isAddedInApp: Boolean,
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
    onOpenDebits: () -> Unit,
    personalNote: String?,
    onSavePersonalNote: (String) -> Unit,
    logbook: List<LogbookEntry>,
    knownCompanions: List<String>,
    onSaveLogbookEntry: (LogbookEntry) -> Unit,
    onDeleteLogbookEntry: (Long) -> Unit,
    onReportFlow: (LogbookEntry) -> Unit,
    onOpenLogbook: () -> Unit,
    nearbyCanyons: List<NearbyCanyon>,
    onNearbyCanyonClick: (Int) -> Unit,
) {
    if (detail.isLocated && !isAddedInApp) {
        item(key = "prediction") {
            CanyonDebitPredictionCard(
                predictions = predictions,
                isLoading = isLoadingPredictions,
                error = predictionError,
                onInfoClick = onOpenPredictionInfo,
                weather = weather,
            )
        }
    }
    detail.debits.firstOrNull()?.takeIf { !isAddedInApp }?.let { latest ->
        item(key = "latest_debit") { LatestDebitRow(debit = latest, onClick = onOpenDebits) }
    }
    if (detail.isLocated) {
        item(key = "weather") {
            CanyonWeatherCard(
                weather = weather,
                isLoading = isLoadingWeather,
                error = weatherError,
            )
        }
    } else {
        item(key = "unlocated") { UnlocatedConditionsCard() }
    }
    if (edfStatusSourceUrl != null) {
        item(key = "edf") {
            CanyonEdfStatusCard(
                status = edfStatus,
                isLoading = isLoadingEdfStatus,
                error = edfStatusError,
                sourceUrl = edfStatusSourceUrl,
            )
        }
    }
    item(key = "my_canyon") {
        val canyon = detail.canyon
        MyCanyonCard(
            canyonId = canyon.id,
            canyonName = canyon.nom,
            memo = personalNote,
            entries = logbook,
            knownCompanions = knownCompanions,
            onSaveMemo = onSavePersonalNote,
            onSaveEntry = onSaveLogbookEntry,
            onDeleteEntry = onDeleteLogbookEntry,
            onReportFlow = onReportFlow,
            onOpenLogbook = onOpenLogbook,
            canReportFlow = !isAddedInApp,
        )
    }
    if (nearbyCanyons.isNotEmpty()) {
        item(key = "nearby") {
            NearbyCanyonsSection(nearby = nearbyCanyons, onCanyonClick = onNearbyCanyonClick)
        }
    }
}

/** The last flow reported, in one line; the reports are in the Débits tab. */
@Composable
private fun LatestDebitRow(debit: Debit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag(TestTags.DETAIL_LATEST_DEBIT),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.size(12.dp).background(debitLevelColor(debit.niveau), CircleShape))
            Text(
                text = stringResource(R.string.canyon_latest_debit, debitLevelLabel(debit.niveau), relativeDayPhrase(debit.date)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
private const val TAB_CONDITIONS = 0
private const val TAB_TOPO = 1
private const val TAB_PHOTOS = 2
private const val TAB_DEBITS = 3
