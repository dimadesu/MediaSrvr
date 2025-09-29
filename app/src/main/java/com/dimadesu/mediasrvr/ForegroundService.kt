package com.dimadesu.mediasrvr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "media_srvr_foreground_channel"
        const val NOTIF_ID = 9245
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Media Srvr")
            .setContentText("Server is running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
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

    override fun onDestroy() {
        // Use STOP_FOREGROUND_REMOVE for clarity with newer APIs
        try {
            @Suppress("Deprecation")
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // Fallback to deprecated boolean form if constant unavailable
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Media Srvr Foreground Service", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keeps Media Srvr running in foreground"
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }
}
