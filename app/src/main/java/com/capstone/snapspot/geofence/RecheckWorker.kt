package com.capstone.snapspot.geofence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.PendingDecision
import com.capstone.snapspot.core.RECHECK_DELAY_MS
import com.capstone.snapspot.core.decidePending
import com.capstone.snapspot.core.isBranchRegion
import com.capstone.snapspot.location.currentFix
import java.util.concurrent.TimeUnit

/** Every 3 minutes while alerts are held: alert those the user got off near, drop the stale ones. */
class RecheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as SnapSpotApp).container
        c.repo.reload()
        val held = c.pending.all()
        if (held.isEmpty()) return Result.success()
        val fix = currentFix(applicationContext)
        val now = System.currentTimeMillis()
        val done = mutableSetOf<String>()
        for (p in held) {
            when (decidePending(p, fix, now)) {
                PendingDecision.ALERT -> {
                    // Whatever AlertService answers (cooldown, coupon used, app on screen), this one is settled.
                    if (isBranchRegion(p.regionId)) c.alerts.onBranchEnter(p.regionId) else c.alerts.onPlaceEnter(p.regionId)
                    done += p.group
                }
                PendingDecision.DROP -> done += p.group
                PendingDecision.RECHECK -> Unit
            }
        }
        c.pending.remove(done)
        // Re-read: an alert may have been held while this ran.
        if (c.pending.all().isNotEmpty()) schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "recheck"

        /** KEEP from a new hold (one check is already coming); APPEND_OR_REPLACE from the worker itself (it is still running). */
        fun schedule(ctx: Context, policy: ExistingWorkPolicy) {
            val work = OneTimeWorkRequestBuilder<RecheckWorker>()
                .setInitialDelay(RECHECK_DELAY_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, policy, work)
        }
    }
}
