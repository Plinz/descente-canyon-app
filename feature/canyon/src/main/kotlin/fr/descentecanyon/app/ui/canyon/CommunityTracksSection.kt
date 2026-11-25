package fr.descentecanyon.app.ui.canyon

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CommunityTrack
import fr.descentecanyon.app.domain.model.CommunityTracks
import fr.descentecanyon.app.domain.model.TrackRole
import fr.descentecanyon.app.domain.model.TrackStanding
import fr.descentecanyon.app.domain.model.TrackVote
import fr.descentecanyon.app.domain.model.toGpx
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.map.trackColor
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_GPX_BYTES = 2 * 1024 * 1024
private val GPX_MIME_TYPES = arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream", "*/*")
private const val MESSAGE_DURATION_MS = 6_000L

/** Every track in display order (kept ones, then those set aside): its index gives its colour on the page and on the map. */
internal val CommunityTracks.ordered: List<CommunityTrack> get() = visible + discredited

/**
 * GPS tracks shared by the people using the app, ranked by their votes: a section of the Topo tab
 * shaped like the others (folded, with a summary). Shown only when the app's server is on; nothing
 * to moderate: no name, no comment, the votes set bad tracks aside.
 */
@Composable
internal fun CommunityTracksSection(
    canyonName: String,
    onShowMap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: CommunityTracksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.isAvailable) return

    val context = LocalContext.current
    val colors = LocalDcColors.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pendingUpload by remember { mutableStateOf<ByteArray?>(null) }
    var exportTrack by remember { mutableStateOf<CommunityTrack?>(null) }
    var deleteCandidate by remember { mutableStateOf<CommunityTrack?>(null) }
    var showDiscredited by rememberSaveable { mutableStateOf(false) }
    val roleLabels = TrackRole.entries.associateWith { trackRoleLabel(it) }
    val tracks = state.tracks
    val ordered = tracks?.ordered.orEmpty()

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readUpTo(context, uri, MAX_GPX_BYTES + 1) }
            when {
                bytes == null -> viewModel.showMessage(UiText.res(R.string.track_file_unreadable))
                bytes.size > MAX_GPX_BYTES -> viewModel.showMessage(UiText.res(R.string.track_error_too_large))
                else -> pendingUpload = bytes
            }
        }
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val track = exportTrack
        exportTrack = null
        if (uri == null || track == null) return@rememberLauncherForActivityResult
        val gpx = track.toGpx("$canyonName – ${roleLabels.getValue(track.role)}")
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(gpx.toByteArray()) } != null
                }.getOrDefault(false)
            }
            viewModel.showMessage(UiText.res(if (written) R.string.track_exported else R.string.error_storage))
        }
    }

    val firstTracks = mutableListOf<String>()
    tracks?.visible?.take(2)?.forEach { firstTracks += "${roleLabels.getValue(it.role)} ${trackLength(it)}" }
    val preview = when {
        tracks == null && state.isLoading -> stringResource(R.string.tracks_loading)
        tracks == null -> stringResource(R.string.tracks_unavailable)
        tracks.visible.isEmpty() -> stringResource(R.string.tracks_empty)
        else -> firstTracks.joinToString(" · ")
    }

    DcCard(
        modifier = modifier.fillMaxWidth(),
        variant = if (expanded) DcCardVariant.Elevated else DcCardVariant.Surface,
        contentPadding = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tracks_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!expanded) {
                        Text(
                            text = if (ordered.isEmpty()) {
                                preview
                            } else {
                                pluralStringResource(R.plurals.tracks_count, tracks!!.visible.size, tracks.visible.size) +
                                    " · " +
                                    preview
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = colors.borderSubtle)
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.tracks_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        when {
                            tracks == null && state.isLoading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                            tracks == null -> Text(stringResource(R.string.tracks_unavailable), style = MaterialTheme.typography.bodyMedium)

                            tracks.tracks.isEmpty() -> Text(
                                stringResource(R.string.tracks_empty),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        tracks?.visible?.forEach { track ->
                            TrackRow(
                                track = track,
                                colorIndex = ordered.indexOf(track),
                                roleLabel = roleLabels.getValue(track.role),
                                enabled = state.isOnline && track.id !in state.busyTrackIds,
                                onVote = { vote -> viewModel.vote(track, vote) },
                                onExport = {
                                    exportTrack = track
                                    createFile.launch(exportFileName(canyonName, roleLabels.getValue(track.role)))
                                },
                                onDelete = { deleteCandidate = track },
                            )
                        }
                        val discredited = tracks?.discredited.orEmpty()
                        if (discredited.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    pluralStringResource(R.plurals.tracks_discredited, discredited.size, discredited.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { showDiscredited = !showDiscredited }) {
                                    Text(
                                        stringResource(
                                            if (showDiscredited) R.string.tracks_hide_discredited else R.string.tracks_show_discredited,
                                        ),
                                    )
                                }
                            }
                            if (showDiscredited) {
                                discredited.forEach { track ->
                                    TrackRow(
                                        track = track,
                                        colorIndex = ordered.indexOf(track),
                                        roleLabel = roleLabels.getValue(track.role),
                                        enabled = state.isOnline && track.id !in state.busyTrackIds,
                                        onVote = { vote -> viewModel.vote(track, vote) },
                                        onExport = null,
                                        onDelete = { deleteCandidate = track },
                                    )
                                }
                            }
                        }
                        if (tracks != null && !state.isOnline) {
                            val locale = LocalConfiguration.current.locales[0]
                            val readAt = remember(tracks.fetchedAt, locale) {
                                DateTimeFormatter.ofLocalizedDate(
                                    FormatStyle.MEDIUM,
                                ).withLocale(locale).format(tracks.fetchedAt.atZone(ZoneId.systemDefault()))
                            }
                            Text(
                                stringResource(R.string.tracks_read_at, readAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                        state.message?.let { message ->
                            Text(message.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            LaunchedEffect(message) {
                                delay(MESSAGE_DURATION_MS)
                                viewModel.consumeMessage()
                            }
                        }
                        if (state.isUploading) {
                            Text(
                                stringResource(R.string.track_uploading),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (onShowMap != null && tracks?.visible?.isNotEmpty() == true) {
                                TextButton(onClick = onShowMap) {
                                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.show_map_points), modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                            TextButton(onClick = { pickFile.launch(GPX_MIME_TYPES) }, enabled = state.isOnline && !state.isUploading) {
                                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.tracks_add), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        if (!state.isOnline) {
                            Text(
                                stringResource(R.string.tracks_need_network),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingUpload?.let { bytes ->
        UploadTrackDialog(
            roleLabels = roleLabels,
            onDismiss = { pendingUpload = null },
            onConfirm = { role ->
                pendingUpload = null
                viewModel.upload(role, bytes)
            },
        )
    }
    deleteCandidate?.let { track ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.track_delete_title)) },
            text = { Text(stringResource(R.string.track_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    viewModel.delete(track)
                }) { Text(stringResource(R.string.track_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The colour of the track on the map: a dot in front of its name. */
@Composable
internal fun TrackColorDot(colorIndex: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).background(Color(trackColor(colorIndex)), CircleShape))
}

@Composable
private fun TrackRow(
    track: CommunityTrack,
    colorIndex: Int,
    roleLabel: String,
    enabled: Boolean,
    onVote: (TrackVote) -> Unit,
    onExport: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = LocalDcColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TrackColorDot(colorIndex)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(roleLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(trackStandingLabel(track), style = MaterialTheme.typography.labelMedium, color = trackStandingColor(track.standing))
            }
            Text(trackFacts(track), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            if (track.isMine) {
                Text(stringResource(R.string.track_mine), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoteToggle(track = track, vote = TrackVote.UP, enabled = enabled, onVote = onVote)
                    Text("${track.upVotes}", style = MaterialTheme.typography.labelLarge)
                    VoteToggle(
                        track = track,
                        vote = TrackVote.DOWN,
                        enabled = enabled,
                        onVote = onVote,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Text("${track.downVotes}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (onExport != null || track.isMine) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.track_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onExport != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.track_export)) }, onClick = {
                            menuOpen = false
                            onExport()
                        })
                    }
                    if (track.isMine) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.track_delete)) }, enabled = enabled, onClick = {
                            menuOpen = false
                            onDelete()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun VoteToggle(
    track: CommunityTrack,
    vote: TrackVote,
    enabled: Boolean,
    onVote: (TrackVote) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = track.myVote == vote
    val up = vote == TrackVote.UP
    val description = if (up) {
        stringResource(R.string.track_vote_up_description, track.upVotes)
    } else {
        stringResource(R.string.track_vote_down_description, track.downVotes)
    }
    IconToggleButton(
        checked = selected,
        onCheckedChange = { onVote(vote) },
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = when {
                up && selected -> Icons.Filled.ThumbUp
                up -> Icons.Outlined.ThumbUp
                selected -> Icons.Filled.ThumbDown
                else -> Icons.Outlined.ThumbDown
            },
            contentDescription = null,
            tint = when {
                !selected -> LocalDcColors.current.textSecondary
                up -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun UploadTrackDialog(roleLabels: Map<TrackRole, String>, onDismiss: () -> Unit, onConfirm: (TrackRole) -> Unit) {
    var role by rememberSaveable { mutableStateOf(TrackRole.APPROACH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.track_upload_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.track_upload_role), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup()) {
                    TrackRole.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = role == option, role = Role.RadioButton, onClick = { role = option })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = role == option, onClick = null)
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(roleLabels.getValue(option), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    trackRoleHint(option),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalDcColors.current.textSecondary,
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.track_upload_terms),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(role) }) { Text(stringResource(R.string.track_upload_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun trackRoleLabel(role: TrackRole): String = stringResource(
    when (role) {
        TrackRole.APPROACH -> R.string.track_role_approach
        TrackRole.RETURN -> R.string.track_role_return
        TrackRole.ESCAPE -> R.string.track_role_escape
        TrackRole.FULL -> R.string.track_role_full
    },
)

@Composable
private fun trackRoleHint(role: TrackRole): String = stringResource(
    when (role) {
        TrackRole.APPROACH -> R.string.track_role_approach_hint
        TrackRole.RETURN -> R.string.track_role_return_hint
        TrackRole.ESCAPE -> R.string.track_role_escape_hint
        TrackRole.FULL -> R.string.track_role_full_hint
    },
)

@Composable
internal fun trackStandingLabel(track: CommunityTrack): String = stringResource(
    when {
        track.isReference -> R.string.track_standing_reference
        track.standing == TrackStanding.ENDORSED -> R.string.track_standing_endorsed
        track.standing == TrackStanding.DISPUTED -> R.string.track_standing_disputed
        track.standing == TrackStanding.DISCREDITED -> R.string.track_standing_discredited
        else -> R.string.track_standing_new
    },
)

@Composable
internal fun trackStandingColor(standing: TrackStanding): Color = when (standing) {
    TrackStanding.ENDORSED -> MaterialTheme.colorScheme.primary
    TrackStanding.DISPUTED, TrackStanding.DISCREDITED -> MaterialTheme.colorScheme.error
    TrackStanding.NEW -> LocalDcColors.current.textSecondary
}

@Composable
private fun trackLength(track: CommunityTrack): String {
    val locale = LocalConfiguration.current.locales[0]
    return if (track.lengthMeters < 1_000) {
        stringResource(R.string.track_length_m, track.lengthMeters)
    } else {
        stringResource(R.string.track_length_km, String.format(locale, "%.1f", track.lengthMeters / 1_000.0))
    }
}

/** "2,1 km · D+ 195 m · D− 12 m · relevée en juin 2026". */
@Composable
internal fun trackFacts(track: CommunityTrack): String {
    val locale = LocalConfiguration.current.locales[0]
    val parts = buildList {
        add(trackLength(track))
        track.ascentMeters?.takeIf { it > 0 }?.let { add(stringResource(R.string.track_ascent, it)) }
        track.descentMeters?.takeIf { it > 0 }?.let { add(stringResource(R.string.track_descent, it)) }
        track.recordedMonth?.let { month ->
            add(stringResource(R.string.track_recorded, month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))))
        }
    }
    return parts.joinToString(" · ")
}

private fun exportFileName(canyonName: String, roleLabel: String): String =
    "${canyonName}_$roleLabel.gpx".replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")

private fun readUpTo(context: Context, uri: Uri, limit: Int): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (output.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}.getOrNull()
