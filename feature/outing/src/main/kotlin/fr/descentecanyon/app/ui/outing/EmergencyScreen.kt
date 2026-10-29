package fr.descentecanyon.app.ui.outing

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CompassDirection
import fr.descentecanyon.app.domain.model.EmergencyNumber
import fr.descentecanyon.app.domain.model.EmergencyNumbers
import fr.descentecanyon.app.domain.model.EmergencyService
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointSource
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.location.DeviceFix
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.location.hasPreciseLocationPermission
import fr.descentecanyon.app.ui.location.observeDeviceFixes
import fr.descentecanyon.app.ui.location.requestLocationSettings
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What to do in an emergency, offline and in the order of a call to the rescuers: which canyon,
 * the numbers, the position to give or to send, the points of the canyon around and what to say.
 */
@Composable
fun EmergencyScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier, viewModel: EmergencyViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    var hasPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    var hasPrecisePermission by remember { mutableStateOf(context.hasPreciseLocationPermission()) }
    var isPositionUnavailable by remember { mutableStateOf(false) }
    var isDeviceLocked by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermission = context.hasLocationPermission()
        hasPrecisePermission = context.hasPreciseLocationPermission()
        if (!hasPermission) viewModel.onPositionUnavailable()
    }

    val locationSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

    LaunchedEffect(Unit) {
        viewModel.setNetworkCountry(context.getSystemService(TelephonyManager::class.java)?.networkCountryIso)
        if (!hasPermission) viewModel.onPositionUnavailable()
    }
    // With the location turned off, no position would ever come: offer to turn it on right away.
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            requestLocationSettings(context, onEnabled = {}, onResolutionRequired = locationSettingsLauncher::launch, onUnavailable = {})
        }
    }
    LifecycleResumeEffect(Unit) {
        isDeviceLocked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        onPauseOrDispose {}
    }
    // The coordinates are read aloud on the phone: the screen must not go dark meanwhile.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Restarted when the precise location is granted: the fixes of the running request stay approximate.
    DisposableEffect(hasPermission, hasPrecisePermission) {
        if (!hasPermission) {
            onDispose {}
        } else {
            val stop = observeDeviceFixes(
                context,
                onFix = {
                    isPositionUnavailable = false
                    viewModel.onPosition(it)
                },
                onUnavailable = {
                    isPositionUnavailable = true
                    viewModel.onPositionUnavailable()
                },
            )
            onDispose(stop)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.emergency_title),
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
                modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.EMERGENCY_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { CanyonCard(state, onSelectCanyon = viewModel::selectCanyon) }
                item { CallCard(state, isDeviceLocked = isDeviceLocked) }
                item {
                    PositionCard(
                        state = state,
                        hasPermission = hasPermission,
                        hasPrecisePermission = hasPrecisePermission,
                        isUnavailable = isPositionUnavailable,
                        // With the approximate location granted, Android offers to switch to the precise one.
                        onRequestPermission = {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        },
                    )
                }
                if (state.points.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(
                                if (state.position != null) R.string.emergency_points_by_distance else R.string.emergency_escape_points,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    items(state.points, key = {
                        "${it.point.type}-${it.point.latitude}-${it.point.longitude}"
                    }) { point -> PointRow(point) }
                }
                state.canyon?.engagement?.let { engagement -> item { EngagementCard(engagement) } }
                item { AdviceCard() }
            }
        }
    }
}

/** Which canyon: the first question of the rescuers, answered without typing. */
@Composable
private fun CanyonCard(state: EmergencyUiState, onSelectCanyon: (Int) -> Unit) {
    val canyon = state.canyon
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.EMERGENCY_CANYON), variant = DcCardVariant.Condition) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Hiking, contentDescription = null, tint = LocalDcColors.current.water)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    canyon != null -> {
                        Text(
                            canyon.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                        val details = listOf(canyon.cotation, canyon.place).filter { it.isNotBlank() }.joinToString(" · ")
                        if (details.isNotEmpty()) {
                            Text(details, style = MaterialTheme.typography.bodyMedium, color = LocalDcColors.current.textSecondary)
                        }
                        canyonContextLines(state).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    state.isLocatingCanyon -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.emergency_locating_canyon), style = MaterialTheme.typography.bodyMedium)
                    }

                    state.position == null -> Text(
                        stringResource(R.string.emergency_canyon_no_position),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    state.isPositionImprecise -> {
                        val uncertainty =
                            formatDistance(state.position.accuracyMeters?.roundToInt() ?: 0, LocalLocale.current.platformLocale)
                        val text = if (state.otherCanyons.isEmpty()) {
                            stringResource(R.string.emergency_canyon_imprecise_none, uncertainty)
                        } else {
                            stringResource(R.string.emergency_canyon_imprecise, uncertainty)
                        }
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }

                    else -> Text(stringResource(R.string.emergency_no_canyon_around), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        when {
            canyon != null && state.otherCanyons.isNotEmpty() -> OtherCanyonsChooser(state, onSelectCanyon)

            // The position does not tell which one: each canyon around is one tap away.
            canyon == null && state.isPositionImprecise -> state.otherCanyons.forEach { candidate ->
                OutlinedButton(
                    onClick = { onSelectCanyon(candidate.summary.id) },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.EMERGENCY_OTHER_CANYON),
                ) {
                    Text(
                        "${candidate.summary.nom} · ${formatDistance(
                            (candidate.distanceKm * 1000).roundToInt(),
                            LocalLocale.current.platformLocale,
                        )}",
                    )
                }
            }
        }
    }
}

/** The outing under way (its return and how many people, which the rescue asks), and how far the canyon is from the position. */
@Composable
private fun canyonContextLines(state: EmergencyUiState): List<String> {
    val locale = LocalLocale.current.platformLocale
    val outingLine = state.outing?.let { outing ->
        val back = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            .format(outing.expectedBack.atZone(ZoneId.systemDefault()))
        stringResource(R.string.emergency_canyon_outing, back)
    }
    val groupLine = state.outing?.groupSize?.let { size -> pluralStringResource(R.plurals.emergency_group, size, size) }
    val distanceLine = state.canyonDistanceMeters?.let { distance ->
        if (distance <= ON_ROUTE_METERS) {
            stringResource(R.string.emergency_canyon_here)
        } else {
            stringResource(R.string.emergency_canyon_around, formatDistance(distance, locale))
        }
    }
    return listOfNotNull(outingLine, groupLine, distanceLine)
}

@Composable
private fun OtherCanyonsChooser(state: EmergencyUiState, onSelectCanyon: (Int) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(TestTags.EMERGENCY_OTHER_CANYON)) {
            Text(stringResource(R.string.emergency_other_canyon))
        }
        DcDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.otherCanyons.forEach { other ->
                DcDropdownMenuItem(
                    text = other.summary.nom,
                    supportingText = formatDistance((other.distanceKm * 1000).roundToInt(), locale),
                    onClick = {
                        expanded = false
                        onSelectCanyon(other.summary.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun CallCard(state: EmergencyUiState, isDeviceLocked: Boolean) {
    val context = LocalContext.current
    val dialUnavailable = stringResource(R.string.emergency_dial_unavailable)
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.emergency_call_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            state.numbers.forEachIndexed { index, number ->
                val service = serviceLabel(number)
                val description = stringResource(R.string.emergency_call_description, number.number, service)
                val onClick = { dial(context, number.number, isDeviceLocked, dialUnavailable) }
                val buttonModifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = description }
                    .testTag(TestTags.emergencyNumber(number.number))
                if (index == 0) {
                    Button(
                        onClick = onClick,
                        modifier = buttonModifier,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { CallButtonContent(number.number, service) }
                } else {
                    OutlinedButton(onClick = onClick, modifier = buttonModifier) { CallButtonContent(number.number, service) }
                }
            }
            if (isDeviceLocked) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.emergency_locked_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.numbers.any { it.number == EmergencyNumbers.EUROPEAN.number }) {
                Text(
                    stringResource(R.string.emergency_112_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                )
            }
            if (!state.isCountryKnown) {
                Text(
                    stringResource(R.string.emergency_unknown_country),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CallButtonContent(number: String, service: String) {
    Icon(Icons.Default.Call, contentDescription = null)
    Text(
        text = number,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp),
    )
    Text(text = " · $service", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun serviceLabel(number: EmergencyNumber): String = stringResource(
    when (number.service) {
        EmergencyService.GENERAL -> R.string.emergency_service_general
        EmergencyService.MEDICAL -> R.string.emergency_service_medical
        EmergencyService.FIRE -> R.string.emergency_service_fire
        EmergencyService.POLICE -> R.string.emergency_service_police
        EmergencyService.GENDARMERIE -> R.string.emergency_service_gendarmerie
        EmergencyService.TOURIST_POLICE -> R.string.emergency_service_tourist_police
        EmergencyService.MOUNTAIN_RESCUE -> R.string.emergency_service_mountain
    },
)

/** The position to read to the rescuers or to send, with the closest point of the canyon. */
@Composable
private fun PositionCard(
    state: EmergencyUiState,
    hasPermission: Boolean,
    hasPrecisePermission: Boolean,
    isUnavailable: Boolean,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.EMERGENCY_POSITION)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MyLocation, contentDescription = null)
                Text(
                    stringResource(R.string.emergency_position_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            val fix = state.position
            if (hasPermission && !hasPrecisePermission) {
                // Rescuers need the exact place, not a circle of a few kilometres.
                Text(
                    stringResource(R.string.emergency_position_approximate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.emergency_position_precise_permission))
                }
            }
            when {
                fix != null -> {
                    val decimal = decimalCoordinates(fix.latitude, fix.longitude)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(decimal, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(toDegreesMinutes(fix.latitude, fix.longitude), style = MaterialTheme.typography.bodyLarge)
                        }
                        IconButton(onClick = { copyToClipboard(context, decimal) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_coordinates))
                        }
                    }
                    val details = listOfNotNull(
                        fix.accuracyMeters?.roundToInt()?.let { stringResource(R.string.emergency_position_accuracy, it) },
                        fix.altitudeMeters?.roundToInt()?.let { stringResource(R.string.emergency_position_altitude, it) },
                        stringResource(R.string.emergency_position_time, formatTime(fix.timeEpochMs, locale)),
                    ).joinToString(" · ")
                    Text(details, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textSecondary)
                    state.nearestPoint?.let { nearest ->
                        Text(
                            stringResource(
                                R.string.emergency_nearest_point,
                                pointLabel(nearest.point),
                                formatDistance(nearest.distanceMeters ?: 0, locale),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val message = emergencyMessage(resources, state, fix, locale)
                    // Stacked: the labels must stay whole on narrow phones.
                    Button(onClick = { sendSms(context, message) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null)
                        Text(stringResource(R.string.emergency_send_sms), modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = { share(context, message) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Text(stringResource(R.string.emergency_share), modifier = Modifier.padding(start = 8.dp))
                    }
                }

                !hasPermission -> OutlinedButton(onClick = onRequestPermission) {
                    Text(stringResource(R.string.emergency_position_permission))
                }

                isUnavailable -> Text(stringResource(R.string.emergency_position_unavailable), style = MaterialTheme.typography.bodyMedium)

                else -> Text(stringResource(R.string.emergency_position_waiting), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PointRow(item: EmergencyPoint) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val point = item.point
    val coordinates = decimalCoordinates(point.latitude, point.longitude)
    val label = pointLabel(point)
    // Escape routes stand out: getting out of the canyon is often the first thing to do.
    val variant = if (point.type == GeoPointType.ECHAPPATOIRE) DcCardVariant.Condition else DcCardVariant.Surface
    DcCard(modifier = Modifier.fillMaxWidth(), variant = variant, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                item.distanceMeters?.let { distance ->
                    Text(
                        listOfNotNull(formatDistance(distance, locale), item.direction?.let { directionLabel(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                pointSourceNote(point.source)?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                pointNotes(point, label).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(coordinates, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textSecondary)
            }
            IconButton(onClick = { navigateTo(context, point, label) }) {
                Icon(Icons.Default.Directions, contentDescription = stringResource(R.string.emergency_navigate_to, label))
            }
            IconButton(onClick = { copyToClipboard(context, coordinates) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_coordinates))
            }
        }
    }
}

/** The "engagement" section of the sheet: how committing the canyon is and where to get out. */
@Composable
private fun EngagementCard(engagement: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.emergency_engagement_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                engagement,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else ENGAGEMENT_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            if (engagement.length > ENGAGEMENT_COLLAPSE_THRESHOLD) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.emergency_read_less else R.string.emergency_read_more))
                }
            }
        }
    }
}

@Composable
private fun AdviceCard() {
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.emergency_advice_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.emergency_advice_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The kind of point, which tells the rescuers where it is: its title on the sheet is often a remark. */
@Composable
private fun pointLabel(point: GeoPoint): String = stringResource(pointTypeLabel(point.type))

/** Title and remark of the sheet, besides the kind of point already given. */
private fun pointNotes(point: GeoPoint, label: String): List<String> = listOfNotNull(point.title, point.remark)
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.equals(label, ignoreCase = true) }
    .distinct()

/** A point that is not on the canyon sheet of the site may be off: the rescuers are told. */
@StringRes
private fun pointSourceNote(source: GeoPointSource): Int? = when (source) {
    GeoPointSource.SITE -> null
    GeoPointSource.TOPO -> R.string.geo_point_source_topo
    GeoPointSource.OPENCANYON -> R.string.geo_point_source_opencanyon
    GeoPointSource.ESTIMATED -> R.string.geo_point_source_estimated
}

@StringRes
private fun pointTypeLabel(type: GeoPointType): Int = when (type) {
    GeoPointType.PARKING_AMONT -> R.string.geo_point_parking_upstream
    GeoPointType.PARKING_AVAL -> R.string.geo_point_parking_downstream
    GeoPointType.ENTREE -> R.string.geo_point_entry
    GeoPointType.SORTIE -> R.string.geo_point_exit
    GeoPointType.POINT_REMARQUABLE -> R.string.geo_point_remarkable
    GeoPointType.ECHAPPATOIRE -> R.string.geo_point_escape
    GeoPointType.UNKNOWN -> R.string.geo_point_gps
}

@Composable
private fun directionLabel(direction: CompassDirection): String = stringResource(
    when (direction) {
        CompassDirection.N -> R.string.direction_north
        CompassDirection.NE -> R.string.direction_north_east
        CompassDirection.E -> R.string.direction_east
        CompassDirection.SE -> R.string.direction_south_east
        CompassDirection.S -> R.string.direction_south
        CompassDirection.SW -> R.string.direction_south_west
        CompassDirection.W -> R.string.direction_west
        CompassDirection.NW -> R.string.direction_north_west
    },
)

/** What to text the rescuers or a relative: canyon, position, closest point and a map link. */
private fun emergencyMessage(resources: Resources, state: EmergencyUiState, fix: DeviceFix, locale: Locale): String {
    val canyon = state.canyon
    val latitude = fix.latitude.format5()
    val longitude = fix.longitude.format5()
    return listOfNotNull(
        // Far from the canyon shown, the position alone says where help is needed.
        canyon?.takeIf {
            state.isAtCanyon
        }?.let { resources.getString(R.string.emergency_sms_canyon, it.name, it.place.ifBlank { it.country }) }
            ?: resources.getString(R.string.emergency_sms_no_canyon),
        resources.getString(
            R.string.emergency_sms_position,
            "$latitude, $longitude",
            fix.accuracyMeters?.roundToInt() ?: 0,
            formatTime(fix.timeEpochMs, locale),
        ),
        state.nearestPoint?.let { nearest ->
            val label = resources.getString(pointTypeLabel(nearest.point.type))
            resources.getString(R.string.emergency_sms_nearest, formatDistance(nearest.distanceMeters ?: 0, locale), label)
        },
        "https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=16/$latitude/$longitude",
    ).joinToString("\n")
}

/**
 * Prefills the number in a dialer: no permission needed, and the user confirms the call. Over the
 * lock screen, the system emergency dialer takes emergency numbers without unlocking.
 */
private fun dial(context: Context, number: String, isDeviceLocked: Boolean, unavailableMessage: String) {
    val uri = "tel:$number".toUri()
    val intents = listOfNotNull(
        Intent(EMERGENCY_DIALER_ACTION, uri).takeIf { isDeviceLocked },
        Intent(Intent.ACTION_DIAL, uri),
    )
    val started = intents.any { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
    if (!started) Toast.makeText(context, unavailableMessage, Toast.LENGTH_LONG).show()
}

private fun sendSms(context: Context, message: String) {
    val intent = Intent(Intent.ACTION_SENDTO, "smsto:".toUri()).putExtra("sms_body", message)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        share(context, message)
    }
}

private fun share(context: Context, message: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.emergency_share)))
    } catch (_: ActivityNotFoundException) {
        copyToClipboard(context, message)
    }
}

/** Opens the point in a maps app: offline ones (OsmAnd, Organic Maps...) guide without network. */
private fun navigateTo(context: Context, point: GeoPoint, label: String) {
    val coordinates = "${point.latitude.format5()},${point.longitude.format5()}"
    val uri = "geo:$coordinates?q=$coordinates(${Uri.encode(label)})".toUri()
    try {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), context.getString(R.string.navigate_chooser_title)))
    } catch (_: ActivityNotFoundException) {
        copyToClipboard(context, coordinates)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("coordinates", text))
    // Android 13+ confirms clipboard writes itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

private fun decimalCoordinates(latitude: Double, longitude: Double): String = "${latitude.format5()}, ${longitude.format5()}"

private fun Double.format5(): String = String.format(Locale.ROOT, "%.5f", this)

private fun formatTime(epochMs: Long, locale: Locale): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    .withLocale(locale)
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** "350 m" below a kilometre, "1,2 km" below ten, "504 km" beyond. */
internal fun formatDistance(meters: Int, locale: Locale): String = when {
    meters < 1_000 -> "${(meters / 10.0).roundToInt() * 10} m"
    meters < 10_000 -> String.format(locale, "%.1f km", meters / 1000.0)
    else -> "${(meters / 1000.0).roundToInt()} km"
}

/** "45° 10.806' N, 5° 29.466' E": the format of many rescue services and paper maps. */
internal fun toDegreesMinutes(latitude: Double, longitude: Double): String {
    fun part(value: Double, positive: Char, negative: Char): String {
        val absolute = abs(value)
        val degrees = absolute.toInt()
        val minutes = (absolute - degrees) * 60
        return String.format(Locale.ROOT, "%d° %06.3f' %c", degrees, minutes, if (value >= 0) positive else negative)
    }
    return "${part(latitude, 'N', 'S')}, ${part(longitude, 'E', 'W')}"
}

/** Opens the dialer of the lock screen "Emergency call" button (AOSP and most phones). */
private const val EMERGENCY_DIALER_ACTION = "com.android.phone.EmergencyDialer.DIAL"

/** Closer than this to the route of the canyon, the user is in it or on its paths. */
private const val ON_ROUTE_METERS = 150
private const val ENGAGEMENT_COLLAPSED_LINES = 5
private const val ENGAGEMENT_COLLAPSE_THRESHOLD = 260
