package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.ui.design.LocalDcColors

internal fun LazyListScope.photosItems(
    photos: List<CanyonPhoto>,
    isOnline: Boolean,
    isLoadingPhotos: Boolean,
    photoError: String?,
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
                        !isOnline -> stringResource(R.string.no_offline_photos_without_network)
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
                                listOf(Color.Transparent, LocalDcColors.current.surfacePhotoScrim),
                            ),
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
internal fun HeroPhoto(
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
