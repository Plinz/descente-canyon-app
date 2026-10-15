@file:Suppress("DEPRECATION")

package fr.descentecanyon.app.ui.map

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.map.MAP_STYLE_URI
import kotlin.math.roundToInt
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

internal const val TAG = "MapLibreView"

enum class UserTrackingMode {
    NONE,
    LOCATION,
    HEADING,
}

@Composable
fun MapLibreView(
    markers: List<CanyonSummary>,
    userLatitude: Double?,
    userLongitude: Double?,
    userBearingDegrees: Double? = null,
    userTrackingMode: UserTrackingMode = UserTrackingMode.NONE,
    positionCompassWithControls: Boolean = false,
    compassTopMargin: Dp? = null,
    compassEndMargin: Dp = 12.dp,
    compassFadeFacingNorth: Boolean = true,
    // Places the map data attribution ("i" button) at the top start, below the given margin, when
    // the bottom of the map is covered by app content (sheets, panels).
    attributionTopMargin: Dp? = null,
    onMarkerClick: (Int) -> Unit,
    onMapInteraction: () -> Unit = {},
    onVisibleBoundsChanged: (LatLngBounds) -> Unit = {},
    onCameraChanged: (MapCameraState) -> Unit = {},
    onUserCameraMove: () -> Unit = {},
    onCameraBearingChanged: (Double) -> Unit = {},
    clusterMarkers: Boolean = true,
    watershedGeometryJson: String? = null,
    watershedBounds: LatLngBounds? = null,
    showWatershed: Boolean = false,
    persistedCameraState: MapCameraState? = null,
    focusLocationRequestId: Int = 0,
    resetNorthRequestId: Int = 0,
    styleUri: String = MAP_STYLE_URI,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderState = remember { MapRenderState(context) }
    // Insets settle after composition during a rotation. Keep the renderer instance stable
    // while its compass margins are refreshed by updateData.
    val mapOptions = remember(context) {
        createMapOptions(
            context = context,
            positionCompassWithControls = positionCompassWithControls,
            compassTopMargin = compassTopMargin,
            compassEndMargin = compassEndMargin,
            compassFadeFacingNorth = compassFadeFacingNorth,
            attributionTopMargin = attributionTopMargin,
        )
    }
    val mapView = remember(mapOptions) {
        MapLibre.getInstance(context)
        MapView(context, mapOptions).apply { onCreate(Bundle()) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> Unit
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    DisposableEffect(mapView) {
        val refreshCompass = Runnable { renderState.refreshCompass() }
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                // MapLibre posts its own UI reset after this layout pass.
                mapView.removeCallbacks(refreshCompass)
                mapView.postDelayed(refreshCompass, 100L)
            }
        }
        mapView.addOnLayoutChangeListener(listener)
        onDispose {
            mapView.removeOnLayoutChangeListener(listener)
            mapView.removeCallbacks(refreshCompass)
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    renderState.bindMap(
                        map = map,
                        onMarkerClick = onMarkerClick,
                        onMapInteraction = onMapInteraction,
                        styleUri = styleUri,
                        positionCompassWithControls = positionCompassWithControls,
                        compassTopMargin = compassTopMargin,
                        compassEndMargin = compassEndMargin,
                        compassFadeFacingNorth = compassFadeFacingNorth,
                        attributionTopMargin = attributionTopMargin,
                    )
                }
            }
        },
        modifier = modifier,
        update = {
            renderState.updateData(
                markers = markers,
                userLatitude = userLatitude,
                userLongitude = userLongitude,
                userBearingDegrees = userBearingDegrees,
                userTrackingMode = userTrackingMode,
                positionCompassWithControls = positionCompassWithControls,
                compassTopMargin = compassTopMargin,
                compassEndMargin = compassEndMargin,
                compassFadeFacingNorth = compassFadeFacingNorth,
                attributionTopMargin = attributionTopMargin,
                onMarkerClick = onMarkerClick,
                onMapInteraction = onMapInteraction,
                onVisibleBoundsChanged = onVisibleBoundsChanged,
                onCameraChanged = onCameraChanged,
                onUserCameraMove = onUserCameraMove,
                onCameraBearingChanged = onCameraBearingChanged,
                clusterMarkers = clusterMarkers,
                watershedGeometryJson = watershedGeometryJson,
                watershedBounds = watershedBounds,
                showWatershed = showWatershed,
                persistedCameraState = persistedCameraState,
                focusLocationRequestId = focusLocationRequestId,
                resetNorthRequestId = resetNorthRequestId,
                styleUri = styleUri,
            )
        },
    )
}

private fun createMapOptions(
    context: android.content.Context,
    positionCompassWithControls: Boolean,
    compassTopMargin: Dp?,
    compassEndMargin: Dp,
    compassFadeFacingNorth: Boolean,
    attributionTopMargin: Dp?,
): MapLibreMapOptions {
    val density = context.resources.displayMetrics.density
    return MapLibreMapOptions()
        .compassEnabled(true)
        .compassFadesWhenFacingNorth(compassFadeFacingNorth)
        .logoEnabled(false)
        .apply {
            if (attributionTopMargin != null) {
                attributionGravity(Gravity.START or Gravity.TOP)
                attributionMargins(attributionMargins(attributionTopMargin, density))
            }
        }
        .apply {
            when {
                compassTopMargin != null -> {
                    compassGravity(Gravity.END or Gravity.TOP)
                    compassMargins(
                        intArrayOf(
                            0,
                            (compassTopMargin.value * density).roundToInt(),
                            (compassEndMargin.value * density).roundToInt(),
                            0,
                        ),
                    )
                }
                positionCompassWithControls -> {
                    compassGravity(Gravity.END or Gravity.CENTER_VERTICAL)
                    compassMargins(
                        intArrayOf(
                            0,
                            0,
                            (compassEndMargin.value * density).roundToInt(),
                            (58 * density).roundToInt(),
                        ),
                    )
                }
            }
        }
}

private class MapRenderState(private val context: android.content.Context) {
    private enum class RenderMode {
        VECTOR,
        ANNOTATION,
    }

    private var map: MapLibreMap? = null
    private var markers: List<CanyonSummary> = emptyList()
    private var userLatitude: Double? = null
    private var userLongitude: Double? = null
    private var userBearingDegrees: Double? = null
    private var userTrackingMode: UserTrackingMode = UserTrackingMode.NONE
    private var positionCompassWithControls: Boolean = false
    private var compassTopMargin: Dp? = null
    private var compassEndMargin: Dp = 12.dp
    private var compassFadeFacingNorth: Boolean = true
    private var attributionTopMargin: Dp? = null
    private var onMarkerClick: (Int) -> Unit = {}
    private var onMapInteraction: () -> Unit = {}
    private var onVisibleBoundsChanged: (LatLngBounds) -> Unit = {}
    private var onCameraChanged: (MapCameraState) -> Unit = {}
    private var onUserCameraMove: () -> Unit = {}
    private var onCameraBearingChanged: (Double) -> Unit = {}
    private var clusterMarkers: Boolean = true
    private var watershedGeometryJson: String? = null
    private var watershedBounds: LatLngBounds? = null
    private var showWatershed: Boolean = false
    private var persistedCameraState: MapCameraState? = null
    private var focusLocationRequestId: Int = 0
    private var lastFocusedLocationRequestId: Int = 0
    private var resetNorthRequestId: Int = 0
    private var lastResetNorthRequestId: Int = 0
    private var lastFollowSignature: Int? = null
    private var listenersAttached = false
    private var lastRenderSignature: Int? = null
    private var lastFitDataSignature: Int? = null
    private var didFitCamera = false
    private var styleUri: String = MAP_STYLE_URI
    private var renderMode: RenderMode? = null

    fun bindMap(
        map: MapLibreMap,
        onMarkerClick: (Int) -> Unit,
        onMapInteraction: () -> Unit,
        styleUri: String,
        positionCompassWithControls: Boolean,
        compassTopMargin: Dp?,
        compassEndMargin: Dp,
        compassFadeFacingNorth: Boolean,
        attributionTopMargin: Dp?,
    ) {
        this.map = map
        this.onMarkerClick = onMarkerClick
        this.onMapInteraction = onMapInteraction
        this.styleUri = styleUri
        this.positionCompassWithControls = positionCompassWithControls
        this.compassTopMargin = compassTopMargin
        this.compassEndMargin = compassEndMargin
        this.compassFadeFacingNorth = compassFadeFacingNorth
        this.attributionTopMargin = attributionTopMargin
        configureCompass(map)
        map.setStyle(Style.Builder().fromUri(styleUri)) {
            renderMode = null
            configureCompass(map)
            if (!listenersAttached) {
                attachListeners(map)
                listenersAttached = true
            }
            runCatching {
                render(force = true)
            }.onFailure { throwable ->
                Log.e(TAG, "Unable to render map after style load", throwable)
            }
        }
    }

    fun updateData(
        markers: List<CanyonSummary>,
        userLatitude: Double?,
        userLongitude: Double?,
        userBearingDegrees: Double?,
        userTrackingMode: UserTrackingMode,
        positionCompassWithControls: Boolean,
        compassTopMargin: Dp?,
        compassEndMargin: Dp,
        compassFadeFacingNorth: Boolean,
        attributionTopMargin: Dp?,
        onMarkerClick: (Int) -> Unit,
        onMapInteraction: () -> Unit,
        onVisibleBoundsChanged: (LatLngBounds) -> Unit,
        onCameraChanged: (MapCameraState) -> Unit,
        onUserCameraMove: () -> Unit,
        onCameraBearingChanged: (Double) -> Unit,
        clusterMarkers: Boolean,
        watershedGeometryJson: String?,
        watershedBounds: LatLngBounds?,
        showWatershed: Boolean,
        persistedCameraState: MapCameraState?,
        focusLocationRequestId: Int,
        resetNorthRequestId: Int,
        styleUri: String,
    ) {
        this.markers = markers
        this.userLatitude = userLatitude
        this.userLongitude = userLongitude
        this.userBearingDegrees = userBearingDegrees
        this.userTrackingMode = userTrackingMode
        this.positionCompassWithControls = positionCompassWithControls
        this.compassTopMargin = compassTopMargin
        this.compassEndMargin = compassEndMargin
        this.compassFadeFacingNorth = compassFadeFacingNorth
        this.attributionTopMargin = attributionTopMargin
        this.onMarkerClick = onMarkerClick
        this.onMapInteraction = onMapInteraction
        this.onVisibleBoundsChanged = onVisibleBoundsChanged
        this.onCameraChanged = onCameraChanged
        this.onUserCameraMove = onUserCameraMove
        this.onCameraBearingChanged = onCameraBearingChanged
        this.clusterMarkers = clusterMarkers
        this.watershedGeometryJson = watershedGeometryJson
        this.watershedBounds = watershedBounds
        this.showWatershed = showWatershed
        this.persistedCameraState = persistedCameraState
        this.focusLocationRequestId = focusLocationRequestId
        this.resetNorthRequestId = resetNorthRequestId
        map?.let(::configureCompass)

        if (this.styleUri != styleUri) {
            this.styleUri = styleUri
            lastRenderSignature = null
            didFitCamera = false
            map?.setStyle(Style.Builder().fromUri(styleUri)) {
                renderMode = null
                this@MapRenderState.map?.let(::configureCompass)
                render(force = true)
            }
            return
        }

        val fitDataSignature = buildFitDataSignature(markers, watershedBounds, watershedGeometryJson, showWatershed)
        if (fitDataSignature != lastFitDataSignature) {
            lastFitDataSignature = fitDataSignature
            didFitCamera = false
        }

        runCatching {
            render(force = false)
        }.onFailure { throwable ->
            Log.e(TAG, "Unable to refresh map data", throwable)
        }

        maybeResetNorth()
        maybeFocusOnLocation()
    }

    private fun attachListeners(map: MapLibreMap) {
        map.addOnCameraIdleListener {
            configureCompass(map)
            if (clusterMarkers) {
                runCatching {
                    refreshViewportDerivedSources(map)
                }.onFailure { throwable ->
                    Log.e(TAG, "Unable to refresh clustered map after camera idle", throwable)
                }
            }
            dispatchVisibleBounds(map)
            onCameraBearingChanged(normalizeBearing(map.cameraPosition.bearing))
        }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                onUserCameraMove()
            }
        }
        map.setOnMarkerClickListener { marker ->
            if (!clusterMarkers) {
                handleAnnotationTap(marker)
            }
            !clusterMarkers
        }
        map.addOnMapClickListener { latLng ->
            if (!clusterMarkers) {
                onMapInteraction()
                return@addOnMapClickListener false
            }
            handleClusteredTap(map, latLng)
        }
    }

    private fun configureCompass(map: MapLibreMap) {
        val density = context.resources.displayMetrics.density
        map.uiSettings.isLogoEnabled = false
        attributionTopMargin?.let { topMargin ->
            map.uiSettings.setAttributionGravity(Gravity.START or Gravity.TOP)
            val margins = attributionMargins(topMargin, density)
            map.uiSettings.setAttributionMargins(margins[0], margins[1], margins[2], margins[3])
        }
        map.uiSettings.setCompassEnabled(true)
        map.uiSettings.setCompassFadeFacingNorth(compassFadeFacingNorth)
        when {
            compassTopMargin != null -> {
                map.uiSettings.setCompassGravity(Gravity.END or Gravity.TOP)
                map.uiSettings.setCompassMargins(
                    0,
                    (compassTopMargin!!.value * density).roundToInt(),
                    (compassEndMargin.value * density).roundToInt(),
                    0,
                )
            }
            positionCompassWithControls -> {
                map.uiSettings.setCompassGravity(Gravity.END or Gravity.CENTER_VERTICAL)
                map.uiSettings.setCompassMargins(
                    0,
                    0,
                    (compassEndMargin.value * density).roundToInt(),
                    (58 * density).roundToInt(),
                )
            }
        }
    }

    fun refreshCompass() {
        map?.let(::configureCompass)
    }

    private fun handleAnnotationTap(marker: Marker) {
        val canyonId = marker.snippet.orEmpty()
            .removePrefix("canyon:")
            .toIntOrNull()
            ?: return
        onMarkerClick(canyonId)
    }

    private fun handleClusteredTap(map: MapLibreMap, latLng: LatLng): Boolean {
        val screenPoint = map.projection.toScreenLocation(latLng)
        val detailClusterFeature = map.queryRenderedFeatures(
            screenPoint,
            CANYON_VISIBLE_CLUSTER_LAYER_ID,
            CANYON_VISIBLE_CLUSTER_COUNT_LAYER_ID,
        ).firstOrNull()
        if (detailClusterFeature != null) {
            onMapInteraction()
            zoomToCluster(map, detailClusterFeature, CANYON_VISIBLE_SOURCE_ID)
            return true
        }

        val clusterFeature = map.queryRenderedFeatures(
            screenPoint,
            CANYON_CLUSTER_LAYER_ID,
            CANYON_CLUSTER_COUNT_LAYER_ID,
        ).firstOrNull()
        if (clusterFeature != null) {
            onMapInteraction()
            zoomToCluster(map, clusterFeature, CANYON_SOURCE_ID)
            return true
        }

        val canyonFeature = map.queryRenderedFeatures(
            screenPoint,
            *CANYON_TAP_LAYER_IDS.toTypedArray(),
        ).firstOrNull()
        val canyonId = canyonFeature?.getNumberProperty(PROPERTY_CANYON_ID)?.toInt()
            ?: canyonFeature?.getStringProperty(PROPERTY_CANYON_ID)?.toIntOrNull()
            ?: run {
                onMapInteraction()
                return false
            }
        onMarkerClick(canyonId)
        return true
    }

    private fun zoomToCluster(map: MapLibreMap, clusterFeature: Feature, sourceId: String) {
        val geometry = clusterFeature.geometry() as? Point ?: return
        val source = map.style?.getSourceAs<GeoJsonSource>(sourceId)
        val expansionZoom = runCatching {
            source?.getClusterExpansionZoom(clusterFeature)
                ?.toDouble()
        }.getOrNull()
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(geometry.latitude(), geometry.longitude()),
                clusterTapTargetZoom(map.cameraPosition.zoom, expansionZoom),
            ),
            350,
        )
    }

    private fun render(force: Boolean) {
        val map = map ?: return
        val style = map.style ?: return
        val signature = buildRenderSignature(
            markers = markers,
            userLatitude = userLatitude,
            userLongitude = userLongitude,
            userBearingDegrees = userBearingDegrees,
            userTrackingMode = userTrackingMode,
            clusterMarkers = clusterMarkers,
            watershedGeometryJson = watershedGeometryJson,
            showWatershed = showWatershed,
        )
        if (!force && lastRenderSignature == signature) return
        lastRenderSignature = signature

        updateWatershed(style)

        if (clusterMarkers) {
            renderVector(style, map)
        } else {
            renderAnnotated(style, map)
        }

        if (!didFitCamera) {
            val restored = restoreCamera(map)
            if (!restored) {
                fitCamera(
                    map = map,
                    coordinates = canyonCoordinates(),
                    watershedBounds = watershedBounds.takeIf { showWatershed && !watershedGeometryJson.isNullOrBlank() },
                )
            }
            didFitCamera = true
        }

        dispatchVisibleBounds(map)
    }

    private fun renderVector(style: Style, map: MapLibreMap) {
        if (renderMode != RenderMode.VECTOR) {
            map.clear()
            renderMode = RenderMode.VECTOR
        }
        ensureVectorStyle(style)
        updateVisiblePointSource(style, map)
        style.getSourceAs<GeoJsonSource>(USER_SOURCE_ID)?.setGeoJson(buildUserFeatureCollection(userLatitude, userLongitude))
    }

    private fun refreshViewportDerivedSources(map: MapLibreMap) {
        val style = map.style ?: return
        if (renderMode != RenderMode.VECTOR) return
        updateVisiblePointSource(style, map)
    }

    private fun updateVisiblePointSource(style: Style, map: MapLibreMap) {
        val visibleMarkers = filterMarkersInViewport(markers, map)
        val detailMode = shouldShowDetailPoints(map.cameraPosition.zoom, visibleMarkers.size)
        style.getSourceAs<GeoJsonSource>(CANYON_SOURCE_ID)?.setGeoJson(
            if (detailMode) EMPTY_FEATURE_COLLECTION else buildCanyonFeatureCollection(markers),
        )
        style.getSourceAs<GeoJsonSource>(CANYON_VISIBLE_SOURCE_ID)?.setGeoJson(
            if (detailMode) {
                buildCanyonFeatureCollection(visibleMarkers)
            } else {
                EMPTY_FEATURE_COLLECTION
            },
        )
    }

    private fun renderAnnotated(style: Style, map: MapLibreMap) {
        clearClusteredSources(style)
        val iconFactory = IconFactory.getInstance(context)
        map.clear()
        renderMode = RenderMode.ANNOTATION
        buildAnnotationMarkers().forEach { marker ->
            map.addMarker(marker.toMarkerOptions(iconFactory, context))
        }
    }

    private fun fitCamera(map: MapLibreMap, coordinates: List<LatLng>, watershedBounds: LatLngBounds?) {
        if (coordinates.isEmpty() && watershedBounds == null) return

        if (coordinates.size == 1 && watershedBounds == null) {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    coordinates.first(),
                    13.6,
                ),
            )
            return
        }

        val bounds = LatLngBounds.Builder().apply {
            coordinates.forEach(::include)
            watershedBounds?.let {
                include(it.northEast)
                include(it.southWest)
            }
        }.build()

        val camera = map.getCameraForLatLngBounds(bounds, intArrayOf(96, 96, 96, 164))
        if (camera != null) {
            val adjustedZoom = coordinates.takeIf { it.isNotEmpty() }?.let(::preferredZoom)?.let { minOf(camera.zoom, it) }
                ?: camera.zoom
            val target = camera.target ?: coordinates.firstOrNull() ?: watershedBounds?.center ?: return
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    target,
                    adjustedZoom,
                ),
            )
        } else {
            val fallbackTarget = coordinates.firstOrNull() ?: watershedBounds?.center ?: return
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    fallbackTarget,
                    coordinates.takeIf { it.isNotEmpty() }?.let(::preferredZoom) ?: 10.0,
                ),
            )
        }
    }

    private fun preferredZoom(coordinates: List<LatLng>): Double {
        if (coordinates.size <= 1) return 14.5

        val latitudes = coordinates.map { it.latitude }
        val longitudes = coordinates.map { it.longitude }
        val maxSpan = maxOf(
            (latitudes.maxOrNull() ?: 0.0) - (latitudes.minOrNull() ?: 0.0),
            (longitudes.maxOrNull() ?: 0.0) - (longitudes.minOrNull() ?: 0.0),
        )

        return when {
            maxSpan < 0.003 -> 14.3
            maxSpan < 0.008 -> 13.3
            maxSpan < 0.02 -> 12.2
            maxSpan < 0.05 -> 11.2
            maxSpan < 0.12 -> 10.2
            else -> 8.8
        }
    }

    private fun buildFitDataSignature(
        markers: List<CanyonSummary>,
        watershedBounds: LatLngBounds?,
        watershedGeometryJson: String?,
        showWatershed: Boolean,
    ): Int {
        var result = 17
        markers.sortedBy { it.id }.forEach { canyon ->
            result = 31 * result + canyon.id
            result = 31 * result + canyon.latitude.coordinateHash()
            result = 31 * result + canyon.longitude.coordinateHash()
        }
        result = 31 * result + showWatershed.hashCode()
        if (showWatershed && !watershedGeometryJson.isNullOrBlank()) {
            result = 31 * result + watershedGeometryJson.hashCode()
            result = 31 * result + (watershedBounds?.toSignatureHash() ?: 0)
        }
        return result
    }

    private fun maybeFocusOnLocation() {
        val map = map ?: return
        val latitude = userLatitude ?: return
        val longitude = userLongitude ?: return

        if (userTrackingMode != UserTrackingMode.NONE) {
            val bearing = when (userTrackingMode) {
                UserTrackingMode.LOCATION -> 0.0
                UserTrackingMode.HEADING -> userBearingDegrees ?: map.cameraPosition.bearing
                UserTrackingMode.NONE -> map.cameraPosition.bearing
            }
            val signature = listOf(
                latitude.coordinateHash(),
                longitude.coordinateHash(),
                bearing.roundToInt(),
                userTrackingMode.ordinal,
            ).fold(17) { result, value -> 31 * result + value }
            if (signature == lastFollowSignature) return

            lastFollowSignature = signature
            didFitCamera = true
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(latitude, longitude))
                        .zoom(maxOf(map.cameraPosition.zoom, USER_LOCATION_FOCUS_ZOOM))
                        .bearing(bearing)
                        .build(),
                ),
                350,
            )
            return
        }

        if (focusLocationRequestId == 0 || focusLocationRequestId == lastFocusedLocationRequestId) return

        lastFocusedLocationRequestId = focusLocationRequestId
        didFitCamera = true
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), USER_LOCATION_FOCUS_ZOOM),
            500,
        )
    }

    private fun maybeResetNorth() {
        val map = map ?: return
        if (resetNorthRequestId == 0 || resetNorthRequestId == lastResetNorthRequestId) return

        val camera = map.cameraPosition
        val target = camera.target ?: return
        lastResetNorthRequestId = resetNorthRequestId
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(target)
                    .zoom(camera.zoom)
                    .tilt(camera.tilt)
                    .bearing(0.0)
                    .build(),
            ),
            250,
        )
    }

    private fun restoreCamera(map: MapLibreMap): Boolean {
        val cameraState = persistedCameraState ?: return false
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(cameraState.latitude, cameraState.longitude),
                cameraState.zoom,
            ),
        )
        return true
    }

    private fun dispatchVisibleBounds(map: MapLibreMap) {
        runCatching {
            map.projection.visibleRegion.latLngBounds
        }.getOrNull()?.let(onVisibleBoundsChanged)

        map.cameraPosition.target?.let { target ->
            onCameraChanged(
                MapCameraState(
                    latitude = target.latitude,
                    longitude = target.longitude,
                    zoom = map.cameraPosition.zoom,
                ),
            )
        }
    }

    private fun buildRenderSignature(
        markers: List<CanyonSummary>,
        userLatitude: Double?,
        userLongitude: Double?,
        userBearingDegrees: Double?,
        userTrackingMode: UserTrackingMode,
        clusterMarkers: Boolean,
        watershedGeometryJson: String?,
        showWatershed: Boolean,
    ): Int {
        var result = 17
        result = 31 * result + clusterMarkers.hashCode()
        markers.sortedBy { it.id }.forEach { canyon ->
            result = 31 * result + canyon.id
            result = 31 * result + canyon.latitude.coordinateHash()
            result = 31 * result + canyon.longitude.coordinateHash()
            result = 31 * result + (canyon.markerType?.ordinal ?: -1)
            result = 31 * result + (canyon.interet?.times(100)?.roundToInt() ?: -1)
        }
        result = 31 * result + userLatitude.coordinateHash()
        result = 31 * result + userLongitude.coordinateHash()
        result = 31 * result + userBearingDegrees.coordinateHash()
        result = 31 * result + userTrackingMode.ordinal
        result = 31 * result + showWatershed.hashCode()
        if (showWatershed && !watershedGeometryJson.isNullOrBlank()) {
            result = 31 * result + watershedGeometryJson.hashCode()
        }
        return result
    }

    private fun updateWatershed(style: Style) {
        ensureWatershedStyle(style)
        val source = style.getSource(WATERSHED_SOURCE_ID) as? GeoJsonSource ?: return
        val geoJson = if (showWatershed && !watershedGeometryJson.isNullOrBlank()) {
            toFeatureGeoJson(watershedGeometryJson!!)
        } else {
            EMPTY_GEOJSON
        }
        runCatching {
            source.setGeoJson(geoJson)
        }.onFailure { throwable ->
            Log.e(TAG, "Unable to update watershed polygon", throwable)
            source.setGeoJson(EMPTY_GEOJSON)
        }
    }

    private fun canyonCoordinates(): List<LatLng> {
        return markers.mapNotNull { canyon ->
            val latitude = canyon.latitude ?: return@mapNotNull null
            val longitude = canyon.longitude ?: return@mapNotNull null
            LatLng(latitude, longitude)
        }
    }

    private fun buildAnnotationMarkers(): List<AnnotationMarker> {
        return buildList {
            markers.forEach { canyon ->
                val latitude = canyon.latitude ?: return@forEach
                val longitude = canyon.longitude ?: return@forEach
                add(AnnotationMarker.Canyon(canyon, latitude, longitude))
            }
            val currentUserLatitude = userLatitude
            val currentUserLongitude = userLongitude
            if (currentUserLatitude != null && currentUserLongitude != null) {
                add(
                    AnnotationMarker.User(
                        latitude = currentUserLatitude,
                        longitude = currentUserLongitude,
                        // Annotation icons are screen-aligned: keep the arrow up while the map follows heading.
                        bearingDegrees = when (userTrackingMode) {
                            UserTrackingMode.NONE -> null
                            UserTrackingMode.LOCATION -> userBearingDegrees
                            UserTrackingMode.HEADING -> 0.0
                        },
                    ),
                )
            }
        }
    }
}

/** Left, top, right, bottom margins in pixels for an attribution placed at the top start. */
private fun attributionMargins(topMargin: Dp, density: Float): IntArray = intArrayOf(
    (ATTRIBUTION_START_MARGIN.value * density).roundToInt(),
    (topMargin.value * density).roundToInt(),
    0,
    0,
)

private val ATTRIBUTION_START_MARGIN = 12.dp
