package com.capstone.snapspot.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.capstone.snapspot.SnapSpotApp

/**
 * The OS forgets geofences on reboot and app update. Re-planning needs Kakao, and the network is
 * often not up yet at boot, so the work waits for a connection instead of running in the receiver.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val work = OneTimeWorkRequestBuilder<ResyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("resync", ExistingWorkPolicy.REPLACE, work)
    }
}

/** Registers the window again around the last planned position. */
class ResyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as SnapSpotApp).container
        c.repo.reload()
        c.geofenceSync.sync(null)
        return Result.success()
    }
}
