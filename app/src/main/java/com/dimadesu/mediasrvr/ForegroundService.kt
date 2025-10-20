package com.dimadesu.mediasrvr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder

class ForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "media_srvr_foreground_channel"
        const val NOTIF_ID = 9245
        const val ACTION_STOP = "com.dimadesu.mediasrvr.action.STOP"
        private const val TAG = "ForegroundService"
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

        // Create an action that stops the foreground service when clicked
        val stopIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RTMP server status:")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop RTMP server", stopPendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)

        // Acquire wake/wifi locks so the native server keeps running reliably
        acquireWakeLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle the Stop action from the notification
        if (intent?.action == ACTION_STOP) {
            // release locks before stopping
            releaseWakeLocks()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                stopForeground(true)
            }
            stopSelf()
            // Kill the process to ensure native Node instance is terminated.
            try {
                Process.killProcess(Process.myPid())
            } catch (e: Exception) {
                // fallback to exit
                kotlin.system.exitProcess(0)
            }
            return START_NOT_STICKY
        }

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
        // ensure locks are released if service is destroyed
        releaseWakeLocks()
        super.onDestroy()
    }

    // WakeLock and WifiLock handling
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireWakeLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                // Use PARTIAL_WAKE_LOCK with indefinite timeout to keep CPU running even when screen is off
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "mediasrvr:node_wakelock"
                )
                // rely on explicit releaseWakeLocks() instead of a timeout; make non-reference-counted
                wakeLock?.setReferenceCounted(false)
                // Acquire without timeout to keep running indefinitely
                @Suppress("DEPRECATION")
                wakeLock?.acquire()
                Log.i(TAG, "acquired partial wakelock with ACQUIRE_CAUSES_WAKEUP (explicit release required)")
            }

            // Optional Wi‑Fi lock to keep Wi‑Fi radio active (requires CHANGE_WIFI_STATE)
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.let {
                if (wifiLock?.isHeld != true) {
                    // WIFI_MODE_FULL_HIGH_PERF is preferable for best throughput on supported devices
                    wifiLock = it.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mediasrvr:wifi_lock")
                    wifiLock?.setReferenceCounted(false)
                    wifiLock?.acquire()
                    Log.i(TAG, "acquired wifi lock")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to acquire wake/wifi locks: ${e.message}")
        }
    }

    private fun releaseWakeLocks() {
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to release wifi lock: ${e.message}")
        } finally {
            wifiLock = null
        }

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to release wakelock: ${e.message}")
        } finally {
            wakeLock = null
        }
        Log.i(TAG, "released wake/wifi locks")
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
