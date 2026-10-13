package com.capstone.snapspot.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.location.GeofencingEvent

/** OS geofence events (the app may be closed). Hands the work to [GeofenceWorker] right away. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (ids.isEmpty()) return
        val work = OneTimeWorkRequestBuilder<GeofenceWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    GeofenceWorker.IDS to ids.toTypedArray(),
                    GeofenceWorker.TRANSITION to event.geofenceTransition,
                    // Speed at the moment of the event (m/s); -1 when the fix carried none.
                    GeofenceWorker.SPEED to (event.triggeringLocation?.takeIf { it.hasSpeed() }?.speed ?: -1f),
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }

    companion object {
        const val ACTION = "com.capstone.snapspot.GEOFENCE"
    }
}
