package com.yourorg.sample;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class NodeForegroundService extends Service {
    public static final String CHANNEL_ID = "node_foreground_channel";
    public static final int NOTIF_ID = 9245;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Node Media Server")
                .setContentText("Node Media Server is running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Service will remain until explicitly stopped
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Node Foreground Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps Node Media Server running in foreground");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
