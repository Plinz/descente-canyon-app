package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import fr.descentecanyon.app.core.ui.R

@Composable
fun RetryablePhoto(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale,
    onError: ((Throwable?) -> Unit)? = null,
    loadingContent: @Composable BoxScope.() -> Unit = {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.5.dp,
        )
    },
    errorContent: @Composable BoxScope.(onRetry: () -> Unit) -> Unit = { onRetry ->
        DefaultPhotoError(onRetry = onRetry)
    },
) {
    val context = LocalContext.current
    var retryVersion by remember(model) { mutableIntStateOf(0) }
    val request = remember(context, model, retryVersion) {
        coil3.request.ImageRequest.Builder(context)
            .data(model)
            .build()
    }
    val painter = rememberAsyncImagePainter(model = request)
    val state by painter.state.collectAsState()
    val errorState = state as? AsyncImagePainter.State.Error

    LaunchedEffect(errorState) {
        errorState?.let { onError?.invoke(it.result.throwable) }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            contentScale = contentScale,
        )

        when (state) {
            is AsyncImagePainter.State.Loading,
            is AsyncImagePainter.State.Empty,
            -> loadingContent()

            is AsyncImagePainter.State.Error -> errorContent {
                retryVersion += 1
            }

            is AsyncImagePainter.State.Success -> Unit
        }
    }
}

@Composable
fun DefaultPhotoError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.photo_load_error),
    showAction: Boolean = true,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (showAction) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}
