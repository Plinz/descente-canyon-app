package fr.descentecanyon.app.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.FavoriteFolder
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

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<FavoriteFolder?>(null) }
    var canyonForFolderAssignment by remember { mutableStateOf<CanyonSummary?>(null) }

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
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (uiState.rawFavorites.isNotEmpty()) {
                // Folders Bar
                FavoritesFoldersBar(
                    folders = uiState.folders,
                    selectedFolderId = uiState.selectedFolderId,
                    onSelectFolder = viewModel::selectFolder,
                    onCreateFolderClick = { showCreateFolderDialog = true },
                    onDeleteFolderClick = { folderToDelete = it },
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                // Filters Bar
                FavoritesFilterBar(
                    uiState = uiState,
                    onSortSelected = viewModel::setSortOption,
                    onCountrySelected = viewModel::setCountryFilter,
                    onRegionSelected = viewModel::setRegionFilter,
                    onMinRatingSelected = viewModel::setMinRatingFilter,
                    onResetFilters = viewModel::resetFilters,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
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
                } else if (uiState.rawFavorites.isEmpty()) {
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
                } else if (uiState.filteredFavorites.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            DcEmptyState(
                                title = stringResource(R.string.favorite_no_matching_filters),
                                icon = Icons.Default.FilterList,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = viewModel::resetFilters) {
                                Text(stringResource(R.string.favorite_reset_filters))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.testTag(TestTags.favoritesList),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = uiState.filteredFavorites,
                            key = { it.id },
                        ) { canyon ->
                            FavoriteDismissItem(
                                onRemove = { viewModel.removeFavorite(canyon.id) },
                            ) {
                                CanyonSummaryCard(
                                    canyon = canyon,
                                    onClick = { onCanyonClick(canyon.id) },
                                    onFolderClick = { canyonForFolderAssignment = canyon },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Create Folder Dialog
    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { name ->
                viewModel.createFolder(name)
                showCreateFolderDialog = false
            },
        )
    }

    // Delete Folder Dialog
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.favorite_folder_delete_title)) },
            text = { Text(stringResource(R.string.favorite_folder_delete_confirm, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder(folder.id)
                        folderToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.favorite_folder_delete_title), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Manage Canyon Folders Dialog
    canyonForFolderAssignment?.let { canyon ->
        ManageCanyonFoldersDialog(
            canyon = canyon,
            folders = uiState.folders,
            canyonFolderMap = uiState.canyonFolderMap,
            onToggleFolder = { folderId -> viewModel.toggleCanyonInFolder(canyon.id, folderId) },
            onCreateFolderClick = { showCreateFolderDialog = true },
            onDismiss = { canyonForFolderAssignment = null }
        )
    }
}

@Composable
private fun FavoritesFoldersBar(
    folders: List<FavoriteFolder>,
    selectedFolderId: Int?,
    onSelectFolder: (Int?) -> Unit,
    onCreateFolderClick: () -> Unit,
    onDeleteFolderClick: (FavoriteFolder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Tous les favoris"
        FilterChip(
            selected = selectedFolderId == null,
            onClick = { onSelectFolder(null) },
            label = { Text(stringResource(R.string.favorite_folder_all)) },
            leadingIcon = {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        )

        // Custom Folders
        folders.forEach { folder ->
            var showMenu by remember { mutableStateOf(false) }

            Box {
                FilterChip(
                    selected = selectedFolderId == folder.id,
                    onClick = { onSelectFolder(folder.id) },
                    label = { Text("${folder.name} (${folder.canyonCount})") },
                    leadingIcon = {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(16.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.favorite_folder_delete_title)) },
                        onClick = {
                            showMenu = false
                            onDeleteFolderClick(folder)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    )
                }
            }
        }

        // "+ Nouveau dossier"
        FilterChip(
            selected = false,
            onClick = onCreateFolderClick,
            label = { Text(stringResource(R.string.favorite_folder_create)) },
            leadingIcon = {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            },
        )
    }
}

@Composable
private fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.favorite_folder_create_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.favorite_folder_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(folderName) },
                enabled = folderName.isNotBlank()
            ) {
                Text(stringResource(R.string.favorite_folder_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun ManageCanyonFoldersDialog(
    canyon: CanyonSummary,
    folders: List<FavoriteFolder>,
    canyonFolderMap: Map<Int, Set<Int>>,
    onToggleFolder: (Int) -> Unit,
    onCreateFolderClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val assignedFolderIds = canyonFolderMap[canyon.id] ?: emptySet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${canyon.nom} - ${stringResource(R.string.favorite_folder_manage_title)}") },
        text = {
            Column {
                if (folders.isEmpty()) {
                    Text(
                        text = "Aucun dossier créé.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    folders.forEach { folder ->
                        val isAssigned = assignedFolderIds.contains(folder.id)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = isAssigned,
                                onCheckedChange = { onToggleFolder(folder.id) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        onDismiss()
                        onCreateFolderClick()
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.favorite_folder_create))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun FavoritesFilterBar(
    uiState: FavoritesUiState,
    onSortSelected: (FavoriteSortOption) -> Unit,
    onCountrySelected: (String?) -> Unit,
    onRegionSelected: (String?) -> Unit,
    onMinRatingSelected: (Float) -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var countryMenuExpanded by remember { mutableStateOf(false) }
    var regionMenuExpanded by remember { mutableStateOf(false) }
    var ratingMenuExpanded by remember { mutableStateOf(false) }

    val hasActiveFilters = uiState.selectedCountry != null ||
            uiState.selectedRegion != null ||
            uiState.minRating > 0f ||
            uiState.selectedSort != FavoriteSortOption.DATE_ADDED_DESC

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sort Dropdown Chip
        Box {
            FilterChip(
                selected = uiState.selectedSort != FavoriteSortOption.DATE_ADDED_DESC,
                onClick = { sortMenuExpanded = true },
                label = {
                    val sortLabel = when (uiState.selectedSort) {
                        FavoriteSortOption.DATE_ADDED_DESC -> stringResource(R.string.favorite_sort_date_desc)
                        FavoriteSortOption.DATE_ADDED_ASC -> stringResource(R.string.favorite_sort_date_asc)
                        FavoriteSortOption.RATING_DESC -> stringResource(R.string.favorite_sort_rating_desc)
                        FavoriteSortOption.NAME_ASC -> stringResource(R.string.favorite_sort_name_asc)
                    }
                    Text("Tri: $sortLabel")
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )

            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
            ) {
                FavoriteSortOption.entries.forEach { option ->
                    val label = when (option) {
                        FavoriteSortOption.DATE_ADDED_DESC -> stringResource(R.string.favorite_sort_date_desc)
                        FavoriteSortOption.DATE_ADDED_ASC -> stringResource(R.string.favorite_sort_date_asc)
                        FavoriteSortOption.RATING_DESC -> stringResource(R.string.favorite_sort_rating_desc)
                        FavoriteSortOption.NAME_ASC -> stringResource(R.string.favorite_sort_name_asc)
                    }
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onSortSelected(option)
                            sortMenuExpanded = false
                        },
                    )
                }
            }
        }

        // Country Filter Chip
        if (uiState.availableCountries.isNotEmpty()) {
            Box {
                FilterChip(
                    selected = uiState.selectedCountry != null,
                    onClick = { countryMenuExpanded = true },
                    label = { Text(uiState.selectedCountry ?: stringResource(R.string.favorite_filter_country)) },
                    leadingIcon = {
                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )

                DropdownMenu(
                    expanded = countryMenuExpanded,
                    onDismissRequest = { countryMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.favorite_filter_all)) },
                        onClick = {
                            onCountrySelected(null)
                            countryMenuExpanded = false
                        },
                    )
                    uiState.availableCountries.forEach { country ->
                        DropdownMenuItem(
                            text = { Text(country) },
                            onClick = {
                                onCountrySelected(country)
                                countryMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // Region Filter Chip
        if (uiState.availableRegions.isNotEmpty()) {
            Box {
                FilterChip(
                    selected = uiState.selectedRegion != null,
                    onClick = { regionMenuExpanded = true },
                    label = { Text(uiState.selectedRegion ?: stringResource(R.string.favorite_filter_region)) },
                    leadingIcon = {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )

                DropdownMenu(
                    expanded = regionMenuExpanded,
                    onDismissRequest = { regionMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.favorite_filter_all)) },
                        onClick = {
                            onRegionSelected(null)
                            regionMenuExpanded = false
                        },
                    )
                    uiState.availableRegions.forEach { region ->
                        DropdownMenuItem(
                            text = { Text(region) },
                            onClick = {
                                onRegionSelected(region)
                                regionMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // Min Rating Filter Chip
        Box {
            FilterChip(
                selected = uiState.minRating > 0f,
                onClick = { ratingMenuExpanded = true },
                label = {
                    val label = if (uiState.minRating > 0f) "≥ ${uiState.minRating}★" else stringResource(R.string.favorite_filter_rating)
                    Text(label)
                },
                leadingIcon = {
                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )

            DropdownMenu(
                expanded = ratingMenuExpanded,
                onDismissRequest = { ratingMenuExpanded = false },
            ) {
                listOf(0f, 2.0f, 3.0f, 3.5f).forEach { rating ->
                    val text = if (rating == 0f) stringResource(R.string.favorite_filter_all) else "≥ $rating★"
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onMinRatingSelected(rating)
                            ratingMenuExpanded = false
                        },
                    )
                }
            }
        }

        // Reset Filters Button
        if (hasActiveFilters) {
            IconButton(
                onClick = onResetFilters,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.favorite_reset_filters),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FavoriteDismissItem(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
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
