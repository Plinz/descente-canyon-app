package fr.descentecanyon.app.ui.account

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.ui.components.CanyonMemoDialog
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.LogbookEntryDialog
import fr.descentecanyon.app.ui.components.LogbookEntryLines
import fr.descentecanyon.app.ui.components.rememberLogbookDateFormatter
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags

/**
 * The logbook: outings by year, canyons with their memo, and the figures over the years, with one
 * search across canyons, accounts, companions and memos. It is never sent to the site.
 */
@Composable
fun LogbookScreen(
    onBackClick: () -> Unit,
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogbookViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    var tab by rememberSaveable { mutableStateOf(viewModel.initialTab) }
    var editedEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editedMemoCanyonId by rememberSaveable { mutableStateOf<Int?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.logbook_title),
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
            if (!state.isLoading && state.isEmpty) {
                DcEmptyState(
                    title = stringResource(R.string.logbook_empty_title),
                    body = stringResource(R.string.logbook_empty_body),
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    modifier = Modifier.padding(24.dp),
                )
                return@Box
            }
            Column(Modifier.fillMaxHeight().width(contentWidth)) {
                PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
                    LogbookTab.entries.forEach { option ->
                        Tab(
                            selected = tab == option,
                            onClick = { tab = option },
                            text = { Text(stringResource(option.labelRes())) },
                            modifier = Modifier.testTag(option.testTag()),
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(TestTags.LOGBOOK_LIST),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (tab != LogbookTab.BILAN) {
                        item(key = "search") { LogbookSearchField(query = query, onQueryChanged = viewModel::onQueryChanged) }
                    }
                    when (tab) {
                        LogbookTab.OUTINGS -> outingItems(state, query, onCanyonClick, onEdit = { editedEntryId = it.id })

                        LogbookTab.CANYONS -> canyonItems(state, query, onCanyonClick, onEditMemo = { editedMemoCanyonId = it })

                        LogbookTab.BILAN -> item(key = "bilan") {
                            state.bilan?.let { LogbookBilanSection(bilan = it, onCanyonClick = onCanyonClick) }
                        }
                    }
                }
            }
        }
    }

    editedEntryId?.let { id ->
        val entry = state.entries.firstOrNull { it.id == id }
        if (entry != null) {
            LogbookEntryDialog(
                canyonId = entry.canyonId,
                canyonName = entry.canyonName,
                initial = entry,
                knownCompanions = state.knownCompanions,
                onDismiss = { editedEntryId = null },
                onSave = { edited ->
                    viewModel.saveEntry(edited)
                    editedEntryId = null
                },
                onDelete = {
                    viewModel.deleteEntry(entry.id)
                    editedEntryId = null
                },
            )
        }
    }
    editedMemoCanyonId?.let { canyonId ->
        val row = state.canyons.firstOrNull { it.canyonId == canyonId }
        if (row != null) {
            CanyonMemoDialog(
                canyonName = row.canyonName,
                initialText = row.memo.orEmpty(),
                onDismiss = { editedMemoCanyonId = null },
                onSave = { text ->
                    viewModel.saveMemo(canyonId, text)
                    editedMemoCanyonId = null
                },
            )
        }
    }
}

private fun LazyListScope.outingItems(state: LogbookUiState, query: String, onCanyonClick: (Int) -> Unit, onEdit: (LogbookEntry) -> Unit) {
    item(key = "stats") {
        Text(
            listOf(
                pluralStringResource(R.plurals.logbook_stats, state.stats.outings, state.stats.outings),
                pluralStringResource(R.plurals.logbook_stats_canyons, state.stats.canyons, state.stats.canyons),
                pluralStringResource(R.plurals.logbook_stats_year, state.stats.outingsThisYear, state.stats.outingsThisYear),
            ).joinToString(" · "),
            style = MaterialTheme.typography.titleSmall,
            color = LocalDcColors.current.textSecondary,
        )
    }
    if (state.entries.isEmpty()) {
        item(key = "no-outing") { NoMatch(query = query, emptyText = stringResource(R.string.logbook_no_outing)) }
        return
    }
    state.entries.groupBy { it.date.year }.forEach { (year, entries) ->
        item(key = "year-$year") {
            Text(
                year.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(entries, key = { it.id }) { entry ->
            val formatter = rememberLogbookDateFormatter()
            DcCard(onClick = { onCanyonClick(entry.canyonId) }, modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogbookEntryLines(
                        entry = entry,
                        title = entry.canyonName,
                        subtitle = entry.date.format(formatter),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onEdit(entry) }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.logbook_edit_title))
                    }
                }
            }
        }
    }
}

private fun LazyListScope.canyonItems(state: LogbookUiState, query: String, onCanyonClick: (Int) -> Unit, onEditMemo: (Int) -> Unit) {
    if (state.canyons.isEmpty()) {
        item(key = "no-canyon") { NoMatch(query = query, emptyText = stringResource(R.string.logbook_no_outing)) }
        return
    }
    items(state.canyons, key = { "canyon-${it.canyonId}" }) { row ->
        val colors = LocalDcColors.current
        val formatter = rememberLogbookDateFormatter()
        DcCard(onClick = { onCanyonClick(row.canyonId) }, modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.canyonName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (row.outings == 0) {
                            stringResource(R.string.logbook_card_empty)
                        } else {
                            listOfNotNull(
                                pluralStringResource(R.plurals.logbook_stats, row.outings, row.outings),
                                row.lastOuting?.let { stringResource(R.string.logbook_card_last, it.format(formatter)) },
                            ).joinToString(" · ")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    row.memo?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
                IconButton(onClick = { onEditMemo(row.canyonId) }) {
                    Icon(
                        if (row.memo == null) Icons.AutoMirrored.Filled.NoteAdd else Icons.Default.Edit,
                        contentDescription = stringResource(
                            if (row.memo ==
                                null
                            ) {
                                R.string.personal_note_add
                            } else {
                                R.string.personal_note_edit
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun LogbookSearchField(query: String, onQueryChanged: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        placeholder = { Text(stringResource(R.string.logbook_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = {
                    onQueryChanged("")
                }) { Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear_query)) }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGBOOK_SEARCH),
    )
}

@Composable
private fun NoMatch(query: String, emptyText: String) {
    Text(
        text = if (query.isBlank()) emptyText else stringResource(R.string.logbook_search_empty, query.trim()),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalDcColors.current.textSecondary,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

private fun LogbookTab.labelRes(): Int = when (this) {
    LogbookTab.OUTINGS -> R.string.logbook_tab_outings
    LogbookTab.CANYONS -> R.string.logbook_tab_canyons
    LogbookTab.BILAN -> R.string.logbook_tab_bilan
}

private fun LogbookTab.testTag(): String = when (this) {
    LogbookTab.OUTINGS -> TestTags.LOGBOOK_TAB_OUTINGS
    LogbookTab.CANYONS -> TestTags.LOGBOOK_TAB_CANYONS
    LogbookTab.BILAN -> TestTags.LOGBOOK_TAB_BILAN
}
