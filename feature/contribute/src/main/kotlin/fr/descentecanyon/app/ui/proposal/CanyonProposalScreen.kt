package fr.descentecanyon.app.ui.proposal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonSheet
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NearbyCanyon
import fr.descentecanyon.app.domain.model.SheetPoint
import fr.descentecanyon.app.domain.model.SheetProblem
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.DcSelectField
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import fr.descentecanyon.app.ui.test.TestTags

/** The point being placed: its index in the sheet (null for a new one) and where the picker starts. */
private data class PointEdit(val index: Int?, val point: SheetPoint)

/**
 * A new canyon, or a change to a canyon added in the app, written as a sheet of the site and sent
 * for review: nothing is published before the operator of the app's server approves it.
 */
@Composable
fun CanyonProposalScreen(
    onClose: () -> Unit,
    onOpenCanyon: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CanyonProposalViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    val horizontalPadding = rememberDcScreenHorizontalPadding()
    var editing by remember { mutableStateOf<PointEdit?>(null) }
    var confirmClose by rememberSaveable { mutableStateOf(false) }

    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.messageShown()
        }
    }
    val hasContent = state.draft != (state.original ?: SheetDraft())
    val close = { if (hasContent && state.sent == null) confirmClose = true else onClose() }

    if (confirmClose) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(R.string.proposal_discard_title)) },
            text = { Text(stringResource(R.string.proposal_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    onClose()
                }) { Text(stringResource(R.string.proposal_discard_action), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    editing?.let { edit ->
        val others = state.draft.points.filterIndexed { index, _ -> index != edit.index }
        SheetPointPicker(
            initial = edit.point,
            isNew = edit.index == null && state.draft.points.isEmpty(),
            otherMarkers = others.mapIndexed { index, point -> point.toMarker(-1 - index) } + state.nearby.map { it.summary },
            onSave = { point ->
                viewModel.savePoint(edit.index, point)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = stringResource(if (state.isEdit) R.string.proposal_title_edit else R.string.proposal_title_new),
                navigation = {
                    IconButton(onClick = close) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close)) }
                },
                actions = {
                    if (state.sent == null && state.isAvailable && !state.isLoading && state.loadError == null) {
                        TextButton(onClick = viewModel::send, enabled = !state.isSending) { Text(stringResource(R.string.proposal_send)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = LocalDcColors.current.backgroundBase,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).imePadding(), contentAlignment = Alignment.TopCenter) {
            when {
                !state.isAvailable -> DcEmptyState(title = stringResource(R.string.proposal_unavailable))

                state.isLoading -> CircularProgressIndicator(modifier = Modifier.padding(32.dp))

                state.loadError != null -> DcEmptyState(title = state.loadError!!.asString())

                state.sent != null -> SentContent(isEdit = state.isEdit, onClose = onClose)

                else -> ProposalForm(
                    state = state,
                    viewModel = viewModel,
                    onAddPoint = { type -> editing = PointEdit(null, newPoint(type, state.draft)) },
                    onEditPoint = { index -> editing = PointEdit(index, state.draft.points[index]) },
                    onOpenCanyon = onOpenCanyon,
                    modifier = Modifier.width(contentWidth),
                    horizontalPadding = horizontalPadding,
                )
            }
        }
    }
}

/** A new point starts at the point that places the canyon, else at the middle of the Alps. */
private fun newPoint(type: GeoPointType, draft: SheetDraft): SheetPoint {
    val anchor = draft.anchor
    return SheetPoint(type, anchor?.latitude ?: DEFAULT_LATITUDE, anchor?.longitude ?: DEFAULT_LONGITUDE)
}

private fun SheetPoint.toMarker(id: Int) = CanyonSummary(
    id = id,
    nom = title,
    pays = "",
    cotation = "",
    url = "",
    latitude = latitude,
    longitude = longitude,
    markerType = type,
)

@Composable
private fun ProposalForm(
    state: CanyonProposalUiState,
    viewModel: CanyonProposalViewModel,
    onAddPoint: (GeoPointType) -> Unit,
    onEditPoint: (Int) -> Unit,
    onOpenCanyon: (Int) -> Unit,
    modifier: Modifier,
    horizontalPadding: androidx.compose.ui.unit.Dp,
) {
    val draft = state.draft
    val problems = if (state.showProblems) state.problems else emptySet()
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(TestTags.PROPOSAL_LIST),
        contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "intro") {
            Text(
                stringResource(if (state.isEdit) R.string.proposal_intro_edit else R.string.proposal_intro_new),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalDcColors.current.textSecondary,
            )
        }
        item(key = "position") {
            Section(stringResource(R.string.proposal_section_position)) {
                draft.points.forEachIndexed { index, point ->
                    PointRow(point = point, onEdit = { onEditPoint(index) }, onRemove = { viewModel.removePoint(index) })
                }
                if (draft.points.isEmpty()) {
                    HintText(stringResource(R.string.proposal_point_missing), isError = SheetProblem.POINT in problems)
                }
                if (SheetProblem.POINTS_TOO_FAR_APART in problems) ErrorText(stringResource(R.string.proposal_point_too_far))
                AddPointButtons(draft = draft, onAddPoint = onAddPoint)
            }
        }
        if (state.nearby.isNotEmpty()) {
            item(key = "nearby") { NearbyCard(nearby = state.nearby, onOpenCanyon = onOpenCanyon) }
        }
        item(key = "identity") {
            Section(stringResource(R.string.proposal_section_identity)) {
                SheetTextField(
                    draft,
                    SheetField.NOM,
                    R.string.proposal_field_nom,
                    viewModel,
                    error = SheetProblem.NAME in problems,
                    required = true,
                )
                SheetTextField(draft, SheetField.NOM_COMPLET, R.string.proposal_field_nom_complet, viewModel)
                SheetTextField(draft, SheetField.COURS_EAU, R.string.proposal_field_cours_eau, viewModel)
                HintText(stringResource(R.string.proposal_place_hint))
                SheetTextField(draft, SheetField.PAYS, R.string.proposal_field_pays, viewModel)
                SheetTextField(draft, SheetField.REGION, R.string.proposal_field_region, viewModel)
                SheetTextField(draft, SheetField.DEPARTEMENT, R.string.proposal_field_departement, viewModel)
                SheetTextField(draft, SheetField.COMMUNE, R.string.proposal_field_commune, viewModel)
                SheetTextField(draft, SheetField.MASSIF, R.string.proposal_field_massif, viewModel)
                SheetTextField(draft, SheetField.BASSIN, R.string.proposal_field_bassin, viewModel)
            }
        }
        item(key = "features") {
            Section(stringResource(R.string.proposal_section_features)) {
                CotationFields(draft = draft, onChange = viewModel::setCotation)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(draft, SheetField.ALTITUDE_DEPART, R.string.proposal_field_altitude, viewModel, Modifier.weight(1f))
                    NumberField(draft, SheetField.DENIVELE, R.string.proposal_field_denivele, viewModel, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(draft, SheetField.LONGUEUR, R.string.proposal_field_longueur, viewModel, Modifier.weight(1f))
                    NumberField(draft, SheetField.CASCADE_MAX, R.string.proposal_field_cascade, viewModel, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(draft, SheetField.CORDE_MIN, R.string.proposal_field_corde, viewModel, Modifier.weight(1f))
                    ShuttleField(draft, viewModel, Modifier.weight(1f))
                }
                Text(stringResource(R.string.proposal_durations), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DurationField(draft, SheetField.TEMPS_APPROCHE, R.string.proposal_field_temps_approche, viewModel, Modifier.weight(1f))
                    DurationField(draft, SheetField.TEMPS_DESCENTE, R.string.proposal_field_temps_descente, viewModel, Modifier.weight(1f))
                    DurationField(draft, SheetField.TEMPS_RETOUR, R.string.proposal_field_temps_retour, viewModel, Modifier.weight(1f))
                }
                Text(
                    stringResource(R.string.proposal_duration_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                )
            }
        }
        item(key = "topo") {
            Section(stringResource(R.string.proposal_section_topo)) {
                HintText(stringResource(R.string.proposal_text_missing), isError = SheetProblem.DESCRIPTION in problems)
                SheetTextField(draft, SheetField.ACCES_AVAL, R.string.proposal_field_acces_aval, viewModel)
                SheetTextField(draft, SheetField.ACCES_AMONT, R.string.proposal_field_acces_amont, viewModel)
                SheetTextField(draft, SheetField.APPROCHE, R.string.proposal_field_approche, viewModel)
                SheetTextField(draft, SheetField.DESCENTE, R.string.proposal_field_descente, viewModel, minLines = 5)
                SheetTextField(draft, SheetField.RETOUR, R.string.proposal_field_retour, viewModel)
                SheetTextField(draft, SheetField.ENGAGEMENT, R.string.proposal_field_engagement, viewModel)
                SheetTextField(draft, SheetField.PERIODE, R.string.proposal_field_periode, viewModel)
                SheetTextField(draft, SheetField.GEOLOGIE, R.string.proposal_field_geologie, viewModel)
                SheetTextField(draft, SheetField.HISTORIQUE, R.string.proposal_field_historique, viewModel)
                SheetTextField(draft, SheetField.REMARQUES, R.string.proposal_field_remarques, viewModel)
                CheckRow(
                    checked = draft.isForbidden,
                    onChecked = viewModel::setForbidden,
                    text = stringResource(R.string.proposal_field_forbidden),
                )
            }
        }
        item(key = "licence") {
            DcCard(
                modifier = Modifier.fillMaxWidth(),
                variant = if (state.showProblems &&
                    !state.licenceAccepted
                ) {
                    DcCardVariant.Warning
                } else {
                    DcCardVariant.Surface
                },
            ) {
                CheckRow(
                    checked = state.licenceAccepted,
                    onChecked = viewModel::acceptLicence,
                    text = stringResource(R.string.proposal_licence),
                    modifier = Modifier.testTag(TestTags.PROPOSAL_LICENCE),
                )
                Text(
                    stringResource(R.string.proposal_review_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item(key = "send") {
            Button(
                onClick = viewModel::send,
                enabled = !state.isSending,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag(TestTags.PROPOSAL_SEND),
            ) {
                if (state.isSending) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(if (state.isEdit) R.string.proposal_send_edit else R.string.proposal_send_new))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** What a part of the form needs, in red once the user tried to send without it. */
@Composable
private fun HintText(text: String, isError: Boolean = false) {
    if (isError) {
        ErrorText(text)
    } else {
        Text(text, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textSecondary)
    }
}

@Composable
private fun PointRow(point: SheetPoint, onEdit: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                pointTypeLabel(point.type) + point.title.takeIf {
                    it.isNotBlank()
                }?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                coordinatesText(point.latitude, point.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = LocalDcColors.current.textSecondary,
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.proposal_point_edit)) }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.proposal_point_remove))
        }
    }
}

@Composable
private fun AddPointButtons(draft: SheetDraft, onAddPoint: (GeoPointType) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val types = draft.points.map { it.type }.toSet()
    val first = when {
        GeoPointType.ENTREE !in types -> GeoPointType.ENTREE
        GeoPointType.SORTIE !in types -> GeoPointType.SORTIE
        else -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (first != null) {
            OutlinedButton(onClick = { onAddPoint(first) }, modifier = Modifier.testTag(TestTags.PROPOSAL_ADD_POINT)) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.proposal_point_place, pointTypeLabel(first)))
            }
        }
        if (draft.points.size < CanyonSheet.MAX_POINTS) {
            Box {
                TextButton(onClick = { menu = true }) { Text(stringResource(R.string.proposal_point_add)) }
                DcDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SHEET_POINT_TYPES.forEach { type ->
                        DcDropdownMenuItem(text = pointTypeLabel(type), onClick = {
                            menu = false
                            onAddPoint(type)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyCard(nearby: List<NearbyCanyon>, onOpenCanyon: (Int) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    DcCard(modifier = Modifier.fillMaxWidth(), variant = DcCardVariant.Warning) {
        Text(stringResource(R.string.proposal_nearby_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.proposal_nearby_body), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        nearby.take(MAX_NEARBY_SHOWN).forEach { canyon ->
            Text(
                stringResource(
                    R.string.proposal_nearby_item,
                    canyon.summary.nom,
                    String.format(locale, "%.1f", canyon.distanceKm),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { onOpenCanyon(canyon.summary.id) }.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SheetTextField(
    draft: SheetDraft,
    field: SheetField,
    label: Int,
    viewModel: CanyonProposalViewModel,
    error: Boolean = false,
    required: Boolean = false,
    minLines: Int = if (field.isText) 2 else 1,
) {
    val value = draft[field]
    val tooLong = field.isText && value.length > CanyonSheet.MAX_TEXT_LENGTH
    val support = when {
        error -> stringResource(R.string.proposal_required)
        tooLong -> stringResource(R.string.proposal_error_text_too_long)
        else -> null
    }
    OutlinedTextField(
        value = value,
        onValueChange = { viewModel.update(field, it.take(if (field.isText) CanyonSheet.MAX_TEXT_LENGTH + 1 else SHORT_MAX)) },
        label = { Text(stringResource(label) + if (required) " *" else "") },
        isError = error || tooLong,
        supportingText = support?.let { text -> { Text(text) } },
        singleLine = !field.isText,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(draft: SheetDraft, field: SheetField, label: Int, viewModel: CanyonProposalViewModel, modifier: Modifier) {
    val value = draft[field]
    val invalid = value.isNotBlank() && value.trim().toIntOrNull() == null
    OutlinedTextField(
        value = value,
        onValueChange = { viewModel.update(field, it.filter { char -> char.isDigit() }.take(NUMBER_MAX)) },
        label = { Text(stringResource(label)) },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ShuttleField(draft: SheetDraft, viewModel: CanyonProposalViewModel, modifier: Modifier) {
    val value = draft[SheetField.NAVETTE_KM]
    val invalid = value.isNotBlank() && CanyonSheet.shuttleOf(value).isEmpty()
    OutlinedTextField(
        value = value,
        onValueChange = {
            viewModel.update(SheetField.NAVETTE_KM, it.filter { char -> char.isDigit() || char == '.' || char == ',' }.take(NUMBER_MAX))
        },
        label = { Text(stringResource(R.string.proposal_field_navette)) },
        supportingText = { Text(stringResource(R.string.proposal_field_navette_hint)) },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun DurationField(draft: SheetDraft, field: SheetField, label: Int, viewModel: CanyonProposalViewModel, modifier: Modifier) {
    val value = draft[field]
    OutlinedTextField(
        value = value,
        onValueChange = { viewModel.update(field, it.take(DURATION_MAX)) },
        label = { Text(stringResource(label)) },
        isError = !CanyonSheet.isDuration(value),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun CotationFields(draft: SheetDraft, onChange: (Int?, Int?, Int?) -> Unit) {
    Text(stringResource(R.string.proposal_field_cotation), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CotationPicker(
            label = stringResource(R.string.proposal_cotation_vertical),
            value = draft.vertical?.let { "v$it" },
            options = (1..7).map { it to "v$it" },
            onSelect = { onChange(it, draft.aquatic, draft.engagement) },
            modifier = Modifier.weight(1f),
        )
        CotationPicker(
            label = stringResource(R.string.proposal_cotation_aquatic),
            value = draft.aquatic?.let { "a$it" },
            options = (1..7).map { it to "a$it" },
            onSelect = { onChange(draft.vertical, it, draft.engagement) },
            modifier = Modifier.weight(1f),
        )
        CotationPicker(
            label = stringResource(R.string.proposal_cotation_engagement),
            value = draft.engagement?.let { CanyonSheet.ROMAN[it - 1] },
            options = (1..6).map { it to CanyonSheet.ROMAN[it - 1] },
            onSelect = { onChange(draft.vertical, draft.aquatic, it) },
            modifier = Modifier.weight(1f),
        )
    }
    val partial = listOf(draft.vertical, draft.aquatic, draft.engagement).count { it != null } in 1..2
    if (partial) {
        Text(
            stringResource(R.string.proposal_cotation_partial),
            style = MaterialTheme.typography.bodySmall,
            color = LocalDcColors.current.textSecondary,
        )
    }
}

@Composable
private fun CotationPicker(label: String, value: String?, options: List<Pair<Int, String>>, onSelect: (Int?) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        DcSelectField(
            label = label,
            value = value ?: "—",
            onClick = { expanded = true },
            isActive = value != null,
            onClear = { onSelect(null) },
        )
        DcDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (number, text) ->
                DcDropdownMenuItem(text = text, isSelected = text == value, onClick = {
                    expanded = false
                    onSelect(number)
                })
            }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, onChecked: (Boolean) -> Unit, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clickable { onChecked(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SentContent(isEdit: Boolean, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(8.dp),
        )
        Text(stringResource(R.string.proposal_sent_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(if (isEdit) R.string.proposal_sent_body_edit else R.string.proposal_sent_body_new),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalDcColors.current.textSecondary,
        )
        Button(onClick = onClose) { Text(stringResource(R.string.close)) }
    }
}

private const val DEFAULT_LATITUDE = 45.0
private const val DEFAULT_LONGITUDE = 6.0
private const val SHORT_MAX = 120
private const val NUMBER_MAX = 6
private const val DURATION_MAX = 10
private const val MAX_NEARBY_SHOWN = 6
