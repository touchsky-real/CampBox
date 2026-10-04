package com.inonvation.lightlife.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class WaterLocation(
    val latitude: Double,
    val longitude: Double,
)

class WaterLocationProvider(private val context: Context) {
    private val locationManager = context.getSystemService(LocationManager::class.java)

    suspend fun currentLocation(): WaterLocation {
        if (!hasLocationPermission()) throw LocationUnavailableException("未授予定位权限")

        val lastKnown = lastKnownLocation()
        if (lastKnown != null && isFreshEnough(lastKnown)) {
            return lastKnown.toWaterLocation()
        }

        val location = try {
            withTimeout(LOCATION_TIMEOUT_MILLIS) {
                requestFreshLocation()
            }
        } catch (_: TimeoutCancellationException) {
            throw LocationUnavailableException("定位超时，请确认定位服务已开启")
        }
        return location.toWaterLocation()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        val providers = locationManager.getProviders(true)
        return providers
            .mapNotNull { provider -> locationManager.getLastKnownLocation(provider) }
            .maxByOrNull { location -> locationScore(location) }
    }

    // 仅在 currentLocation() 的权限检查通过后调用，运行时权限已确认
    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocation(): Location {
        val providers = locationManager.getProviders(true)
            .filter { it in PREFERRED_PROVIDERS }
            .sortedBy { PREFERRED_PROVIDERS.indexOf(it) }
        if (providers.isEmpty()) throw LocationUnavailableException("定位服务未开启")

        return suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            val listener = LocationListener { location ->
                if (resumed.compareAndSet(false, true)) {
                    continuation.resume(location)
                }
            }
            continuation.invokeOnCancellation {
                locationManager.removeUpdates(listener)
            }
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    MIN_UPDATE_INTERVAL_MILLIS,
                    MIN_UPDATE_DISTANCE_METERS,
                    listener,
                    Looper.getMainLooper(),
                )
            }
        }
    }

    private fun isFreshEnough(location: Location): Boolean {
        val age = System.currentTimeMillis() - location.time
        return age in 0..MAX_LAST_KNOWN_AGE_MILLIS
    }

    private fun locationScore(location: Location): Long = location.time - location.accuracy.toLong()

    private fun Location.toWaterLocation() = WaterLocation(latitude = latitude, longitude = longitude)

    private companion object {
        val PREFERRED_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val LOCATION_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15)
        val MAX_LAST_KNOWN_AGE_MILLIS = TimeUnit.MINUTES.toMillis(5)
        const val MIN_UPDATE_INTERVAL_MILLIS = 1_000L
        const val MIN_UPDATE_DISTANCE_METERS = 0f
    }
}

class LocationUnavailableException(message: String) : Exception(message)
