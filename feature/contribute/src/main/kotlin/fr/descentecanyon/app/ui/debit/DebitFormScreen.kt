package fr.descentecanyon.app.ui.debit

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.ui.auth.AuthViewModel
import fr.descentecanyon.app.ui.auth.LoginDialog
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes
import fr.descentecanyon.app.ui.design.LocalDcSpacing
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DebitFormScreen(
    onBackClick: () -> Unit,
    onSubmissionSuccess: () -> Unit = onBackClick,
    /** Closes the form once the report is stored for later sending. */
    onClose: () -> Unit = onBackClick,
    onOpenPendingDebits: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DebitFormViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showLoginDialog by remember { mutableStateOf(false) }

    if (showLoginDialog) {
        LoginDialog(
            uiState = authUiState,
            onUsernameChanged = authViewModel::onUsernameChanged,
            onPasswordChanged = authViewModel::onPasswordChanged,
            onLogin = authViewModel::login,
            onLogout = authViewModel::logout,
            onDismiss = { showLoginDialog = false },
        )
    }

    LaunchedEffect(showLoginDialog, authUiState.authState) {
        if (showLoginDialog && authUiState.authState is AuthState.Connected) {
            showLoginDialog = false
        }
    }

    LaunchedEffect(uiState.loginRequiredMessage) {
        uiState.loginRequiredMessage?.let { message ->
            authViewModel.showMessage(message)
            showLoginDialog = true
            viewModel.clearLoginRequiredMessage()
        }
    }

    LaunchedEffect(uiState.transientMessage) {
        if (uiState.lastSubmissionStatus != null) return@LaunchedEffect
        uiState.transientMessage?.let {
            snackbarHostState.showSnackbar(it.asString(context))
            viewModel.clearTransientMessage()
        }
    }

    LaunchedEffect(uiState.lastSubmissionStatus) {
        val status = uiState.lastSubmissionStatus ?: return@LaunchedEffect
        // The form closes right away: a toast keeps the confirmation visible on the next screen.
        uiState.transientMessage?.let { message ->
            Toast.makeText(context, message.asString(context), Toast.LENGTH_LONG).show()
            viewModel.clearTransientMessage()
        }
        viewModel.clearLastSubmissionStatus()
        when (status) {
            DebitSubmissionStatus.SUBMITTED, DebitSubmissionStatus.SUBMITTED_UNCONFIRMED -> onSubmissionSuccess()
            DebitSubmissionStatus.QUEUED_OFFLINE -> onClose()
        }
    }

    Scaffold(
        containerColor = LocalDcColors.current.backgroundBase,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.debit_form_title),
                navigation = {
                    androidx.compose.material3.IconButton(onClick = onBackClick) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            DebitSubmitBar(
                isSubmitting = uiState.isSubmitting,
                onSubmit = viewModel::submit,
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        val spacing = LocalDcSpacing.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uiState.pendingCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.debit_pending_count, uiState.pendingCount, uiState.pendingCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TestTags.DEBIT_PENDING_COUNT),
                    )
                    TextButton(onClick = onOpenPendingDebits) {
                        Text(stringResource(R.string.pending_debits_view))
                    }
                }
            }

            if (!uiState.isConnected) {
                LoginSuggestionCard(onLoginClick = { showLoginDialog = true })
                OutlinedTextField(
                    value = uiState.observerName,
                    onValueChange = viewModel::onObserverNameChanged,
                    label = { Text(stringResource(R.string.debit_observer_name)) },
                    supportingText = { Text(stringResource(R.string.debit_observer_name_help)) },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.DEBIT_OBSERVER_NAME_FIELD),
                )
                OutlinedTextField(
                    value = uiState.observerEmail,
                    onValueChange = viewModel::onObserverEmailChanged,
                    label = { Text(stringResource(R.string.debit_observer_email)) },
                    supportingText = { Text(stringResource(R.string.debit_observer_email_help)) },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.DEBIT_OBSERVER_EMAIL_FIELD),
                )
            } else {
                Text(
                    text = stringResource(R.string.debit_connected_as, uiState.observerName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            DatePickerField(
                date = uiState.observationDate,
                onDateSelected = viewModel::onObservationDateSelected,
                showDatePicker = { currentDate, onDateSelected ->
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            onDateSelected(LocalDate.of(year, month + 1, dayOfMonth))
                        },
                        currentDate.year,
                        currentDate.monthValue - 1,
                        currentDate.dayOfMonth,
                    ).apply {
                        datePicker.maxDate = System.currentTimeMillis()
                    }.show()
                },
            )

            FormChoiceSection(
                title = stringResource(R.string.debit_observation_type),
                options = listOf(
                    FormOption(
                        ObservationType.NON_PARCOURU,
                        stringResource(R.string.debit_type_not_descended),
                    ),
                    FormOption(
                        ObservationType.PARCOURU,
                        stringResource(R.string.debit_type_descended),
                    ),
                ),
                selected = uiState.observationType,
                helpText = stringResource(R.string.debit_observation_type_help),
                onSelected = viewModel::onObservationTypeChanged,
            )

            DebitLevelChoiceSection(
                title = stringResource(R.string.debit_level_title),
                options = listOf(
                    FormOption(
                        NiveauDebit.CRUE,
                        stringResource(R.string.debit_level_crue),
                        stringResource(R.string.debit_level_crue_description),
                        debitLevelColor(NiveauDebit.CRUE),
                    ),
                    FormOption(
                        NiveauDebit.TRES_GROS,
                        stringResource(R.string.debit_level_tres_gros),
                        stringResource(R.string.debit_level_tres_gros_description),
                        debitLevelColor(NiveauDebit.TRES_GROS),
                    ),
                    FormOption(
                        NiveauDebit.GROS,
                        stringResource(R.string.debit_level_gros),
                        stringResource(R.string.debit_level_gros_description),
                        debitLevelColor(NiveauDebit.GROS),
                    ),
                    FormOption(
                        NiveauDebit.CORRECT,
                        stringResource(R.string.debit_level_correct),
                        stringResource(R.string.debit_level_correct_description),
                        debitLevelColor(NiveauDebit.CORRECT),
                    ),
                    FormOption(
                        NiveauDebit.FILET,
                        stringResource(R.string.debit_level_filet),
                        stringResource(R.string.debit_level_filet_description),
                        debitLevelColor(NiveauDebit.FILET),
                    ),
                    FormOption(
                        NiveauDebit.SEC,
                        stringResource(R.string.debit_level_sec),
                        stringResource(R.string.debit_level_sec_description),
                        debitLevelColor(NiveauDebit.SEC),
                    ),
                ),
                selected = uiState.debitLevel,
                onSelected = viewModel::onDebitLevelChanged,
            )

            CompactChoiceSection(
                title = stringResource(R.string.debit_water_temperature),
                options = listOf(
                    FormOption(
                        WaterTemperature.CHAUDE,
                        stringResource(R.string.debit_water_chaude),
                        stringResource(R.string.debit_water_chaude_description),
                    ),
                    FormOption(
                        WaterTemperature.DOUCE,
                        stringResource(R.string.debit_water_douce),
                        stringResource(R.string.debit_water_douce_description),
                    ),
                    FormOption(
                        WaterTemperature.FROIDE,
                        stringResource(R.string.debit_water_froide),
                        stringResource(R.string.debit_water_froide_description),
                    ),
                    FormOption(
                        WaterTemperature.TRES_FROIDE,
                        stringResource(R.string.debit_water_tres_froide),
                        stringResource(R.string.debit_water_tres_froide_description),
                    ),
                    FormOption(
                        WaterTemperature.GLACEE,
                        stringResource(R.string.debit_water_glacee),
                        stringResource(R.string.debit_water_glacee_description),
                    ),
                    FormOption(
                        WaterTemperature.INCONNUE,
                        stringResource(R.string.debit_water_inconnue),
                        stringResource(R.string.debit_water_inconnue_description),
                    ),
                ),
                selected = uiState.waterTemperature,
                onSelected = viewModel::onWaterTemperatureChanged,
            )

            CompactChoiceSection(
                title = stringResource(R.string.debit_air_temperature),
                options = listOf(
                    FormOption(
                        AirTemperature.SUPER_CHAUD,
                        stringResource(R.string.debit_air_super_chaud),
                        stringResource(R.string.debit_air_super_chaud_description),
                    ),
                    FormOption(
                        AirTemperature.CHAUD,
                        stringResource(R.string.debit_air_chaud),
                        stringResource(R.string.debit_air_chaud_description),
                    ),
                    FormOption(
                        AirTemperature.BON,
                        stringResource(R.string.debit_air_bon),
                        stringResource(R.string.debit_air_bon_description),
                    ),
                    FormOption(
                        AirTemperature.FRISQUET,
                        stringResource(R.string.debit_air_frisquet),
                        stringResource(R.string.debit_air_frisquet_description),
                    ),
                    FormOption(
                        AirTemperature.FROID,
                        stringResource(R.string.debit_air_froid),
                        stringResource(R.string.debit_air_froid_description),
                    ),
                    FormOption(
                        AirTemperature.INCONNUE,
                        stringResource(R.string.debit_air_inconnue),
                    ),
                ),
                selected = uiState.airTemperature,
                onSelected = viewModel::onAirTemperatureChanged,
            )

            OutlinedTextField(
                value = uiState.comment,
                onValueChange = viewModel::onCommentChanged,
                label = { Text(stringResource(R.string.debit_comment)) },
                supportingText = { Text(stringResource(R.string.debit_comment_help)) },
                modifier = Modifier.fillMaxWidth().testTag(TestTags.DEBIT_COMMENT_FIELD),
                minLines = 4,
            )

            if (uiState.isConnected) {
                OutlinedTextField(
                    value = uiState.personalComment,
                    onValueChange = viewModel::onPersonalCommentChanged,
                    label = { Text(stringResource(R.string.debit_personal_comment)) },
                    supportingText = { Text(stringResource(R.string.debit_personal_comment_help)) },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.DEBIT_PERSONAL_COMMENT_FIELD),
                    minLines = 3,
                )
            }

            uiState.error?.let {
                Text(
                    text = it.asString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}

@Composable
private fun DebitSubmitBar(isSubmitting: Boolean, onSubmit: () -> Unit) {
    val colors = LocalDcColors.current
    Surface(
        color = colors.surfaceOverlay,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Button(
                onClick = onSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.DEBIT_SUBMIT_BUTTON),
                shape = LocalDcShapes.current.xl,
                enabled = !isSubmitting,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                } else {
                    Text(stringResource(R.string.debit_submit))
                }
            }
        }
    }
}

@Composable
private fun DebitLevelChoiceSection(
    title: String,
    options: List<FormOption<NiveauDebit>>,
    selected: NiveauDebit?,
    onSelected: (NiveauDebit) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        options.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowOptions.forEach { option ->
                    DebitLevelChoiceCard(
                        option = option,
                        selected = option.value == selected,
                        onClick = { onSelected(option.value) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowOptions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DebitLevelChoiceCard(option: FormOption<NiveauDebit>, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    val shapes = LocalDcShapes.current
    val accent = option.indicatorColor ?: MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .heightIn(min = 104.dp)
            .clickable(onClick = onClick),
        shape = shapes.lg,
        color = if (selected) accent.copy(alpha = 0.16f) else colors.surfaceBase,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) accent.copy(alpha = 0.72f) else colors.borderSubtle,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = shapes.pill,
                    color = accent,
                    content = {},
                )
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            option.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun <T> CompactChoiceSection(title: String, options: List<FormOption<T>>, selected: T?, onSelected: (T) -> Unit) {
    var showInfoDialog by remember { mutableStateOf(false) }
    val describedOptions = options.filter { !it.description.isNullOrBlank() }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    describedOptions.forEach { option ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = option.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = option.description.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalDcColors.current.textSecondary,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (describedOptions.isNotEmpty()) {
                TextButton(onClick = { showInfoDialog = true }) {
                    Text(stringResource(R.string.debit_choice_info))
                }
            }
        }
        options.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOptions.forEach { option ->
                    CompactChoiceChip(
                        title = option.title,
                        selected = option.value == selected,
                        onClick = { onSelected(option.value) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowOptions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CompactChoiceChip(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    val shapes = LocalDcShapes.current
    Surface(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = shapes.pill,
        color = if (selected) colors.primaryAction.copy(alpha = 0.18f) else colors.surfaceBase,
        contentColor = if (selected) colors.primaryAction else colors.textPrimary,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) colors.primaryAction.copy(alpha = 0.7f) else colors.borderSubtle,
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DatePickerField(
    date: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    showDatePicker: (LocalDate, (LocalDate) -> Unit) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date.toString(),
            onValueChange = {},
            label = { Text(stringResource(R.string.debit_observation_date)) },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .testTag(TestTags.DEBIT_OBSERVATION_DATE_FIELD)
                .clickable { showDatePicker(date, onDateSelected) },
        )
    }
}

@Composable
private fun LoginSuggestionCard(onLoginClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.debit_login_prompt_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.debit_login_prompt_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(onClick = onLoginClick) {
                Text(stringResource(R.string.debit_login_prompt_button))
            }
        }
    }
}

@Composable
private fun <T> FormChoiceSection(
    title: String,
    options: List<FormOption<T>>,
    selected: T?,
    helpText: String? = null,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        options.forEach { option ->
            FormChoiceRow(
                option = option,
                selected = option.value == selected,
                onClick = { onSelected(option.value) },
            )
        }
        helpText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> FormChoiceRow(option: FormOption<T>, selected: Boolean, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colorScheme.primaryContainer else colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) colorScheme.primary else colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
            option.indicatorColor?.let { indicatorColor ->
                Surface(
                    modifier = Modifier.size(16.dp),
                    shape = MaterialTheme.shapes.small,
                    color = indicatorColor,
                    border = BorderStroke(1.dp, colorScheme.outlineVariant),
                    content = {},
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                option.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class FormOption<T>(val value: T, val title: String, val description: String? = null, val indicatorColor: Color? = null)
