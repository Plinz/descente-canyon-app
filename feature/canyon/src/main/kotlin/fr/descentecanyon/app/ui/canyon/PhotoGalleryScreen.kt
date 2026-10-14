package fr.descentecanyon.app.ui.canyon

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGalleryScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier, viewModel: PhotoGalleryViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val photos = uiState.photos
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showOverlay by rememberSaveable { mutableStateOf(true) }
    var pendingDownloadPhotoId by remember { mutableStateOf<Long?>(null) }
    val writeStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        val photoId = pendingDownloadPhotoId
        pendingDownloadPhotoId = null
        if (isGranted && photoId != null) {
            viewModel.downloadPhoto(photoId)
        }
    }

    if (uiState.isLoading) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        return
    }

    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onBackClick() }
        return
    }

    BackHandler(onBack = onBackClick)

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { photos.size },
    )
    var zoomedPageIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(uiState.currentIndex, photos.size) {
        if (photos.isNotEmpty() && pagerState.currentPage != uiState.currentIndex) {
            pagerState.scrollToPage(uiState.currentIndex)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            zoomedPageIndex = null
            viewModel.onPageChanged(page)
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

    DisposableEffect(context) {
        val activity = context as? Activity
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousLightStatus = insetsController?.isAppearanceLightStatusBars
        val previousLightNav = insetsController?.isAppearanceLightNavigationBars

        if (window != null && insetsController != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onDispose {
            if (window != null && insetsController != null) {
                previousLightStatus?.let { insetsController.isAppearanceLightStatusBars = it }
                previousLightNav?.let { insetsController.isAppearanceLightNavigationBars = it }
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        val currentPhoto = photos.getOrElse(pagerState.currentPage) { photos.first() }

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = zoomedPageIndex == null,
            ) { page ->
                val photo = photos[page]
                ZoomablePhoto(
                    model = photo.localPath ?: photo.url,
                    contentDescription = photo.description,
                    onError = {
                        if (photo.localPath != null) {
                            viewModel.onPersistedPhotoMissing(photo.id)
                        }
                    },
                    onToggleOverlay = { showOverlay = !showOverlay },
                    onZoomStateChanged = { isZoomed ->
                        zoomedPageIndex = if (isZoomed) page else zoomedPageIndex?.takeIf { it != page }
                    },
                )
            }

            AnimatedVisibility(
                visible = showOverlay,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    color = Color.Black.copy(alpha = 0.34f),
                    contentColor = Color.White,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = Color.White,
                            )
                        }
                        Surface(
                            color = Color.White.copy(alpha = 0.12f),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Text(
                                text = "${pagerState.currentPage + 1}/${photos.size}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                        Box(modifier = Modifier.weight(1f))
                        if (currentPhoto.localPath == null) {
                            IconButton(
                                onClick = {
                                    if (canWritePublicPhotos(context)) {
                                        viewModel.downloadPhoto(currentPhoto.id)
                                    } else {
                                        pendingDownloadPhotoId = currentPhoto.id
                                        writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }
                                },
                                enabled = currentPhoto.id != 0L && !uiState.downloadingPhotoIds.contains(currentPhoto.id),
                            ) {
                                if (uiState.downloadingPhotoIds.contains(currentPhoto.id)) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = stringResource(R.string.photo_download_action),
                                        tint = Color.White,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showOverlay,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (currentPhoto.localPath != null) {
                            PhotoStatusBadge(text = stringResource(R.string.offline_available))
                        } else if (uiState.downloadingPhotoIds.contains(currentPhoto.id)) {
                            PhotoStatusBadge(text = stringResource(R.string.downloading_offline))
                        }
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.34f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            currentPhoto.description?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            currentPhoto.auteur?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    color = Color.White.copy(alpha = 0.82f),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun canWritePublicPhotos(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PhotoStatusBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.14f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ZoomablePhoto(
    model: Any?,
    contentDescription: String?,
    onError: () -> Unit,
    onToggleOverlay: () -> Unit,
    onZoomStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember(model) { mutableStateOf(1f) }
    var offset by remember(model) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(model) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(scale) {
        onZoomStateChanged(scale > 1.01f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                containerSize = size
                offset = constrainPhotoOffset(offset, scale, size)
            }
            .pointerInput(model) {
                detectTapGestures(
                    onTap = { onToggleOverlay() },
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val nextScale = DOUBLE_TAP_PHOTO_ZOOM
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = nextScale
                            offset = constrainPhotoOffset(
                                Offset(
                                    x = (center.x - tapOffset.x) * (nextScale - 1f),
                                    y = (center.y - tapOffset.y) * (nextScale - 1f),
                                ),
                                nextScale,
                                containerSize,
                            )
                        }
                    },
                )
            }
            .pointerInput(model) {
                detectPhotoTransformGestures(
                    isZoomed = { scale > 1.01f },
                ) { pan, zoom ->
                    val nextScale = (scale * zoom).coerceIn(MIN_PHOTO_ZOOM, MAX_PHOTO_ZOOM)
                    val nextOffset = if (nextScale <= 1.01f) {
                        Offset.Zero
                    } else {
                        Offset(
                            x = offset.x + pan.x,
                            y = offset.y + pan.y,
                        )
                    }
                    scale = nextScale
                    offset = constrainPhotoOffset(nextOffset, nextScale, containerSize)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        RetryablePhoto(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
            onError = { onError() },
            loadingContent = {
                CircularProgressIndicator(color = Color.White)
            },
            errorContent = { onRetry ->
                DefaultPhotoError(
                    onRetry = onRetry,
                    message = stringResource(R.string.photo_gallery_load_error),
                )
            },
        )
    }
}

private suspend fun PointerInputScope.detectPhotoTransformGestures(
    isZoomed: () -> Boolean,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            val shouldConsume = pressedCount > 1 || isZoomed()
            if (shouldConsume) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    onGesture(pan, zoom)
                    event.changes.forEach { change ->
                        if (change.positionChanged()) {
                            change.consume()
                        }
                    }
                }
            }
        }
    }
}

private fun constrainPhotoOffset(offset: Offset, scale: Float, containerSize: IntSize): Offset {
    if (scale <= 1f || containerSize.width <= 0 || containerSize.height <= 0) {
        return Offset.Zero
    }
    val maxX = containerSize.width * (scale - 1f) / 2f
    val maxY = containerSize.height * (scale - 1f) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

private const val MIN_PHOTO_ZOOM = 1f
private const val DOUBLE_TAP_PHOTO_ZOOM = 2.5f
private const val MAX_PHOTO_ZOOM = 4f
