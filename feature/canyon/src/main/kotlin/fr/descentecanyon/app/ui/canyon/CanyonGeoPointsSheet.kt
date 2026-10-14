package fr.descentecanyon.app.ui.canyon

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.ui.common.startActivitySafely
import fr.descentecanyon.app.ui.map.MapLibreView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanyonGeoPointsSheet(
    detail: CanyonDetail,
    onDismiss: () -> Unit,
    onNavigate: (GeoPoint) -> Unit,
    onOpenFullscreen: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.show_map_points),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = detail.canyon.nom,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenFullscreen) {
                    Text(text = stringResource(R.string.fullscreen_map))
                }
            }

            CanyonGeoPointsMapAndList(
                detail = detail,
                onNavigate = onNavigate,
                mapHeight = 260.dp,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CanyonGeoPointsMapAndList(detail: CanyonDetail, onNavigate: (GeoPoint) -> Unit, mapHeight: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val markers = remember(detail.geoPoints, context) {
        detail.geoPoints.mapIndexed { index, point ->
            fr.descentecanyon.app.domain.model.CanyonSummary(
                id = detail.canyon.id * 10 + index,
                nom = point.navigationLabel(context),
                pays = detail.canyon.pays,
                cotation = detail.canyon.cotation,
                url = detail.canyon.url,
                latitude = point.latitude,
                longitude = point.longitude,
                markerType = point.type,
            )
        }
    }

    if (markers.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            MapLibreView(
                markers = markers,
                userLatitude = null,
                userLongitude = null,
                onMarkerClick = {},
                clusterMarkers = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mapHeight),
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = detail.geoPoints.sortedBy { it.type.navigationPriority() },
            key = { it.id.takeIf { id -> id != 0L } ?: (it.latitude.toString() + it.longitude.toString()) },
        ) { point ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(point.type.mapColor(), CircleShape),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = point.displayName(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = point.type.mapColor(),
                            )
                        }
                        point.displaySubtitle()?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.map_location_coordinates,
                                point.latitude,
                                point.longitude,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onNavigate(point) }) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.navigate))
                    }
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CanyonGeoPointsFullScreenDialog(detail: CanyonDetail, onDismiss: () -> Unit, onNavigate: (GeoPoint) -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = CardDefaults.shape,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.show_map_points),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = detail.canyon.nom,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                CanyonGeoPointsMapAndList(
                    detail = detail,
                    onNavigate = onNavigate,
                    mapHeight = 360.dp,
                )
            }
        }
    }
}

private fun openNavigation(context: android.content.Context, point: GeoPoint) {
    val label = Uri.encode(point.navigationLabel(context))
    val uri = "geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}($label)".toUri()
    context.startActivitySafely(Intent(Intent.ACTION_VIEW, uri))
}
