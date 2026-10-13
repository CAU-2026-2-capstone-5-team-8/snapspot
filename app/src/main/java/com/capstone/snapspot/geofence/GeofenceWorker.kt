package com.capstone.snapspot.geofence

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.capstone.snapspot.AppContainer
import com.capstone.snapspot.R
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.PendingAlert
import com.capstone.snapspot.core.REFRESH_ID
import com.capstone.snapspot.core.brandKeyOfRegion
import com.capstone.snapspot.core.isBranchRegion
import com.capstone.snapspot.core.isVehicleSpeed
import com.capstone.snapspot.location.currentFix
import com.capstone.snapspot.notify.CHANNEL_ID
import com.capstone.snapspot.notify.ensureChannel
import com.google.android.gms.location.Geofence

/** Enter → alert, or hold it while in a vehicle. Leaving the refresh region → re-plan the window there. */
class GeofenceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as SnapSpotApp).container
        c.repo.reload()
        val ids = inputData.getStringArray(IDS).orEmpty()
        val transition = inputData.getInt(TRANSITION, -1)
        val speed = inputData.getFloat(SPEED, -1f).takeIf { it >= 0f }
        for (id in ids) {
            when {
                id == REFRESH_ID -> if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                    val here = currentFix(applicationContext)?.at ?: return if (runAttemptCount < 3) Result.retry() else Result.failure()
                    c.geofenceSync.sync(here)
                }
                transition != Geofence.GEOFENCE_TRANSITION_ENTER -> Unit
                isVehicleSpeed(speed) && hold(c, id) -> Unit
                isBranchRegion(id) -> c.alerts.onBranchEnter(id)
                else -> c.alerts.onPlaceEnter(id)
            }
        }
        return Result.success()
    }

    /** Passing by in a vehicle: keep the alert for when the user gets off nearby. False if the target is unknown. */
    private suspend fun hold(c: AppContainer, id: String): Boolean {
        val now = System.currentTimeMillis()
        val p = if (isBranchRegion(id)) {
            val b = c.repo.window.value?.branches?.get(id) ?: return false
            PendingAlert(id, brandKeyOfRegion(id), b.latitude, b.longitude, now)
        } else {
            val place = c.repo.places.value.firstOrNull { it.id == id } ?: return false
            PendingAlert(id, id, place.latitude, place.longitude, now)
        }
        c.pending.add(p)
        RecheckWorker.schedule(applicationContext, ExistingWorkPolicy.KEEP)
        return true
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
        const val SPEED = "speed"
    }
}
