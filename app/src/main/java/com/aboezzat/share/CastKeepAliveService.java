package com.aboezzat.share;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

public class CastKeepAliveService extends Service {
    private static final String CHANNEL_ID = "aboezzat_cast_background";
    private static final int NOTIFICATION_ID = 8401;
    private static final long REFRESH_INTERVAL_MS = 15000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshNotification = new Runnable() {
        @Override public void run() {
            updateNotification();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, CastKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        handler.removeCallbacks(refreshNotification);
        handler.postDelayed(refreshNotification, REFRESH_INTERVAL_MS);
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "تشغيل AboEzzat Cast في الخلفية",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("إشعار مستمر يوضح حالة AboEzzat Cast وخدماته");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = launchIntent == null ? null : PendingIntent.getActivity(
                this, 8402, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String accessibility = isAccessibilityEnabled()
                ? "إمكانية الوصول مفعّلة" : "إمكانية الوصول متوقفة";
        String vpn = isVpnActive() ? " • VPN متصل" : "";
        String status = "AboEzzat Cast يعمل في الخلفية • " + accessibility + vpn;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AboEzzat Cast")
                .setContentText(status)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(status))
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true);
        if (contentIntent != null) builder.setContentIntent(contentIntent);
        return builder.build();
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) return false;
        String target = new ComponentName(this,
                ClipboardShortcutAccessibilityService.class).flattenToString();
        for (String item : enabled.split(":")) {
            if (target.equalsIgnoreCase(item.trim())) return true;
        }
        return false;
    }

    private boolean isVpnActive() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        ConnectivityManager connectivity =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) return false;
        Network network = connectivity.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Deliberately keep the foreground service alive when the app task is swiped away.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(refreshNotification);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
