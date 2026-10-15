package fr.descentecanyon.app.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.components.CanyonSummaryCard
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.DcSectionHeader
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import fr.descentecanyon.app.ui.test.TestTags

@Composable
fun FavoritesScreen(
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth()
    val screenHorizontalPadding = rememberDcScreenHorizontalPadding()
    val snackbarHostState = remember { SnackbarHostState() }
    val removedMessage = uiState.removedFavorite?.let { stringResource(R.string.favorite_removed, it.nom) }
    val undoLabel = stringResource(R.string.undo)

    LaunchedEffect(removedMessage) {
        removedMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(message = removedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemoveFavorite() else viewModel.onRemovedFavoriteShown()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalDcColors.current.backgroundBase)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(contentWidth)
                .align(Alignment.TopCenter)
                .padding(horizontal = screenHorizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            DcSectionHeader(
                title = stringResource(R.string.tab_favorites),
                subtitle = stringResource(R.string.favorites_screen_subtitle),
                modifier = Modifier.padding(bottom = 12.dp),
            )

            uiState.error?.let { error ->
                Text(
                    text = error.asString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            ) {
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.favorites.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        DcEmptyState(
                            title = stringResource(R.string.no_favorites),
                            icon = Icons.Default.FavoriteBorder,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.testTag(TestTags.FAVORITES_LIST),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = uiState.favorites,
                            key = { it.id },
                        ) { canyon ->
                            FavoriteDismissItem(
                                onRemove = { viewModel.removeFavorite(canyon) },
                            ) {
                                CanyonSummaryCard(
                                    canyon = canyon,
                                    onClick = { onCanyonClick(canyon.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
        )
    }
}

@Composable
private fun FavoriteDismissItem(onRemove: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
            }
            value != SwipeToDismissBoxValue.StartToEnd
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            val isDismissed = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart ||
                dismissState.currentValue == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.remove_favorite),
                        tint = if (isDismissed) MaterialTheme.colorScheme.error else Color.Transparent,
                        modifier = Modifier.padding(end = 20.dp),
                    )
                }
            }
        },
    ) {
        content()
    }
}
