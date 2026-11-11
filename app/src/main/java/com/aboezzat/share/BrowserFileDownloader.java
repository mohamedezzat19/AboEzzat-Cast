package com.aboezzat.share;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.WebSettings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class BrowserFileDownloader {
    private static final String PREFS = "AboEzzatSharePrefs";
    private static final String KEY = "in_app_download_items";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private BrowserFileDownloader() {}

    static void start(Activity activity, String url, String type, String hintedMime, String referer) {
        if (activity == null || url == null || url.trim().isEmpty()) {
            if (activity != null) Toast.makeText(activity, "رابط التنزيل غير صالح", Toast.LENGTH_LONG).show();
            return;
        }
        final String target = url.trim();
        if (!(target.startsWith("http://") || target.startsWith("https://"))) {
            Toast.makeText(activity, "المتصفح لا يستطيع تنزيل هذا النوع من الروابط مباشرة", Toast.LENGTH_LONG).show();
            return;
        }
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".flv") || lower.contains("pull-")) {
            Toast.makeText(activity, "هذا بث مباشر/متكيف؛ يحتاج تنزيل المقاطع ودمجها، وليس ملفاً عادياً", Toast.LENGTH_LONG).show();
            return;
        }

        String extension = extensionFromUrl(target);
        String mime = normalizeMime(hintedMime, extension, type);
        if (extension.isEmpty()) extension = extensionForMime(mime, type);
        String fileName = "AboEzzat_" + System.currentTimeMillis() + extension;
        long id = System.currentTimeMillis();
        String title = "image".equals(type) ? "صورة" : "audio".equals(type) ? "ملف صوتي" : "فيديو";
        JSONObject item = new JSONObject();
        try {
            item.put("id", id);
            item.put("title", title);
            item.put("file", fileName);
            item.put("type", type);
            item.put("mime", mime);
            item.put("url", target);
            item.put("status", "downloading");
            item.put("bytes", 0L);
            item.put("total", -1L);
            item.put("progress", 0);
            item.put("created", System.currentTimeMillis());
            saveItem(activity, item);
        } catch (Exception ignored) {}

        Toast.makeText(activity, "بدأ تنزيل الملف داخل متصفح AboEzzat", Toast.LENGTH_SHORT).show();
        final String finalName = fileName;
        final String finalMime = mime;
        final long recordId = id;
        final String finalType = type;
        final String finalReferer = referer == null ? "" : referer;
        final String cookie = CookieManager.getInstance().getCookie(target);
        final String userAgent = WebSettings.getDefaultUserAgent(activity);
        final Context app = activity.getApplicationContext();

        AsyncTask.execute(() -> {
            Uri destination = null;
            boolean published = false;
            long received = 0L;
            try {
                Request.Builder requestBuilder = new Request.Builder()
                        .url(target)
                        .get()
                        .header("User-Agent", userAgent)
                        .header("Accept", "*/*")
                        .header("Accept-Encoding", "identity");
                if (cookie != null && !cookie.isEmpty()) requestBuilder.header("Cookie", cookie);
                if (!finalReferer.isEmpty() && (finalReferer.startsWith("http://") || finalReferer.startsWith("https://"))) {
                    requestBuilder.header("Referer", finalReferer);
                }

                try (Response response = CLIENT.newCall(requestBuilder.build()).execute()) {
                    if (!response.isSuccessful()) throw new java.io.IOException("HTTP " + response.code());
                    ResponseBody body = response.body();
                    if (body == null) throw new java.io.IOException("السيرفر أرسل استجابة فارغة");
                    String responseMime = body.contentType() == null ? "" : body.contentType().toString().toLowerCase(Locale.ROOT);
                    if (responseMime.startsWith("text/html") || responseMime.startsWith("application/json")
                            || responseMime.startsWith("text/plain") && body.contentLength() < 1024) {
                        throw new java.io.IOException("الموقع أرسل صفحة ويب بدلاً من ملف الوسائط؛ قد يحتاج تسجيل دخول أو رابطاً صالحاً");
                    }
                    long total = body.contentLength();
                    destination = createDestination(app, finalName, finalMime);
                    if (destination == null) throw new java.io.IOException("تعذر إنشاء ملف في مجلد التنزيلات");

                    try (InputStream input = body.byteStream();
                         OutputStream output = app.getContentResolver().openOutputStream(destination, "w")) {
                        if (output == null) throw new java.io.IOException("تعذر فتح ملف التنزيل للكتابة");
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        long lastUpdate = 0L;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                            received += count;
                            long now = System.currentTimeMillis();
                            if (now - lastUpdate >= 500) {
                                updateProgress(app, recordId, received, total);
                                lastUpdate = now;
                            }
                        }
                        output.flush();
                    }

                    if (received <= 0) throw new java.io.IOException("الملف المستلم حجمه صفر بايت");
                    if (total > 0 && received != total) {
                        throw new java.io.IOException("التنزيل غير مكتمل: وصل " + received + " من " + total + " بايت");
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues ready = new ContentValues();
                        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        app.getContentResolver().update(destination, ready, null, null);
                    }
                    published = true;
                    updateComplete(app, recordId, destination, received);
                    Uri finalUri = destination;
                    long finalBytes = received;
                    if (activity != null) activity.runOnUiThread(() ->
                            Toast.makeText(activity, "اكتمل التنزيل ✓ (" + formatBytes(finalBytes) + ")", Toast.LENGTH_LONG).show());
                }
            } catch (Exception error) {
                if (destination != null && !published) {
                    try { app.getContentResolver().delete(destination, null, null); } catch (Exception ignored) {}
                }
                updateFailed(app, recordId, error.getMessage() == null ? "فشل التنزيل" : error.getMessage());
                String message = error.getMessage() == null ? "فشل التنزيل" : error.getMessage();
                if (activity != null) activity.runOnUiThread(() ->
                        Toast.makeText(activity, "لم يكتمل التنزيل: " + message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static Uri createDestination(Context context, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AboEzzat Cast");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            return context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        }
        java.io.File folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (folder == null) throw new java.io.IOException("مجلد التنزيلات غير متاح");
        if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد التنزيلات");
        java.io.File file = new java.io.File(folder, name);
        return Uri.fromFile(file);
    }

    private static void updateProgress(Context context, long id, long bytes, long total) {
        JSONObject item = findItem(context, id);
        if (item == null) return;
        try {
            item.put("bytes", bytes);
            item.put("total", total);
            item.put("progress", total > 0 ? (int) Math.min(99, bytes * 100L / total) : -1);
            item.put("status", "downloading");
            saveItem(context, item);
        } catch (Exception ignored) {}
    }

    private static void updateComplete(Context context, long id, Uri uri, long bytes) {
        JSONObject item = findItem(context, id);
        if (item == null) return;
        try {
            item.put("uri", uri.toString());
            item.put("bytes", bytes);
            item.put("total", bytes);
            item.put("progress", 100);
            item.put("status", "complete");
            saveItem(context, item);
        } catch (Exception ignored) {}
    }

    private static void updateFailed(Context context, long id, String error) {
        JSONObject item = findItem(context, id);
        if (item == null) return;
        try {
            item.put("status", "failed");
            item.put("error", error);
            item.put("progress", 0);
            saveItem(context, item);
        } catch (Exception ignored) {}
    }

    private static synchronized JSONObject findItem(Context context, long id) {
        JSONArray items = readItems(context);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optLong("id", -1) == id) return item;
        }
        return null;
    }

    private static synchronized void saveItem(Context context, JSONObject updated) {
        JSONArray old = readItems(context);
        JSONArray next = new JSONArray();
        boolean found = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null) continue;
            if (item.optLong("id", -1) == updated.optLong("id", -2)) {
                next.put(updated);
                found = true;
            } else next.put(item);
        }
        if (!found) next.put(updated);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.toString()).apply();
    }

    private static JSONArray readItems(Context context) {
        try { return new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    static void deleteFile(Context context, JSONObject item) {
        String value = item == null ? "" : item.optString("uri", "");
        if (!value.isEmpty()) {
            try { context.getContentResolver().delete(Uri.parse(value), null, null); } catch (Exception ignored) {}
        }
    }

    private static String extensionFromUrl(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (path == null) return "";
            int dot = path.lastIndexOf('.');
            if (dot < 0) return "";
            String ext = path.substring(dot).toLowerCase(Locale.ROOT);
            return ext.matches("\\.[a-z0-9]{2,7}") ? ext : "";
        } catch (Exception ignored) { return ""; }
    }

    private static String normalizeMime(String hinted, String ext, String type) {
        if (hinted != null && !hinted.trim().isEmpty() && !hinted.contains("*")) {
            int semi = hinted.indexOf(';');
            return (semi >= 0 ? hinted.substring(0, semi) : hinted).trim().toLowerCase(Locale.ROOT);
        }
        if (!ext.isEmpty()) {
            String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.substring(1));
            if (guessed != null) return guessed;
        }
        return "image".equals(type) ? "image/jpeg" : "audio".equals(type) ? "audio/mpeg" : "video/mp4";
    }

    private static String extensionForMime(String mime, String type) {
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (ext != null && !ext.isEmpty()) return "." + ext;
        return "image".equals(type) ? ".jpg" : "audio".equals(type) ? ".mp3" : ".mp4";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
