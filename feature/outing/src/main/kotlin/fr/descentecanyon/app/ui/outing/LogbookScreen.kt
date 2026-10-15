package fr.descentecanyon.app.ui.outing

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookStats
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LogbookUiState(
    val entries: List<LogbookEntry> = emptyList(),
    val stats: LogbookStats = LogbookStats(0, 0, 0),
    val isLoading: Boolean = true,
)

@HiltViewModel
class LogbookViewModel @Inject constructor(private val logbookRepository: LogbookRepository, private val clock: Clock) : ViewModel() {
    val uiState: StateFlow<LogbookUiState> = logbookRepository.observeAll()
        .map { entries -> LogbookUiState(entries, LogbookStats.of(entries, LocalDate.now(clock)), isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogbookUiState())

    fun delete(id: Long) {
        viewModelScope.launch { logbookRepository.delete(id) }
    }
}

/** The user's logbook, grouped by year, newest first; it never leaves the phone. */
@Composable
fun LogbookScreen(
    onBackClick: () -> Unit,
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogbookViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    var pendingDelete by remember { mutableStateOf<LogbookEntry?>(null) }
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale)

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
            if (!state.isLoading && state.entries.isEmpty()) {
                DcEmptyState(
                    title = stringResource(R.string.logbook_empty_title),
                    body = stringResource(R.string.logbook_empty_body),
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    modifier = Modifier.padding(24.dp),
                )
                return@Box
            }
            LazyColumn(
                modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.LOGBOOK_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
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
                        DcCard(onClick = { onCanyonClick(entry.canyonId) }, modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.canyonName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        entry.date.format(dateFormatter),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LocalDcColors.current.textSecondary,
                                    )
                                    entry.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                                IconButton(onClick = { pendingDelete = entry }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.logbook_delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.logbook_delete)) },
            text = { Text("${entry.canyonName} · ${entry.date.format(dateFormatter)}") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(entry.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.logbook_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
