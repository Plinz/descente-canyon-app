package fr.descentecanyon.app.ui.outing

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.EmergencyNumber
import fr.descentecanyon.app.domain.model.EmergencyService
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.location.DeviceFix
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.location.observeDeviceFixes
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What to do in an emergency, offline: numbers of the canyon's country, current position to read
 * or text to the rescuers, the escape routes of the sheet and what to tell them.
 */
@Composable
fun EmergencyScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier, viewModel: EmergencyViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)

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
                item { CallCard(state) }
                item { PositionCard(canyonName = state.canyonName) }
                item { AdviceCard() }
                if (state.points.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.emergency_escape_points),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(state.points, key = { "${it.type}-${it.latitude}-${it.longitude}" }) { point -> PointRow(point) }
                }
            }
        }
    }
}

@Composable
private fun CallCard(state: EmergencyUiState) {
    val context = LocalContext.current
    val dialUnavailable = stringResource(R.string.emergency_dial_unavailable)
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.emergency_call_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            state.numbers.forEachIndexed { index, number ->
                val service = serviceLabel(number)
                val description = stringResource(R.string.emergency_call_description, number.number, service)
                val onClick = {
                    // ACTION_DIAL needs no permission and lets the user confirm the call.
                    try {
                        context.startActivity(Intent(Intent.ACTION_DIAL, "tel:${number.number}".toUri()))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, dialUnavailable, Toast.LENGTH_LONG).show()
                    }
                }
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
            Text(
                stringResource(R.string.emergency_112_hint),
                style = MaterialTheme.typography.bodySmall,
                color = LocalDcColors.current.textSecondary,
            )
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
        EmergencyService.MOUNTAIN_RESCUE -> R.string.emergency_service_mountain
    },
)

@Composable
private fun PositionCard(canyonName: String?) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    var hasPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    var fix by remember { mutableStateOf<DeviceFix?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermission = results.values.any { it }
    }

    DisposableEffect(hasPermission) {
        if (!hasPermission) {
            onDispose {}
        } else {
            val stop = observeDeviceFixes(context, onFix = {
                fix = it
                unavailable = false
            }, onUnavailable = { unavailable = true })
            onDispose(stop)
        }
    }

    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.EMERGENCY_POSITION)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MyLocation, contentDescription = null)
                Text(
                    stringResource(R.string.emergency_position_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            val current = fix
            when {
                !hasPermission -> OutlinedButton(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    },
                ) { Text(stringResource(R.string.emergency_position_permission)) }

                current != null -> {
                    val decimal = String.format(Locale.ROOT, "%.5f, %.5f", current.latitude, current.longitude)
                    Text(decimal, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(toDegreesMinutes(current.latitude, current.longitude), style = MaterialTheme.typography.bodyLarge)
                    val accuracy = current.accuracyMeters?.roundToInt()
                    val details = listOfNotNull(
                        accuracy?.let { stringResource(R.string.emergency_position_accuracy, it) },
                        current.altitudeMeters?.roundToInt()?.let { stringResource(R.string.emergency_position_altitude, it) },
                    ).joinToString(" · ")
                    if (details.isNotEmpty()) {
                        Text(
                            details,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalDcColors.current.textSecondary,
                        )
                    }
                    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
                        .format(Instant.ofEpochMilli(current.timeEpochMs).atZone(ZoneId.systemDefault()))
                    val smsBody = resources.getString(R.string.emergency_sms_body, canyonName.orEmpty(), decimal, accuracy ?: 0, time)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyToClipboard(context, decimal) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Text(stringResource(R.string.copy_coordinates), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                        }
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SENDTO, "smsto:".toUri()).putExtra("sms_body", smsBody)
                                try {
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, smsBody),
                                            null,
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null)
                            Text(stringResource(R.string.emergency_send_sms), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                        }
                    }
                }

                unavailable -> Text(stringResource(R.string.emergency_position_unavailable), style = MaterialTheme.typography.bodyMedium)

                else -> Text(stringResource(R.string.emergency_position_waiting), style = MaterialTheme.typography.bodyMedium)
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
            )
            Text(stringResource(R.string.emergency_advice_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PointRow(point: GeoPoint) {
    val context = LocalContext.current
    val coordinates = String.format(Locale.ROOT, "%.5f, %.5f", point.latitude, point.longitude)
    DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(pointLabel(point), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                point.remark?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(coordinates, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textSecondary)
            }
            IconButton(onClick = { copyToClipboard(context, coordinates) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_coordinates))
            }
        }
    }
}

@Composable
private fun pointLabel(point: GeoPoint): String = point.title?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(
    when (point.type) {
        GeoPointType.PARKING_AMONT -> R.string.geo_point_parking_upstream
        GeoPointType.PARKING_AVAL -> R.string.geo_point_parking_downstream
        GeoPointType.ENTREE -> R.string.geo_point_entry
        GeoPointType.SORTIE -> R.string.geo_point_exit
        GeoPointType.POINT_REMARQUABLE -> R.string.geo_point_remarkable
        GeoPointType.ECHAPPATOIRE -> R.string.geo_point_escape
        GeoPointType.UNKNOWN -> R.string.geo_point_gps
    },
)

private fun copyToClipboard(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("coordinates", text))
    // Android 13+ confirms clipboard writes itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
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
