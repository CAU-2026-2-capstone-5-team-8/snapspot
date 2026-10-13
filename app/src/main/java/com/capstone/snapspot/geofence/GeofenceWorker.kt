package com.capstone.snapspot.geofence

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.capstone.snapspot.R
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.REFRESH_ID
import com.capstone.snapspot.core.isBranchRegion
import com.capstone.snapspot.notify.CHANNEL_ID
import com.capstone.snapspot.notify.ensureChannel
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

/** Enter → alert. Leaving the refresh region → find the position and re-plan the window there. */
class GeofenceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as SnapSpotApp).container
        c.repo.reload()
        val ids = inputData.getStringArray(IDS).orEmpty()
        val transition = inputData.getInt(TRANSITION, -1)
        for (id in ids) {
            when {
                id == REFRESH_ID -> if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                    val here = currentPosition() ?: return if (runAttemptCount < 3) Result.retry() else Result.failure()
                    c.geofenceSync.sync(here)
                }
                transition != Geofence.GEOFENCE_TRANSITION_ENTER -> Unit
                isBranchRegion(id) -> c.alerts.onBranchEnter(id)
                else -> c.alerts.onPlaceEnter(id)
            }
        }
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentPosition(): Coords? {
        if (!hasFineLocation(applicationContext)) return null
        val fused = LocationServices.getFusedLocationProviderClient(applicationContext)
        val last = runCatching { fused.lastLocation.await() }.getOrNull()
        val fresh = last?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 60_000_000_000L }
        val loc = fresh ?: runCatching { fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await() }.getOrNull()
        return loc?.let { Coords(it.latitude, it.longitude) }
    }

    /** Only used when an expedited job runs as a foreground service (Android 11 and below). */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle("근처 장소 확인 중")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(4242, n)
    }

    companion object {
        const val IDS = "ids"
        const val TRANSITION = "transition"
    }
}
