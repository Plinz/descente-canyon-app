package fr.descentecanyon.app.ui.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonNote
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.LogbookBilan
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookStats
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Views of the logbook. */
enum class LogbookTab {
    /** Every outing, newest first. */
    OUTINGS,

    /** Every canyon with outings or a memo. */
    CANYONS,

    /** Figures over the years. */
    BILAN,
}

/** A canyon of the logbook or with a memo: what the user knows of it at a glance. */
data class LogbookCanyonRow(val canyonId: Int, val canyonName: String, val outings: Int, val lastOuting: LocalDate?, val memo: String?)

data class LogbookUiState(
    /** Outings matching the search, newest first. */
    val entries: List<LogbookEntry> = emptyList(),
    /** Canyons matching the search, the most recent activity first. */
    val canyons: List<LogbookCanyonRow> = emptyList(),
    val bilan: LogbookBilan? = null,
    val stats: LogbookStats = LogbookStats(0, 0, 0),
    val knownCompanions: List<String> = emptyList(),
    val query: String = "",
    /** Nothing at all in the logbook nor in the memos (not only nothing matching the search). */
    val isEmpty: Boolean = true,
    val isLoading: Boolean = true,
)

@HiltViewModel
class LogbookViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val logbookRepository: LogbookRepository,
    private val canyonNotesRepository: CanyonNotesRepository,
    private val canyonRepository: CanyonRepository,
    private val clock: Clock,
) : ViewModel() {

    val initialTab: LogbookTab = LogbookTab.entries.getOrNull(savedStateHandle.get<Int>("tab") ?: 0) ?: LogbookTab.OUTINGS

    private val query = MutableStateFlow("")

    /** The search as typed, ahead of the filtered lists. */
    val queryText: StateFlow<String> = query.asStateFlow()
    private val summaries = mutableMapOf<Int, CanyonSummary>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<LogbookUiState> = combine(logbookRepository.observeAll(), canyonNotesRepository.observeAll(), query, ::Triple)
        .mapLatest { (entries, notes, text) -> build(entries, notes, text) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogbookUiState())

    fun onQueryChanged(text: String) {
        query.value = text
    }

    fun saveEntry(entry: LogbookEntry) {
        viewModelScope.launch { logbookRepository.save(entry) }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { logbookRepository.delete(id) }
    }

    fun saveMemo(canyonId: Int, text: String) {
        viewModelScope.launch { canyonNotesRepository.saveNote(canyonId, text) }
    }

    private suspend fun build(entries: List<LogbookEntry>, notes: List<CanyonNote>, text: String): LogbookUiState {
        val missing = (entries.map { it.canyonId } + notes.map { it.canyonId }).toSet() - summaries.keys
        if (missing.isNotEmpty()) {
            suspendRunCatching { canyonRepository.getCanyonSummaries(missing) }.getOrNull()?.forEach { summaries[it.id] = it }
        }
        val needle = text.trim().normalizeForSearch()
        val memos = notes.associateBy { it.canyonId }
        val outingsByCanyon = entries.groupBy { it.canyonId }
        val canyonIds = (outingsByCanyon.keys + memos.keys)
        val rows = canyonIds.mapNotNull { id ->
            val outings = outingsByCanyon[id].orEmpty()
            val name = summaries[id]?.nom ?: outings.firstOrNull()?.canyonName ?: return@mapNotNull null
            LogbookCanyonRow(id, name, outings.size, outings.maxOfOrNull { it.date }, memos[id]?.text)
        }.sortedWith(
            compareByDescending<LogbookCanyonRow> { row ->
                // Latest activity: an outing, or the memo edited since.
                maxOf(
                    row.lastOuting?.toEpochDay() ?: Long.MIN_VALUE,
                    memos[row.canyonId]?.updatedAt?.let { it / MILLIS_PER_DAY } ?: Long.MIN_VALUE,
                )
            }.thenBy { it.canyonName },
        )
        return LogbookUiState(
            entries = if (needle.isEmpty()) entries else entries.filter { it.matches(needle) },
            canyons = if (needle.isEmpty()) rows else rows.filter { it.matches(needle) },
            bilan = LogbookBilan.of(entries, summaries, LocalDate.now(clock)),
            stats = LogbookStats.of(entries, LocalDate.now(clock)),
            knownCompanions = suspendRunCatching { logbookRepository.knownCompanions() }.getOrDefault(emptyList()),
            query = text,
            isEmpty = entries.isEmpty() && notes.isEmpty(),
            isLoading = false,
        )
    }

    private fun LogbookEntry.matches(needle: String): Boolean =
        listOfNotNull(canyonName, note, companions).any { it.normalizeForSearch().contains(needle) }

    private fun LogbookCanyonRow.matches(needle: String): Boolean =
        listOfNotNull(canyonName, memo).any { it.normalizeForSearch().contains(needle) }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
