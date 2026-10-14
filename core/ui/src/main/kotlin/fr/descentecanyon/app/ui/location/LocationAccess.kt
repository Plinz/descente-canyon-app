package fr.descentecanyon.app.ui.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

fun Context.hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
    android.content.pm.PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
    android.content.pm.PackageManager.PERMISSION_GRANTED

fun requestLocationSettings(
    context: Context,
    onEnabled: () -> Unit,
    onResolutionRequired: (IntentSenderRequest) -> Unit,
    onUnavailable: () -> Unit,
) {
    val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        LOCATION_SETTINGS_INTERVAL_MS,
    ).build()
    val settingsRequest = LocationSettingsRequest.Builder()
        .addLocationRequest(locationRequest)
        .setAlwaysShow(true)
        .build()

    LocationServices.getSettingsClient(context)
        .checkLocationSettings(settingsRequest)
        .addOnSuccessListener { onEnabled() }
        .addOnFailureListener { throwable ->
            val resolvable = throwable as? ResolvableApiException
            if (resolvable != null) {
                onResolutionRequired(
                    IntentSenderRequest.Builder(resolvable.resolution.intentSender).build(),
                )
            } else {
                onUnavailable()
            }
        }
}

@SuppressLint("MissingPermission")
fun loadCurrentDeviceLocation(context: Context, onLocation: (Double, Double) -> Unit, onUnavailable: () -> Unit) {
    if (!context.hasLocationPermission()) {
        onUnavailable()
        return
    }

    val client = LocationServices.getFusedLocationProviderClient(context)
    val cancellationTokenSource = CancellationTokenSource()
    val handler = Handler(Looper.getMainLooper())
    var completed = false
    var timeout: Runnable? = null
    var locationCallback: LocationCallback? = null

    fun cleanup() {
        timeout?.let(handler::removeCallbacks)
        locationCallback?.let { callback -> client.removeLocationUpdates(callback) }
        cancellationTokenSource.cancel()
    }

    fun finishUnavailable() {
        if (completed) return
        completed = true
        cleanup()
        onUnavailable()
    }

    fun finishWith(location: Location) {
        if (completed) return
        completed = true
        cleanup()
        onLocation(location.latitude, location.longitude)
    }

    fun useIfFresh(location: Location?) {
        if (location != null && location.isFreshEnough()) {
            finishWith(location)
        }
    }

    val timeoutRunnable = Runnable { finishUnavailable() }
    timeout = timeoutRunnable
    handler.postDelayed(timeoutRunnable, LOCATION_LOOKUP_TIMEOUT_MS)

    val updatesRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        LOCATION_UPDATE_INTERVAL_MS,
    )
        .setMinUpdateIntervalMillis(LOCATION_UPDATE_MIN_INTERVAL_MS)
        .setMaxUpdateDelayMillis(LOCATION_UPDATE_INTERVAL_MS)
        .setDurationMillis(LOCATION_LOOKUP_TIMEOUT_MS)
        .build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            useIfFresh(result.lastLocation)
        }
    }
    locationCallback = callback

    client.requestLocationUpdates(updatesRequest, callback, Looper.getMainLooper())
        .addOnFailureListener { finishUnavailable() }

    val currentLocationRequest = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MS)
        .setMaxUpdateAgeMillis(LOCATION_MAX_UPDATE_AGE_MS)
        .build()

    client.getCurrentLocation(currentLocationRequest, cancellationTokenSource.token)
        .addOnSuccessListener(::useIfFresh)
}

/**
 * Observes the foreground device position. Call the returned function as soon as the map leaves
 * composition so GPS updates do not continue in the background.
 */
@SuppressLint("MissingPermission")
fun observeDeviceLocation(context: Context, onLocation: (Double, Double) -> Unit, onUnavailable: () -> Unit = {}): () -> Unit {
    if (!context.hasLocationPermission()) {
        onUnavailable()
        return {}
    }

    val client = LocationServices.getFusedLocationProviderClient(context)
    val request = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        LOCATION_UPDATE_INTERVAL_MS,
    )
        .setMinUpdateIntervalMillis(LOCATION_UPDATE_MIN_INTERVAL_MS)
        .build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                onLocation(location.latitude, location.longitude)
            }
        }
    }

    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        .addOnFailureListener { onUnavailable() }

    return { client.removeLocationUpdates(callback) }
}

private fun Location.isFreshEnough(): Boolean {
    val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
    return ageMs in 0..LOCATION_MAX_UPDATE_AGE_MS
}

private const val LOCATION_SETTINGS_INTERVAL_MS = 10_000L
private const val CURRENT_LOCATION_TIMEOUT_MS = 15_000L
private const val LOCATION_LOOKUP_TIMEOUT_MS = 45_000L
private const val LOCATION_UPDATE_INTERVAL_MS = 1_500L
private const val LOCATION_UPDATE_MIN_INTERVAL_MS = 500L
private const val LOCATION_MAX_UPDATE_AGE_MS = 30_000L
