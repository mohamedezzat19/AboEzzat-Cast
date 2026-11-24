package com.aboezzat.share;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.MimeTypeMap;

import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import fi.iki.elonen.NanoHTTPD;

public class CastKeepAliveService extends Service {
    private static final String CHANNEL_ID = "aboezzat_cast_background";
    private static final int NOTIFICATION_ID = 8401;
    private static final long REFRESH_INTERVAL_MS = 15000L;
    private static final String PREFS_NAME = "AboEzzatSharePrefs";
    private static final String PREF_MEDIA_URI = "local_server_media_uri";
    private static volatile Uri selectedMediaUri;
    private static volatile String mediaAccessToken = "";
    private static final SecureRandom MEDIA_TOKEN_RANDOM = new SecureRandom();
    private LocalMediaServer localServer;
    private boolean localServerReady;

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

    public static synchronized void setSelectedMediaUri(Context context, Uri uri) {
        selectedMediaUri = uri;
        mediaAccessToken = uri == null ? "" : createMediaAccessToken();
        SharedPreferences.Editor edit = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS).edit();
        if (uri == null) edit.remove(PREF_MEDIA_URI);
        else edit.putString(PREF_MEDIA_URI, uri.toString());
        edit.apply();
    }

    public static String getMediaAccessToken() {
        return mediaAccessToken;
    }

    private static String createMediaAccessToken() {
        byte[] token = new byte[32];
        MEDIA_TOKEN_RANDOM.nextBytes(token);
        return android.util.Base64.encodeToString(token,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP
                        | android.util.Base64.NO_PADDING);
    }

    private static boolean isValidMediaToken(String supplied) {
        String expected = mediaAccessToken;
        if (expected == null || expected.length() != 43
                || supplied == null || supplied.length() != 43) return false;
        return MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE | MODE_MULTI_PROCESS)
                .getString(PREF_MEDIA_URI, null);
        if (saved != null) {
            try {
                selectedMediaUri = Uri.parse(saved);
                mediaAccessToken = createMediaAccessToken();
            } catch (Exception ignored) {
                selectedMediaUri = null;
                mediaAccessToken = "";
            }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        startLocalServer();
        handler.removeCallbacks(refreshNotification);
        handler.postDelayed(refreshNotification, REFRESH_INTERVAL_MS);
        return START_STICKY;
    }

    private void startLocalServer() {
        if (localServer != null) return;
        try {
            localServer = new LocalMediaServer(8088);
            // TLS-only listener: do not expose plaintext HTTP if identity setup fails.
            localServer.makeSecure(CastTlsIdentity.getServerSocketFactory(),
                    new String[]{"TLSv1.2"});
            localServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            localServerReady = true;
        } catch (Exception e) {
            localServerReady = false;
            android.util.Log.e("AboEzzatCast", "Could not start background media server", e);
            if (localServer != null) {
                try { localServer.stop(); } catch (Exception ignored) {}
                localServer = null;
            }
        }
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
                ? "إمكانية الوصول تعمل" : "إمكانية الوصول متوقفة";
        String server = localServerReady ? "بث الوسائط جاهز" : "خادم بث الوسائط غير جاهز";
        String vpn = isVpnActive() ? " • VPN متصل" : "";
        String status = "التطبيق يعمل في الخلفية • " + accessibility
                + " • " + server + vpn;

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

    private String getMediaDisplayName(Uri uri) {
        if (uri == null) return null;
        String name = uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) return null;
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private long queryMediaLength(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) return cursor.getLong(column);
            }
        } catch (Exception e) {
            android.util.Log.w("AboEzzatCast", "Could not query local media size", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1L;
    }

    private class LocalMediaServer extends NanoHTTPD {
        LocalMediaServer(int port) { super(port); }

        @Override public Response serve(IHTTPSession session) {
            String requestPath = session.getUri();
            if (!"/media.mp4".equals(requestPath) && !"/media.jpg".equals(requestPath)) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
            }
            String suppliedToken = session.getParms().get("token");
            if (!isValidMediaToken(suppliedToken)) {
                Response denied = newFixedLengthResponse(Response.Status.FORBIDDEN,
                        "text/plain", "Access denied");
                denied.addHeader("Cache-Control", "no-store, max-age=0");
                denied.addHeader("Pragma", "no-cache");
                denied.addHeader("X-Content-Type-Options", "nosniff");
                return denied;
            }
            final Uri mediaUri = selectedMediaUri;
            if (mediaUri == null) return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "text/plain", "No media selected");

            AssetFileDescriptor afd = null;
            try {
                afd = getContentResolver().openAssetFileDescriptor(mediaUri, "r");
                if (afd == null) return newFixedLengthResponse(Response.Status.NOT_FOUND,
                        "text/plain", "Media file unavailable");

                long fileLength = afd.getLength();
                if (fileLength < 0) fileLength = queryMediaLength(mediaUri);
                if (fileLength <= 0) {
                    afd.close();
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                            "text/plain", "Unable to determine media length");
                }

                String mime = getContentResolver().getType(mediaUri);
                if (mime == null || mime.trim().isEmpty()
                        || "application/octet-stream".equalsIgnoreCase(mime)) {
                    String name = getMediaDisplayName(mediaUri);
                    if (name != null) {
                        int dot = name.lastIndexOf('.');
                        if (dot >= 0 && dot < name.length() - 1) {
                            String guessed = MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
                            if (guessed != null) mime = guessed;
                        }
                    }
                }
                if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";

                long start = 0L, end = fileLength - 1L;
                boolean partial = false;
                String range = session.getHeaders().get("range");
                if (range != null && !range.trim().isEmpty()) {
                    try {
                        if (!range.startsWith("bytes=")) throw new IllegalArgumentException("Unsupported range");
                        String spec = range.substring(6).trim();
                        int comma = spec.indexOf(',');
                        if (comma >= 0) spec = spec.substring(0, comma).trim();
                        int dash = spec.indexOf('-');
                        if (dash < 0) throw new IllegalArgumentException("Malformed range");
                        String left = spec.substring(0, dash).trim();
                        String right = spec.substring(dash + 1).trim();
                        if (left.isEmpty()) {
                            long suffix = Long.parseLong(right);
                            if (suffix <= 0) throw new IllegalArgumentException("Invalid suffix range");
                            start = Math.max(0L, fileLength - suffix);
                            end = fileLength - 1L;
                        } else {
                            start = Long.parseLong(left);
                            end = right.isEmpty() ? fileLength - 1L : Long.parseLong(right);
                        }
                        if (start < 0 || start >= fileLength || end < start)
                            throw new IllegalArgumentException("Range outside file");
                        end = Math.min(end, fileLength - 1L);
                        partial = true;
                    } catch (Exception invalidRange) {
                        try { afd.close(); } catch (IOException ignored) {}
                        Response response = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE,
                                "text/plain", "");
                        response.addHeader("Content-Range", "bytes */" + fileLength);
                        response.addHeader("Accept-Ranges", "bytes");
                        return response;
                    }
                }

                long contentLength = end - start + 1L;
                final AssetFileDescriptor responseAfd = afd;
                FileInputStream rawStream = afd.createInputStream();
                java.io.InputStream stream = new java.io.FilterInputStream(rawStream) {
                    @Override public void close() throws IOException {
                        try { super.close(); }
                        finally { try { responseAfd.close(); } catch (IOException ignored) {} }
                    }
                };
                long toSkip = start;
                while (toSkip > 0) {
                    long skipped = stream.skip(toSkip);
                    if (skipped <= 0) {
                        if (stream.read() == -1) throw new IOException("Cannot seek to requested range");
                        skipped = 1;
                    }
                    toSkip -= skipped;
                }

                Response response = newFixedLengthResponse(
                        partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                        mime, stream, contentLength);
                response.addHeader("Content-Length", String.valueOf(contentLength));
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("Cache-Control", "no-store, max-age=0");
                response.addHeader("Pragma", "no-cache");
                response.addHeader("X-Content-Type-Options", "nosniff");
                if (partial) response.addHeader("Content-Range",
                        "bytes " + start + "-" + end + "/" + fileLength);
                return response;
            } catch (Exception e) {
                if (afd != null) {
                    try { afd.close(); } catch (IOException ignored) {}
                }
                android.util.Log.e("AboEzzatCast", "Failed to serve local media", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                        "Error loading local media");
            }
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Keep the user-visible foreground session alive when the launcher task is swiped away.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(refreshNotification);
        if (localServer != null) {
            try { localServer.stop(); } catch (Exception ignored) {}
            localServer = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
