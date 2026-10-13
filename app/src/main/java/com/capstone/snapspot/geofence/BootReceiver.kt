package com.capstone.snapspot.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.capstone.snapspot.SnapSpotApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The OS forgets geofences on reboot and app update: register them again around the last known plan position. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val c = (context.applicationContext as SnapSpotApp).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                c.geofenceSync.sync(c.repo.window.value?.here)
            } finally {
                pending.finish()
            }
        }
    }
}
