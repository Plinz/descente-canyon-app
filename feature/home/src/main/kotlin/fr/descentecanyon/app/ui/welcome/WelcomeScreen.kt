package fr.descentecanyon.app.ui.welcome

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcSelectField
import fr.descentecanyon.app.ui.design.DcZonePickerSheet
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import fr.descentecanyon.app.ui.design.zoneFieldValue
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.test.TestTags

/**
 * The first opening of the app: what it does in three lines, the user's area (the latest reports of
 * the home screen and the map open there) and the position, explained. Everything can be skipped.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit, modifier: Modifier = Modifier, viewModel: WelcomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = LocalDcColors.current
    val contentWidth = rememberDcContentWidth(maxWidth = 560.dp)
    val horizontalPadding = rememberDcScreenHorizontalPadding()
    var showZones by rememberSaveable { mutableStateOf(false) }
    var hasLocation by rememberSaveable { mutableStateOf(context.hasLocationPermission()) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        hasLocation = granted.values.any { it }
    }

    Box(
        modifier = modifier.fillMaxSize().background(colors.backgroundBase).statusBarsPadding().navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(modifier = Modifier.width(contentWidth).fillMaxSize().padding(horizontal = horizontalPadding)) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                WelcomeLine(Icons.Default.Search, stringResource(R.string.welcome_line_find))
                WelcomeLine(Icons.Default.Hiking, stringResource(R.string.welcome_line_plan))
                WelcomeLine(Icons.Default.Sos, stringResource(R.string.welcome_line_sos))

                Spacer(modifier = Modifier.height(4.dp))
                DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                    Text(
                        stringResource(R.string.welcome_zone_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.welcome_zone_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    DcSelectField(
                        icon = Icons.Default.Place,
                        label = stringResource(R.string.zone_filter_title),
                        value = zoneFieldValue(state.selectedCountry, state.selectedSubdivision),
                        isActive = state.selectedCountry != null,
                        enabled = state.zones.isNotEmpty(),
                        onClick = { showZones = true },
                    )
                }
                DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                    Text(
                        stringResource(R.string.welcome_location_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.welcome_location_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (hasLocation) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.primaryAction)
                            Text(stringResource(R.string.welcome_location_allowed), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                locationLauncher.launch(
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                )
                            },
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.welcome_location_allow), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDone, modifier = Modifier.testTag(TestTags.WELCOME_SKIP)) {
                    Text(stringResource(R.string.welcome_skip))
                }
                Button(onClick = { viewModel.finish(onDone) }, modifier = Modifier.testTag(TestTags.WELCOME_START)) {
                    Text(stringResource(R.string.welcome_start))
                }
            }
        }
    }

    if (showZones) {
        DcZonePickerSheet(
            zones = state.zones,
            selectedCountry = state.selectedCountry,
            selectedSubdivision = state.selectedSubdivision,
            subtitle = stringResource(R.string.welcome_zone_hint),
            onZoneSelected = { country, subdivision ->
                viewModel.selectZone(country, subdivision)
                showZones = false
            },
            onDismiss = { showZones = false },
        )
    }
}

@Composable
private fun WelcomeLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, tint = LocalDcColors.current.primaryAction, modifier = Modifier.size(28.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
