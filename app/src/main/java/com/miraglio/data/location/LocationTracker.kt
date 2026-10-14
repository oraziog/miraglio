package com.miraglio.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private var started = false

    private val listener = LocationListener { location ->
        acceptLocation(location)
    }

    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean = runCatching {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    fun startLocationUpdates() {
        if (!hasFinePermission() || started) return
        started = true
        requestUpdates(LocationManager.GPS_PROVIDER, 500L, 0.5f)
        requestUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 1f)
    }

    fun stopLocationUpdates() {
        if (!started) return
        started = false
        runCatching { locationManager.removeUpdates(listener) }
    }

    private fun requestUpdates(provider: String, minTimeMs: Long, minDistanceM: Float) {
        // Doppio controllo al momento della chiamata: il permesso puo' essere
        // stato revocato dopo startLocationUpdates.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            locationManager.requestLocationUpdates(
                provider,
                minTimeMs,
                minDistanceM,
                listener,
                Looper.getMainLooper()
            )
        }
    }

    private fun acceptLocation(location: Location) {
        val accuracy = location.accuracy
        if (accuracy <= 0f || accuracy > MAX_ACCURACY_METERS) return
        _currentLocation.value = location
    }

    companion object {
        const val MAX_ACCURACY_METERS = 40f
    }
}