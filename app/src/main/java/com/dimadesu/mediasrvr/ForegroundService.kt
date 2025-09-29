package com.dimadesu.mediasrvr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder

class ForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "media_srvr_foreground_channel"
        const val NOTIF_ID = 9245
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Create a PendingIntent so tapping the notification opens MainActivity
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            // If an instance already exists, bring it to foreground instead of creating a new one
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = TaskStackBuilder.create(this).run {
            // Add the back stack for the Intent (so Back works as expected)
            addNextIntentWithParentStack(mainIntent)
            getPendingIntent(0, pendingFlags)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MediaSrvr")
            .setContentText("Server is running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        // Preferred: use STOP_FOREGROUND_REMOVE on newer APIs; keep fallback for compatibility
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MediaSrvr Foreground Service", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keeps MediaSrvr running in foreground"
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }
}
