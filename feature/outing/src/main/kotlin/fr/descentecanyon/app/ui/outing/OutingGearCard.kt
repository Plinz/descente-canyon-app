package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.DaylightCheck
import fr.descentecanyon.app.domain.model.GearGroup
import fr.descentecanyon.app.domain.model.GearItem
import fr.descentecanyon.app.domain.model.GearKey
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Duration

/** The gear groups shown for the outing: those of accompanied beginners with a group that has some. */
private fun OutingPlanUiState.gearGroups(): List<GearGroup> =
    GearGroup.entries.filter { withBeginners || (it != GearGroup.GUESTS_BRING && it != GearGroup.GUESTS_PROVIDED) }

/** The bag of the outing in one line: how much is packed, and headlamps when the day ends late. */
@Composable
internal fun BagCard(state: OutingPlanUiState, onClick: () -> Unit) {
    val colors = LocalDcColors.current
    val items = state.gear.filter { it.group in state.gearGroups() }
    val checked = items.count { it.id in state.choices.checkedGear }
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_BAG), onClick = onClick, contentPadding = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Backpack, contentDescription = null, tint = colors.textSecondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.outing_bag_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.gear_progress, checked, items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                if (needsHeadlamps(state.daylight)) {
                    Text(stringResource(R.string.gear_headlamps_advice), style = MaterialTheme.typography.bodySmall, color = colors.warning)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textMuted)
        }
    }
}

/**
 * The gear of the outing, to check while packing: the user's list (suggested gear they kept and
 * their own items) and what this outing calls for (rope length, shuttle, long day). The lists for
 * accompanied beginners show with a group that has some.
 */
@Composable
internal fun BagDialog(
    state: OutingPlanUiState,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit,
    onHide: (GearKey) -> Unit,
    onRemoveCustom: (String) -> Unit,
    onAdd: (GearGroup, String) -> Unit,
    onRestore: () -> Unit,
) {
    val colors = LocalDcColors.current
    val resources = LocalResources.current
    var editing by rememberSaveable { mutableStateOf(false) }
    val groups = state.gearGroups()
    val items = state.gear.filter { it.group in groups }
    val checked = items.count { it.id in state.choices.checkedGear }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                CompactAppBar(
                    title = stringResource(R.string.outing_bag_title),
                    navigation = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        TextButton(onClick = { editing = !editing }) {
                            Text(stringResource(if (editing) R.string.gear_edit_done else R.string.gear_edit))
                        }
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding().testTag(TestTags.OUTING_GEAR),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "progress") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.gear_progress, checked, items.size), style = MaterialTheme.typography.bodyMedium)
                        if (needsHeadlamps(state.daylight)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = colors.warning,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(stringResource(R.string.gear_headlamps_advice), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                groups.forEach { group ->
                    val groupItems = items.filter { it.group == group }
                    if (groupItems.isEmpty() && !editing) return@forEach
                    item(key = "group-${group.name}") {
                        Text(
                            gearGroupLabel(group),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(groupItems, key = { it.id }) { item ->
                        GearRow(
                            label = gearLabel(resources, item),
                            checked = item.id in state.choices.checkedGear,
                            onToggle = { onToggle(item.id) },
                            onRemove = when {
                                !editing -> null
                                item is GearItem.Suggested -> ({ onHide(item.key) })
                                item is GearItem.Custom -> ({ onRemoveCustom(item.id) })
                                else -> null
                            },
                        )
                    }
                }
                if (editing) {
                    item(key = "add") { AddGearRow(groups = groups, onAdd = onAdd) }
                    if (state.gearList.hidden.isNotEmpty()) {
                        item(key = "restore") { TextButton(onClick = onRestore) { Text(stringResource(R.string.gear_restore)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun GearRow(label: String, checked: Boolean, onToggle: () -> Unit, onRemove: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.gear_remove, label),
                    tint = LocalDcColors.current.textMuted,
                )
            }
        }
    }
}

@Composable
private fun AddGearRow(groups: List<GearGroup>, onAdd: (GearGroup, String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(GearGroup.PERSONAL) }
    var menu by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        Box {
            TextButton(onClick = { menu = true }) { Text(stringResource(R.string.gear_add_to, gearGroupLabel(group))) }
            DcDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                groups.forEach { option ->
                    DcDropdownMenuItem(
                        text = gearGroupLabel(option),
                        isSelected = option == group,
                        onClick = {
                            group = option
                            menu = false
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_GEAR_LABEL) },
                label = { Text(stringResource(R.string.gear_add_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    onAdd(group, text)
                    text = ""
                },
                enabled = text.isNotBlank(),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.gear_add))
            }
        }
    }
}

@Composable
private fun gearGroupLabel(group: GearGroup): String = stringResource(
    when (group) {
        GearGroup.PERSONAL -> R.string.gear_group_personal
        GearGroup.SHARED -> R.string.gear_group_shared
        GearGroup.GUESTS_BRING -> R.string.gear_group_guests_bring
        GearGroup.GUESTS_PROVIDED -> R.string.gear_group_guests_provided
    },
)

/** Little daylight left at the return, even late: headlamps are worth checking. */
private fun needsHeadlamps(daylight: DaylightCheck): Boolean = when (daylight) {
    is DaylightCheck.Late -> true
    is DaylightCheck.Ok -> daylight.margin < HEADLAMP_MARGIN
    DaylightCheck.Unknown -> false
}

private val HEADLAMP_MARGIN: Duration = Duration.ofMinutes(90)
private const val MAX_GEAR_LABEL = 60
