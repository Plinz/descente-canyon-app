package fr.descentecanyon.app.ui.canyon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Patterns
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.BibliographyEntry
import fr.descentecanyon.app.domain.model.BibliographyKind
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.Regulation
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.components.CotationBadge
import fr.descentecanyon.app.ui.components.ForbiddenBadge
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.DebitBadge
import fr.descentecanyon.app.ui.components.InterestStars
import fr.descentecanyon.app.ui.components.AppFloatingActionButton
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcMetricTile
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes
import fr.descentecanyon.app.ui.design.LocalDcSpacing
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.map.MapLibreView
import fr.descentecanyon.app.ui.test.TestTags
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitCrue
import fr.descentecanyon.app.ui.theme.DebitTresGros
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanyonDetailScreen(
    canyonId: Int,
    onBackClick: () -> Unit,
    onReportDebitClick: () -> Unit,
    onRateInterestClick: () -> Unit,
    onShowMapClick: () -> Unit,
    onOpenPredictionInfo: () -> Unit,
    onOpenPhotoGallery: (Long) -> Unit,
    onUserClick: (String) -> Unit = {},
    openDebitsTabInitially: Boolean = false,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    refreshDebitsAfterSubmission: Boolean = false,
    onRefreshDebitsAfterSubmissionHandled: () -> Unit = {},
    refreshDetailAfterInterestRating: Boolean = false,
    onRefreshDetailAfterInterestRatingHandled: () -> Unit = {},
    viewModel: CanyonDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val contentWidth = rememberDcContentWidth(maxWidth = 940.dp)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.toggleDebitNotifications() }

    LaunchedEffect(canyonId) {
        PerformanceTrace.logEvent("canyon_detail_screen_visible", "canyonId" to canyonId)
    }

    LaunchedEffect(refreshDebitsAfterSubmission) {
        if (refreshDebitsAfterSubmission) {
            viewModel.refreshDebitsAfterSubmission()
            onRefreshDebitsAfterSubmissionHandled()
        }
    }

    LaunchedEffect(refreshDetailAfterInterestRating) {
        if (refreshDetailAfterInterestRating) {
            viewModel.loadCanyon(canyonId)
            onRefreshDetailAfterInterestRatingHandled()
        }
    }

    LaunchedEffect(uiState.transientMessage) {
        uiState.transientMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearTransientMessage()
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reconcilePersistedPhotos()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactAppBar(
                title = uiState.canyonDetail?.canyon?.nom ?: stringResource(R.string.canyon_fallback_title, canyonId),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onReportDebitClick,
                        modifier = Modifier.testTag(TestTags.detailReportDebitButton),
                    ) {
                        AddDebitIcon(contentDescription = stringResource(R.string.debit_add_action))
                    }
                    IconButton(
                        onClick = onRateInterestClick,
                        modifier = Modifier.testTag(TestTags.detailRateInterestButton),
                    ) {
                        AddInterestIcon(contentDescription = stringResource(R.string.interest_rating_add_action))
                    }
                    IconButton(
                        onClick = {
                            if (!uiState.isDebitNotificationsEnabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.toggleDebitNotifications()
                            }
                        },
                        modifier = Modifier.testTag(TestTags.detailDebitNotificationButton),
                    ) {
                        Icon(
                            imageVector = if (uiState.isDebitNotificationsEnabled) {
                                Icons.Default.NotificationsActive
                            } else {
                                Icons.Default.NotificationsNone
                            },
                            contentDescription = if (uiState.isDebitNotificationsEnabled) {
                                stringResource(R.string.notifications_canyon_unfollow_action)
                            } else {
                                stringResource(R.string.notifications_canyon_follow_action)
                            },
                            tint = if (uiState.isDebitNotificationsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    IconButton(
                        onClick = viewModel::toggleFavorite,
                        modifier = Modifier.testTag(TestTags.detailFavoriteButton),
                    ) {
                        Icon(
                            imageVector = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (uiState.isFavorite) {
                                stringResource(R.string.remove_favorite)
                            } else {
                                stringResource(R.string.add_favorite)
                            },
                            tint = if (uiState.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = uiState.error ?: stringResource(R.string.error_generic),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadCanyon(canyonId) }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }

                uiState.canyonDetail != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        CanyonDetailContent(
                            detail = uiState.canyonDetail!!,
                            isRefreshingDetail = uiState.isRefreshingDetail,
                            isOnline = uiState.isOnline,
                            isLoadingPhotos = uiState.isLoadingPhotos,
                            photoError = uiState.photoError,
                            isLoadingDebits = uiState.isLoadingDebits,
                            debitError = uiState.debitError,
                            edfStatus = uiState.edfStatus,
                            isLoadingEdfStatus = uiState.isLoadingEdfStatus,
                            edfStatusError = uiState.edfStatusError,
                            edfStatusSourceUrl = uiState.edfStatusSourceUrl,
                            weather = uiState.weather,
                            isLoadingWeather = uiState.isLoadingWeather,
                            weatherError = uiState.weatherError,
                            predictions = uiState.predictions,
                            isLoadingPredictions = uiState.isLoadingPredictions,
                            predictionError = uiState.predictionError,
                            onOpenPredictionInfo = onOpenPredictionInfo,
                            downloadingPhotoIds = uiState.downloadingPhotoIds,
                            onOpenPhotoGallery = onOpenPhotoGallery,
                            onUserClick = onUserClick,
                            onPersistedPhotoMissing = viewModel::onPersistedPhotoMissing,
                            openDebitsTabInitially = openDebitsTabInitially,
                            bottomContentPadding = contentPadding.calculateBottomPadding() + 96.dp,
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(contentWidth),
                        )
                    }
                }
            }

            if (uiState.canyonDetail != null && !uiState.isLoading && uiState.error == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    AppFloatingActionButton(
                        onClick = onShowMapClick,
                        modifier = Modifier.padding(
                            end = 20.dp,
                            bottom = contentPadding.calculateBottomPadding() + 20.dp,
                        ),
                        icon = { iconModifier ->
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = stringResource(R.string.show_map_points),
                                modifier = iconModifier,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddDebitIcon(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(30.dp)) {
        Icon(
            imageVector = Icons.Default.WaterDrop,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .size(26.dp)
                .align(Alignment.CenterStart),
        )
        Surface(
            modifier = Modifier
                .size(15.dp)
                .align(Alignment.BottomEnd),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

@Composable
private fun AddInterestIcon(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(30.dp)) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .size(26.dp)
                .align(Alignment.CenterStart),
        )
        Surface(
            modifier = Modifier
                .size(15.dp)
                .align(Alignment.BottomEnd),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CanyonDetailContent(
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
    onUserClick: (String) -> Unit,
    onPersistedPhotoMissing: (Long) -> Unit,
    openDebitsTabInitially: Boolean,
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable(openDebitsTabInitially) {
        mutableIntStateOf(if (openDebitsTabInitially) 2 else 0)
    }
    val listState = rememberLazyListState()
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
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            AnimatedVisibility(visible = isRefreshingDetail) {
                DetailRefreshingBanner()
            }
        }
        item {
            SummaryCard(
                detail = detail,
                onPersistedPhotoMissing = onPersistedPhotoMissing,
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
            CanyonDailyForecastCard(
                forecasts = weather?.dailyForecasts ?: emptyList(),
            )
        }
        item {
            CanyonDebitPredictionCard(
                predictions = predictions,
                isLoading = isLoadingPredictions,
                error = predictionError,
                onInfoClick = onOpenPredictionInfo,
            )
        }

        stickyHeader {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
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
                isOfflineSaved = detail.canyon.isOffline,
                downloadingPhotoIds = downloadingPhotoIds,
                onOpenPhotoGallery = onOpenPhotoGallery,
                onPersistedPhotoMissing = onPersistedPhotoMissing,
            )
            2 -> debitItems(detail.debits, isLoadingDebits, debitError, onUserClick)
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

@Composable
private fun SummaryCard(
    detail: CanyonDetail,
    onPersistedPhotoMissing: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canyon = detail.canyon
    val heroPhoto = detail.photos.firstOrNull()
    val colors = LocalDcColors.current
    val shapes = LocalDcShapes.current
    val spacing = LocalDcSpacing.current

    DcCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
        variant = DcCardVariant.Elevated,
        contentPadding = 0.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
            ) {
                if (heroPhoto != null) {
                    HeroPhoto(
                        photo = heroPhoto,
                        onPersistedPhotoMissing = onPersistedPhotoMissing,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(colors.waterDeep, colors.backgroundElevated, colors.rock.copy(alpha = 0.72f))
                                )
                            ),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, colors.surfacePhotoScrim),
                                startY = 40f,
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canyon.isForbidden) {
                            ForbiddenBadge()
                        } else {
                            CotationBadge(cotation = canyon.cotation)
                        }
                        canyon.interet?.let { interest ->
                            InterestStars(interest = interest)
                        }
                    }
                    Text(
                        text = canyon.nom,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.snow,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = colors.waterMist,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${canyon.commune} - ${canyon.pays}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.waterMist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                heroPhoto?.auteur?.takeIf { it.isNotBlank() }?.let { author ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(spacing.md),
                        shape = shapes.pill,
                        color = colors.surfacePhotoScrim.copy(alpha = 0.72f),
                        contentColor = colors.snow,
                    ) {
                        Text(
                            text = author,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            SummaryStatsGrid(
                detail = detail,
                modifier = Modifier.padding(spacing.lg),
            )
        }
    }
}

@Composable
private fun SummaryStatsGrid(
    detail: CanyonDetail,
    modifier: Modifier = Modifier,
) {
    val canyon = detail.canyon

    val parcoursStats = listOf(
        SummaryStat(stringResource(R.string.altitude), canyon.altitudeDepart?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.elevation), canyon.denivele?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.length), canyon.longueur?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.max_waterfall), canyon.cascadeMax?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.rope), canyon.cordeMin?.let { "${it}m" }),
    ).filter { !it.value.isNullOrBlank() }

    val timeStats = listOf(
        SummaryStat(stringResource(R.string.approach_time), canyon.tempsApproche),
        SummaryStat(stringResource(R.string.descent_time), canyon.tempsDescente),
        SummaryStat(stringResource(R.string.return_time), canyon.tempsRetour),
    ).filter { !it.value.isNullOrBlank() }

    val locationStats = listOf(
        SummaryStat(
            stringResource(R.string.communes),
            canyon.communes.takeIf { it.isNotEmpty() }?.joinToString(),
        ),
        SummaryStat(stringResource(R.string.region), canyon.region),
        SummaryStat(stringResource(R.string.department), canyon.departement),
        SummaryStat(stringResource(R.string.massif), canyon.massif),
        SummaryStat(stringResource(R.string.basin), canyon.bassin),
        SummaryStat(
            stringResource(R.string.watershed_area),
            detail.watershed?.areaKm2?.let(::formatAreaKm2),
        ),
        SummaryStat(stringResource(R.string.watercourse), canyon.coursEau),
    ).filter { !it.value.isNullOrBlank() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (locationStats.isNotEmpty()) {
            SummarySection(
                title = stringResource(R.string.location),
                stats = locationStats,
            )
        }
        if (parcoursStats.isNotEmpty()) {
            SummaryMetricSection(
                title = stringResource(R.string.canyon_summary_parcours),
                stats = parcoursStats,
            )
        }
        if (timeStats.isNotEmpty()) {
            SummaryMetricSection(
                title = stringResource(R.string.canyon_summary_timing),
                stats = timeStats,
            )
        }
    }
}

private data class SummaryStat(
    val label: String,
    val value: String?,
)

private fun formatAreaKm2(areaKm2: Double): String {
    val precision = if (areaKm2 >= 100.0) "%.0f" else if (areaKm2 >= 10.0) "%.1f" else "%.2f"
    return String.format(Locale.getDefault(), "$precision km2", areaKm2)
}

@Composable
private fun SummarySection(
    title: String,
    stats: List<SummaryStat>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                stats.forEachIndexed { index, stat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stat.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stat.value ?: "-",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (index != stats.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricSection(
    title: String,
    stats: List<SummaryStat>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = LocalDcColors.current.textSecondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.chunked(2).forEach { rowStats ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowStats.forEach { stat ->
                        DcMetricTile(
                            label = stat.label,
                            value = stat.value ?: "-",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowStats.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun LazyListScope.topoItems(detail: CanyonDetail) {
        val sectionModifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        detail.accesAval?.let { text ->
            item {
                CollapsibleSection(
                    title = if (detail.accesAmont.isNullOrBlank()) {
                        stringResource(R.string.access)
                    } else {
                        stringResource(R.string.access_downstream)
                    },
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.accesAmont?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.access_upstream),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.approche?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.approach),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.descente?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.descent),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.retour?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.return_path),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.engagement?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.engagement),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.periode?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.period),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.geologie?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.geology),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.historique?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.history),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        detail.remarques?.let { text ->
            item {
                CollapsibleSection(
                    title = stringResource(R.string.notes),
                    content = text,
                    modifier = sectionModifier,
                )
            }
        }
        if (detail.bibliography.isNotEmpty()) {
            item {
                BibliographySection(
                    entries = detail.bibliography,
                    modifier = sectionModifier,
                )
            }
        }
        if (detail.regulations.isNotEmpty()) {
            item {
                RegulationSection(
                    regulations = detail.regulations,
                    modifier = sectionModifier,
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(132.dp)) // FAB clearance
        }
}

@Composable
private fun CollapsibleSection(
    title: String,
    content: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val preview = remember(content) { normalizeSectionText(content).lineSequence().joinToString(" ").trim() }
    val colors = LocalDcColors.current

    DcCard(
        modifier = modifier.fillMaxWidth(),
        variant = if (expanded) DcCardVariant.Elevated else DcCardVariant.Surface,
        contentPadding = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!expanded && preview.isNotBlank()) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = colors.borderSubtle)
                    LinkifiedSectionText(
                        content = content,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkifiedSectionText(
    content: String,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val text = remember(content, linkColor) {
        buildLinkifiedAnnotatedString(
            content = normalizeSectionText(content),
            linkColor = linkColor,
        )
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(color = bodyColor),
        modifier = modifier,
    )
}

private val HtmlLineBreakRegex = Regex("""(?i)<br\s*/?>""")

private fun normalizeSectionText(content: String): String {
    return content
        .replace("\r\n", "\n")
        .replace(HtmlLineBreakRegex, "\n")
}

private fun buildLinkifiedAnnotatedString(
    content: String,
    linkColor: Color,
): AnnotatedString {
    val matcher = Patterns.WEB_URL.matcher(content)
    return buildAnnotatedString {
        var currentIndex = 0
        while (matcher.find()) {
            val rawMatch = matcher.group().orEmpty()
            val linkText = rawMatch.trimTrailingUrlPunctuation()
            if (linkText.isBlank()) {
                continue
            }

            append(content.substring(currentIndex, matcher.start()))
            withLink(
                LinkAnnotation.Url(
                    url = normalizeExternalUrl(linkText),
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        )
                    ),
                )
            ) {
                append(linkText)
            }
            append(rawMatch.substring(linkText.length))
            currentIndex = matcher.end()
        }

        if (currentIndex < content.length) {
            append(content.substring(currentIndex))
        }
    }
}

private fun normalizeExternalUrl(url: String): String {
    return if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
        url
    } else {
        "https://$url"
    }
}

private fun String.trimTrailingUrlPunctuation(): String = trimEnd('.', ',', ';', ':', ')', ']', '}')

@Composable
private fun BibliographySection(
    entries: List<BibliographyEntry>,
    modifier: Modifier = Modifier,
) {
    val topoguides = entries.filter { it.kind == BibliographyKind.TOPOGUIDE }
    val maps = entries.filter { it.kind == BibliographyKind.MAP }
    val resources = entries.filter { it.kind == BibliographyKind.RESOURCE }

    var expanded by rememberSaveable { mutableStateOf(false) }

    DcCard(modifier = modifier.fillMaxWidth(), variant = DcCardVariant.Surface, contentPadding = 0.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.bibliography),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (topoguides.isNotEmpty()) {
                        BibliographyGroup(title = stringResource(R.string.topoguides_with_count, topoguides.size), entries = topoguides)
                    }
                    if (maps.isNotEmpty()) {
                        BibliographyGroup(title = stringResource(R.string.maps_with_count, maps.size), entries = maps)
                    }
                    if (resources.isNotEmpty()) {
                        BibliographyGroup(title = stringResource(R.string.resources_with_count, resources.size), entries = resources)
                    }
                }
            }
        }
    }
}

@Composable
private fun BibliographyGroup(
    title: String,
    entries: List<BibliographyEntry>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = LocalDcColors.current.textSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        entries.forEach { entry ->
            DcCard(variant = DcCardVariant.Condition, contentPadding = 12.dp) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    entry.authors.takeIf { it.isNotEmpty() }?.let { authors ->
                        Text(
                            text = stringResource(R.string.bibliography_authors, authors.joinToString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    bibliographyMetaLine(entry)?.let { meta ->
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.status?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.url?.let { url ->
                        LinkRow(
                            icon = Icons.Default.Language,
                            label = url,
                            url = url,
                        )
                    }
                    entry.detailUrl?.let { url ->
                        LinkRow(
                            icon = Icons.Default.Description,
                            label = stringResource(R.string.open_reference),
                            url = url,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegulationSection(
    regulations: List<Regulation>,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    DcCard(modifier = modifier.fillMaxWidth(), variant = DcCardVariant.Warning, contentPadding = 0.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.regulations_with_count, regulations.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    regulations.forEach { regulation ->
                        RegulationItem(regulation = regulation)
                    }
                }
            }
        }
    }
}

@Composable
private fun RegulationItem(
    regulation: Regulation,
    modifier: Modifier = Modifier,
) {
    val status = regulation.status.orEmpty()
    val isInactive = status.contains("obsol", ignoreCase = true) ||
        status.contains("abrog", ignoreCase = true)

    val containerColor = when {
        status.contains("actif", ignoreCase = true) -> DebitCorrect.copy(alpha = 0.12f)
        status.contains("temp", ignoreCase = true) -> DebitTresGros.copy(alpha = 0.12f)
        isInactive -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }
    val accentColor = when {
        status.contains("actif", ignoreCase = true) -> DebitCorrect
        status.contains("temp", ignoreCase = true) -> DebitTresGros
        isInactive -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    var expanded by rememberSaveable(regulation.id) { mutableStateOf(!isInactive) }
    val statusText = regulation.status?.let { stringResource(R.string.regulation_status, it) }
    val actionText = regulation.action?.let { stringResource(R.string.regulation_action, it) }
    val effectiveDateText = regulation.effectiveDate?.let {
        stringResource(R.string.regulation_effective_date, it)
    }

    DcCard(
        modifier = modifier.fillMaxWidth(),
        variant = DcCardVariant.Warning,
        contentPadding = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = regulation.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    RegulationStatusBadge(text = regulation.status ?: stringResource(R.string.regulation_status_unknown), accentColor)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = accentColor,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    val body = buildString {
                        appendLineIfNotBlank(statusText)
                        appendLineIfNotBlank(actionText)
                        appendLineIfNotBlank(effectiveDateText)
                        appendLineIfNotBlank(regulation.summary)
                        if (!isInactive) {
                            appendLineIfNotBlank(regulation.remark)
                            appendLineIfNotBlank(regulation.details)
                        }
                    }.trim()
                    if (body.isNotBlank()) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (!isInactive) {
                        regulation.attachments.forEach { attachment ->
                            LinkRow(
                                icon = Icons.Default.Description,
                                label = attachment.label,
                                url = attachment.url,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    LinkRow(
                        icon = Icons.Default.Language,
                        label = stringResource(R.string.open_regulation_page),
                        url = regulation.textUrl,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RegulationStatusBadge(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = LocalDcShapes.current.pill,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.16f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.36f)),
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun bibliographyMetaLine(entry: BibliographyEntry): String? {
    val parts = buildList {
        entry.publicationYear?.let { add(it.toString()) }
        entry.editor?.let { add(it) }
        entry.reference?.let { add(it) }
        entry.scale?.let { add(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" - ")
}

private fun StringBuilder.appendLineIfNotBlank(value: String?) {
    if (!value.isNullOrBlank()) {
        if (isNotEmpty()) {
            append("\n\n")
        }
        append(value.trim())
    }
}

private fun LazyListScope.photosItems(
    photos: List<CanyonPhoto>,
    isOnline: Boolean,
    isLoadingPhotos: Boolean,
    photoError: String?,
    isOfflineSaved: Boolean,
    downloadingPhotoIds: Set<Long>,
    onOpenPhotoGallery: (Long) -> Unit,
    onPersistedPhotoMissing: (Long) -> Unit,
) {
    if (isLoadingPhotos && photos.isEmpty()) {
        item {
            LoadingSectionItem(text = stringResource(R.string.loading_photos))
        }
    } else if (photos.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 32.dp, end = 32.dp, bottom = 96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        photoError != null -> photoError
                        !isOnline && isOfflineSaved -> stringResource(R.string.no_offline_photos_without_network)
                        else -> stringResource(R.string.no_photos)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        if (isLoadingPhotos) {
            item {
                InlineLoadingBanner(
                    text = stringResource(R.string.loading_photos),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(
            items = photos,
            key = { it.id.takeIf { id -> id != 0L } ?: it.url.hashCode().toLong() },
        ) { photo ->
            PhotoCard(
                photo = photo,
                isDownloading = downloadingPhotoIds.contains(photo.id),
                onOpen = {
                    onOpenPhotoGallery(photo.id)
                },
                onPersistedPhotoMissing = onPersistedPhotoMissing,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

@Composable
private fun PhotoCard(
    photo: CanyonPhoto,
    isDownloading: Boolean,
    onOpen: () -> Unit,
    onPersistedPhotoMissing: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            ProgressivePhoto(
                photo = photo,
                onPersistedPhotoMissing = onPersistedPhotoMissing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentScale = ContentScale.Crop,
            )
            val photoAuthor = photo.auteur?.takeIf { it.isNotBlank() }
            val photoDescription = photo.description?.takeIf { it.isNotBlank() }
            if (photoAuthor != null || photoDescription != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, LocalDcColors.current.surfacePhotoScrim)
                            )
                        )
                        .padding(12.dp),
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart),
                    ) {
                        photoDescription?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalDcColors.current.snow,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        photoAuthor?.let { author ->
                            Text(
                                text = author,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalDcColors.current.waterMist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = if (photoDescription != null) 2.dp else 0.dp),
                            )
                        }
                    }
                }
            }
            if (isDownloading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun HeroPhoto(
    photo: CanyonPhoto,
    onPersistedPhotoMissing: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale,
) {
    val fullModel = photo.localPath ?: photo.url.takeIf { it.isNotBlank() } ?: photo.thumbnailUrl.orEmpty()
    val fallbackModel = photo.thumbnailUrl?.takeIf { it.isNotBlank() && it != fullModel }
    var displayedModel by remember(photo.localPath, photo.url, photo.thumbnailUrl) {
        mutableStateOf(fullModel)
    }

    RetryablePhoto(
        model = displayedModel,
        contentDescription = photo.description,
        modifier = modifier,
        contentScale = contentScale,
        onError = {
            if (photo.localPath != null) {
                onPersistedPhotoMissing(photo.id)
            } else if (fallbackModel != null && displayedModel != fallbackModel) {
                displayedModel = fallbackModel
            }
        },
        errorContent = { onRetry ->
            DefaultPhotoError(
                onRetry = {
                    if (fallbackModel != null && displayedModel != fallbackModel) {
                        displayedModel = fallbackModel
                    } else {
                        onRetry()
                    }
                },
                message = stringResource(R.string.photo_load_error),
            )
        },
    )
}

@Composable
private fun ProgressivePhoto(
    photo: CanyonPhoto,
    onPersistedPhotoMissing: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale,
) {
    val previewModel = photo.localPath ?: photo.thumbnailUrl ?: photo.url
    val fullModel = photo.localPath ?: photo.url
    val canFallbackToFullImage = photo.localPath == null &&
        !photo.thumbnailUrl.isNullOrBlank() &&
        photo.thumbnailUrl != photo.url
    var displayedModel by remember(photo.localPath, photo.thumbnailUrl, photo.url) {
        mutableStateOf(previewModel)
    }

    RetryablePhoto(
        model = displayedModel,
        contentDescription = photo.description,
        modifier = modifier,
        contentScale = contentScale,
        onError = {
            if (photo.localPath != null) {
                onPersistedPhotoMissing(photo.id)
            } else if (canFallbackToFullImage && displayedModel != fullModel) {
                displayedModel = fullModel
            }
        },
        errorContent = { onRetry ->
            DefaultPhotoError(
                onRetry = {
                    if (canFallbackToFullImage && displayedModel != fullModel) {
                        displayedModel = fullModel
                    } else {
                        onRetry()
                    }
                },
                message = stringResource(R.string.photo_load_error),
            )
        },
    )
}

private fun LazyListScope.debitItems(
    debits: List<Debit>,
    isLoadingDebits: Boolean,
    debitError: String?,
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
                onAuthorClick = onUserClick,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

@Composable
private fun LoadingSectionItem(
    text: String,
    modifier: Modifier = Modifier,
) {
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
private fun InlineLoadingBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanyonGeoPointsSheet(
    detail: CanyonDetail,
    onDismiss: () -> Unit,
    onNavigate: (GeoPoint) -> Unit,
    onOpenFullscreen: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.show_map_points),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = detail.canyon.nom,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenFullscreen) {
                    Text(text = stringResource(R.string.fullscreen_map))
                }
            }

            CanyonGeoPointsMapAndList(
                detail = detail,
                onNavigate = onNavigate,
                mapHeight = 260.dp,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CanyonGeoPointsMapAndList(
    detail: CanyonDetail,
    onNavigate: (GeoPoint) -> Unit,
    mapHeight: androidx.compose.ui.unit.Dp,
) {
    val context = LocalContext.current
    val markers = remember(detail.geoPoints, context) {
        detail.geoPoints.mapIndexed { index, point ->
            fr.descentecanyon.app.domain.model.CanyonSummary(
                id = detail.canyon.id * 10 + index,
                nom = point.navigationLabel(context),
                pays = detail.canyon.pays,
                cotation = detail.canyon.cotation,
                url = detail.canyon.url,
                latitude = point.latitude,
                longitude = point.longitude,
                markerType = point.type,
            )
        }
    }

    if (markers.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            MapLibreView(
                markers = markers,
                userLatitude = null,
                userLongitude = null,
                onMarkerClick = {},
                clusterMarkers = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mapHeight),
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = detail.geoPoints.sortedBy { it.type.navigationPriority() },
            key = { it.id.takeIf { id -> id != 0L } ?: (it.latitude.toString() + it.longitude.toString()) },
        ) { point ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(point.type.mapColor(), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = point.displayName(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = point.type.mapColor(),
                            )
                        }
                        point.displaySubtitle()?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.map_location_coordinates,
                                point.latitude,
                                point.longitude,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onNavigate(point) }) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.navigate))
                    }
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CanyonGeoPointsFullScreenDialog(
    detail: CanyonDetail,
    onDismiss: () -> Unit,
    onNavigate: (GeoPoint) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = CardDefaults.shape,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.show_map_points),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = detail.canyon.nom,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                CanyonGeoPointsMapAndList(
                    detail = detail,
                    onNavigate = onNavigate,
                    mapHeight = 360.dp,
                )
            }
        }
    }
}

private fun openNavigation(
    context: android.content.Context,
    point: GeoPoint,
) {
    val label = Uri.encode(point.navigationLabel(context))
    val uri = Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}($label)")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}
