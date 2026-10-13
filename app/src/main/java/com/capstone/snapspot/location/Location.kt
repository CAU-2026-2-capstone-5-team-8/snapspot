package com.capstone.snapspot.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Looper
import android.os.SystemClock
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.Fix
import com.capstone.snapspot.geofence.hasFineLocation
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

/** The user's position while the app is open (25m steps), for distances, the map and the in-app watcher. */
class LocationTracker(private val ctx: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(ctx)
    private val _here = MutableStateFlow<Coords?>(null)
    val here: StateFlow<Coords?> = _here.asStateFlow()
    private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { _here.value = Coords(it.latitude, it.longitude) }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasFineLocation(ctx)) return
        running = true
        fused.lastLocation.addOnSuccessListener { loc -> if (loc != null && _here.value == null) _here.value = Coords(loc.latitude, loc.longitude) }
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000).setMinUpdateDistanceMeters(25f).build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    fun stop() {
        if (!running) return
        running = false
        fused.removeLocationUpdates(callback)
    }

    @SuppressLint("MissingPermission")
    suspend fun current(): Coords? {
        _here.value?.let { return it }
        if (!hasFineLocation(ctx)) return null
        // Balanced uses Wi-Fi/cell only; with neither (no Wi-Fi, emulator) fall back to GPS once.
        val loc = currentAt(Priority.PRIORITY_BALANCED_POWER_ACCURACY) ?: currentAt(Priority.PRIORITY_HIGH_ACCURACY)
        return loc?.let { Coords(it.latitude, it.longitude) }?.also { _here.value = it }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentAt(priority: Int) =
        runCatching { fused.getCurrentLocation(priority, null).await() }.getOrNull()
}

/**
 * One position with its speed, for background work. A fix from the last minute is reused;
 * otherwise Wi-Fi/cell first, then GPS once (emulators and places without Wi-Fi have no network fix).
 * `needSpeed`: GPS first, since Wi-Fi/cell fixes carry no speed and "still on the bus" would read as walking.
 */
@SuppressLint("MissingPermission")
suspend fun currentFix(ctx: Context, needSpeed: Boolean = false): Fix? {
    if (!hasFineLocation(ctx)) return null
    val fused = LocationServices.getFusedLocationProviderClient(ctx)
    suspend fun at(priority: Int) = runCatching { fused.getCurrentLocation(priority, null).await() }.getOrNull()
    val last = runCatching { fused.lastLocation.await() }.getOrNull()
        ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 60_000_000_000L }
    val loc = (if (needSpeed) at(Priority.PRIORITY_HIGH_ACCURACY) ?: last ?: at(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
    else last ?: at(Priority.PRIORITY_BALANCED_POWER_ACCURACY) ?: at(Priority.PRIORITY_HIGH_ACCURACY))
        ?: return null
    return Fix(Coords(loc.latitude, loc.longitude), loc.takeIf { it.hasSpeed() }?.speed)
}

/**
 * Extracted name/address → coordinates with the device geocoder, most specific query first.
 */
suspend fun geocodePlace(ctx: Context, name: String, address: String): Coords? = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) return@withContext null
    val geocoder = Geocoder(ctx, Locale.KOREA)
    val queries = listOf(listOf(address, name).filter { it.isNotBlank() }.joinToString(" "), address, name)
        .filter { it.isNotBlank() }.distinct()
    for (q in queries) {
        @Suppress("DEPRECATION")
        val hit = runCatching { geocoder.getFromLocationName(q, 1)?.firstOrNull() }.getOrNull()
        if (hit != null) return@withContext Coords(hit.latitude, hit.longitude)
    }
    null
}
