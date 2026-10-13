package com.capstone.snapspot.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.capstone.snapspot.MainActivity
import com.capstone.snapspot.R

const val CHANNEL_ID = "nearby-places"

fun ensureChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(CHANNEL_ID, "근처 저장 장소 알림", NotificationManager.IMPORTANCE_HIGH).apply {
        vibrationPattern = longArrayOf(0, 250, 250, 250)
        enableVibration(true)
    }
    ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

fun notificationsAllowed(ctx: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

class AndroidNotifier(private val ctx: Context) : Notifier {
    override fun show(id: Int, title: String, body: String, deepLink: String) {
        ensureChannel(ctx)
        if (!notificationsAllowed(ctx)) return
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink), ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_icon)
            .setColor(0xFF2D7A57.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }
}
