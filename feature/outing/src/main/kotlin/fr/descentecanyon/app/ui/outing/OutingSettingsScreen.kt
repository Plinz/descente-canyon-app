package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.model.PersonalPace
import fr.descentecanyon.app.domain.repository.OutingPreferencesRepository
import fr.descentecanyon.app.domain.usecase.GetPersonalPaceUseCase
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OutingSettingsUiState(val settings: OutingSettings = OutingSettings.DEFAULT, val personalPace: PersonalPace? = null)

/** The coefficients and times that turn the sheet times into those of the user's group. */
@HiltViewModel
class OutingSettingsViewModel @Inject constructor(
    private val outingPreferencesRepository: OutingPreferencesRepository,
    getPersonalPaceUseCase: GetPersonalPaceUseCase,
) : ViewModel() {

    private val personalPace = MutableStateFlow<PersonalPace?>(null)

    val uiState: StateFlow<OutingSettingsUiState> = combine(outingPreferencesRepository.observeSettings(), personalPace) { settings, pace ->
        OutingSettingsUiState(settings, pace)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OutingSettingsUiState())

    init {
        viewModelScope.launch { personalPace.value = getPersonalPaceUseCase() }
    }

    fun update(change: (OutingSettings) -> OutingSettings) {
        val updated = change(uiState.value.settings).sanitized()
        viewModelScope.launch { outingPreferencesRepository.setSettings(updated) }
    }

    fun reset() {
        viewModelScope.launch { outingPreferencesRepository.setSettings(OutingSettings.DEFAULT) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutingSettingsScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier, viewModel: OutingSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    val locale = LocalLocale.current.platformLocale
    fun factor(value: Double) = formatFactor(value, locale)
    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.outing_settings_title),
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
            LazyColumn(
                modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.OUTING_SETTINGS_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.outing_settings_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalDcColors.current.textSecondary,
                    )
                }
                item {
                    Section(stringResource(R.string.outing_settings_default_group)) {
                        Stepper(
                            title = stringResource(R.string.outing_group_title),
                            label = pluralStringResource(R.plurals.outing_group_size, settings.defaultGroupSize, settings.defaultGroupSize),
                            onMinus = { viewModel.update { it.copy(defaultGroupSize = it.defaultGroupSize - 1) } },
                            onPlus = { viewModel.update { it.copy(defaultGroupSize = it.defaultGroupSize + 1) } },
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GroupLevel.entries.forEach { level ->
                                FilterChip(
                                    selected = settings.defaultGroupLevel == level,
                                    onClick = { viewModel.update { it.copy(defaultGroupLevel = level) } },
                                    label = { Text(groupLevelLabel(level)) },
                                )
                            }
                        }
                    }
                }
                item {
                    Section(stringResource(R.string.outing_settings_parking)) {
                        Stepper(
                            title = stringResource(R.string.outing_settings_prep),
                            label = formatMinutes(settings.prepMinutes.toLong()),
                            onMinus = { viewModel.update { it.copy(prepMinutes = it.prepMinutes - MINUTES_STEP) } },
                            onPlus = { viewModel.update { it.copy(prepMinutes = it.prepMinutes + MINUTES_STEP) } },
                        )
                        Stepper(
                            title = stringResource(R.string.outing_settings_pack_up),
                            label = formatMinutes(settings.packUpMinutes.toLong()),
                            onMinus = { viewModel.update { it.copy(packUpMinutes = it.packUpMinutes - MINUTES_STEP) } },
                            onPlus = { viewModel.update { it.copy(packUpMinutes = it.packUpMinutes + MINUTES_STEP) } },
                        )
                        Stepper(
                            title = stringResource(R.string.outing_settings_shuttle),
                            label = formatMinutes(settings.shuttleHandlingMinutes.toLong()),
                            onMinus = { viewModel.update { it.copy(shuttleHandlingMinutes = it.shuttleHandlingMinutes - MINUTES_STEP) } },
                            onPlus = { viewModel.update { it.copy(shuttleHandlingMinutes = it.shuttleHandlingMinutes + MINUTES_STEP) } },
                        )
                    }
                }
                item {
                    Section(stringResource(R.string.outing_settings_group_size), stringResource(R.string.outing_settings_group_size_hint)) {
                        Stepper(
                            title = stringResource(R.string.outing_settings_group_5_6),
                            label = factor(settings.mediumGroupFactor),
                            onMinus = { viewModel.update { it.copy(mediumGroupFactor = it.mediumGroupFactor - FACTOR_STEP) } },
                            onPlus = { viewModel.update { it.copy(mediumGroupFactor = it.mediumGroupFactor + FACTOR_STEP) } },
                        )
                        Stepper(
                            title = stringResource(R.string.outing_settings_group_7_8),
                            label = factor(settings.largeGroupFactor),
                            onMinus = { viewModel.update { it.copy(largeGroupFactor = it.largeGroupFactor - FACTOR_STEP) } },
                            onPlus = { viewModel.update { it.copy(largeGroupFactor = it.largeGroupFactor + FACTOR_STEP) } },
                        )
                        Stepper(
                            title = stringResource(R.string.outing_settings_group_9),
                            label = factor(settings.hugeGroupFactor),
                            onMinus = { viewModel.update { it.copy(hugeGroupFactor = it.hugeGroupFactor - FACTOR_STEP) } },
                            onPlus = { viewModel.update { it.copy(hugeGroupFactor = it.hugeGroupFactor + FACTOR_STEP) } },
                        )
                    }
                }
                item {
                    Section(stringResource(R.string.outing_settings_level), stringResource(R.string.outing_settings_level_hint)) {
                        Stepper(
                            title = stringResource(R.string.outing_level_beginners),
                            label = factor(settings.beginnersFactor),
                            onMinus = { viewModel.update { it.copy(beginnersFactor = it.beginnersFactor - FACTOR_STEP) } },
                            onPlus = { viewModel.update { it.copy(beginnersFactor = it.beginnersFactor + FACTOR_STEP) } },
                        )
                        Stepper(
                            title = stringResource(R.string.outing_level_discovery),
                            label = factor(settings.discoveryFactor),
                            onMinus = { viewModel.update { it.copy(discoveryFactor = it.discoveryFactor - FACTOR_STEP) } },
                            onPlus = { viewModel.update { it.copy(discoveryFactor = it.discoveryFactor + FACTOR_STEP) } },
                        )
                    }
                }
                item {
                    Section(stringResource(R.string.outing_settings_pace)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.outing_settings_pace_use),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = settings.usePersonalPace,
                                onCheckedChange = { checked -> viewModel.update { it.copy(usePersonalPace = checked) } },
                            )
                        }
                        val pace = state.personalPace
                        Text(
                            text = if (pace != null) {
                                pluralStringResource(R.plurals.outing_settings_pace_value, pace.outings, factor(pace.ratio), pace.outings)
                            } else {
                                stringResource(R.string.outing_settings_pace_none, PersonalPace.MIN_OUTINGS)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalDcColors.current.textSecondary,
                        )
                    }
                }
                item {
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.outing_settings_reset))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textSecondary) }
        DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 8.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 8.dp), content = content)
        }
    }
}

/** A value changed by steps, with the setting it is for. */
@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit, title: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title ?: "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) {
            Icon(
                Icons.Default.Remove,
                contentDescription = stringResource(
                    R.string.outing_settings_less,
                    title ?: label,
                ),
            )
        }
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onPlus) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(
                    R.string.outing_settings_more,
                    title ?: label,
                ),
            )
        }
    }
}

private const val MINUTES_STEP = 5
private const val FACTOR_STEP = OutingSettings.FACTOR_STEP
