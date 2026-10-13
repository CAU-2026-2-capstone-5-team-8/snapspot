package com.capstone.snapspot.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.capstone.snapspot.core.BranchCache
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.KakaoApi
import com.capstone.snapspot.core.Plan
import com.capstone.snapspot.data.Repository
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

data class SyncResult(val plan: Plan, val registered: Boolean, val error: String?)

fun hasFineLocation(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun hasBackgroundLocation(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Re-plans the window around `here` and registers it with the OS (replacing the previous one). */
class GeofenceSync(
    private val ctx: Context,
    private val repo: Repository,
    private val api: KakaoApi,
    private val cache: BranchCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val client by lazy { LocationServices.getGeofencingClient(ctx) }

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(ctx, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION)
        // Geofencing fills in the event extras, so the intent must be mutable.
        PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    @SuppressLint("MissingPermission")
    suspend fun sync(here: Coords?): SyncResult = lock.withLock {
        val r = buildWindow(here, repo.places.value, repo.coupons.value, api, cache, clock())
        repo.saveWindow(r.state)
        if (!hasFineLocation(ctx) || !hasBackgroundLocation(ctx)) {
            return@withLock SyncResult(r.plan, false, "위치 권한이 \"항상 허용\"이 아니에요")
        }
        try {
            client.removeGeofences(pendingIntent).await()
            if (r.plan.regions.isNotEmpty()) {
                val fences = r.plan.regions.map {
                    Geofence.Builder()
                        .setRequestId(it.identifier)
                        .setCircularRegion(it.latitude, it.longitude, it.radius.toFloat())
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(
                            (if (it.notifyOnEnter) Geofence.GEOFENCE_TRANSITION_ENTER else 0) or
                                (if (it.notifyOnExit) Geofence.GEOFENCE_TRANSITION_EXIT else 0),
                        )
                        .build()
                }
                // INITIAL_TRIGGER_ENTER: already standing inside a saved place's radius still alerts.
                val request = GeofencingRequest.Builder()
                    .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                    .addGeofences(fences)
                    .build()
                client.addGeofences(request, pendingIntent).await()
            }
            SyncResult(r.plan, true, null)
        } catch (e: Exception) {
            // Error 1000 = "Google Location Accuracy" off: the OS drops every geofence.
            SyncResult(r.plan, false, e.message ?: e.javaClass.simpleName)
        }
    }
}
