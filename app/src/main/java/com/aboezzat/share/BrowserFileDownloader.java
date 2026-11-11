package com.aboezzat.share;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
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
        String mime;
        if ("audio".equals(type) && (extension.isEmpty() || ".mp4".equals(extension))) {
            mime = hintedMime != null && hintedMime.toLowerCase(Locale.ROOT).startsWith("audio/")
                    ? normalizeMime(hintedMime, "", type) : "audio/mp4";
            extension = extensionForMime(mime, type);
            if (extension.isEmpty() || ".mp4".equals(extension)) extension = ".m4a";
        } else {
            mime = normalizeMime(hintedMime, extension, type);
            if (extension.isEmpty()) extension = extensionForMime(mime, type);
        }
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


    /**
     * Downloads the selected video and separate audio rendition, then muxes them
     * into one MP4 without re-encoding. Unsupported codecs/containers fail clearly.
     */
    static void startMux(Activity activity, String videoUrl, String audioUrl, String referer, String title) {
        if (activity == null || videoUrl == null || videoUrl.trim().isEmpty()
                || audioUrl == null || audioUrl.trim().isEmpty()) {
            if (activity != null) Toast.makeText(activity, "حدد مسار فيديو ومسار صوت أولاً", Toast.LENGTH_LONG).show();
            return;
        }
        final String video = videoUrl.trim();
        final String audio = audioUrl.trim();
        if (!isHttpUrl(video) || !isHttpUrl(audio)) {
            Toast.makeText(activity, "رابط الفيديو أو الصوت غير صالح", Toast.LENGTH_LONG).show();
            return;
        }
        if (isAdaptiveOrLive(video) || isAdaptiveOrLive(audio)) {
            Toast.makeText(activity, "المسارات المباشرة أو المتكيفة تحتاج معالجة مقاطع خاصة؛ لم يبدأ تنزيل ملف ناقص", Toast.LENGTH_LONG).show();
            return;
        }

        final long recordId = System.currentTimeMillis();
        final String fileName = "AboEzzat_" + recordId + "_merged.mp4";
        final String safeTitle = title == null || title.trim().isEmpty() ? "فيديو بصوت وصورة" : title.trim();
        JSONObject record = new JSONObject();
        try {
            record.put("id", recordId);
            record.put("title", safeTitle);
            record.put("file", fileName);
            record.put("type", "video");
            record.put("mime", "video/mp4");
            record.put("url", video);
            record.put("audio_url", audio);
            record.put("status", "downloading");
            record.put("bytes", 0L);
            record.put("total", -1L);
            record.put("progress", 0);
            record.put("created", System.currentTimeMillis());
            record.put("operation", "mux");
            saveItem(activity, record);
        } catch (Exception ignored) {}

        Toast.makeText(activity, "بدأ تنزيل مسار الفيديو والصوت ودمجهما في ملف واحد", Toast.LENGTH_LONG).show();
        final Context app = activity.getApplicationContext();
        final String pageReferer = referer == null ? "" : referer;
        final String userAgent = WebSettings.getDefaultUserAgent(activity);
        AsyncTask.execute(() -> {
            File videoTemp = null;
            File audioTemp = null;
            File mergedTemp = null;
            Uri destination = null;
            boolean published = false;
            try {
                File work = new File(app.getCacheDir(), "media_mux");
                if (!work.exists() && !work.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد العمل المؤقت");
                videoTemp = File.createTempFile("video_", ".bin", work);
                audioTemp = File.createTempFile("audio_", ".bin", work);
                mergedTemp = File.createTempFile("merged_", ".mp4", work);

                long videoBytes = downloadToTemp(video, videoTemp, pageReferer, userAgent);
                updateProgress(app, recordId, videoBytes, -1L);
                long audioBytes = downloadToTemp(audio, audioTemp, pageReferer, userAgent);
                updateProgress(app, recordId, videoBytes + audioBytes, -1L);

                muxTracks(videoTemp, audioTemp, mergedTemp);
                long mergedBytes = mergedTemp.length();
                if (mergedBytes <= 0) throw new java.io.IOException("ملف الدمج الناتج فارغ");

                destination = createDestination(app, fileName, "video/mp4");
                if (destination == null) throw new java.io.IOException("تعذر إنشاء ملف MP4 في التنزيلات");
                try (InputStream input = new FileInputStream(mergedTemp);
                     OutputStream output = openDestination(app, destination)) {
                    if (output == null) throw new java.io.IOException("تعذر فتح ملف MP4 النهائي");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    long copied = 0L;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        copied += count;
                        if (copied % (1024 * 1024) < buffer.length) updateProgress(app, recordId, copied, mergedBytes);
                    }
                    output.flush();
                    if (copied != mergedBytes) throw new java.io.IOException("نسخ ملف MP4 النهائي لم يكتمل");
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    app.getContentResolver().update(destination, ready, null, null);
                }
                published = true;
                updateComplete(app, recordId, destination, mergedBytes);
                if (activity != null) {
                    long done = mergedBytes;
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "اكتمل دمج الفيديو والصوت في ملف واحد ✓ (" + formatBytes(done) + ")",
                            Toast.LENGTH_LONG).show());
                }
            } catch (Exception error) {
                if (destination != null && !published) {
                    try { app.getContentResolver().delete(destination, null, null); } catch (Exception ignored) {}
                    if ("file".equalsIgnoreCase(destination.getScheme())) {
                        try { new File(destination.getPath()).delete(); } catch (Exception ignored) {}
                    }
                }
                String message = error.getMessage() == null ? "فشل تنزيل أو دمج المسارات" : error.getMessage();
                updateFailed(app, recordId, message);
                if (activity != null) activity.runOnUiThread(() ->
                        Toast.makeText(activity, "تعذر إنشاء فيديو بصوت وصورة: " + message, Toast.LENGTH_LONG).show());
            } finally {
                if (videoTemp != null) videoTemp.delete();
                if (audioTemp != null) audioTemp.delete();
                if (mergedTemp != null) mergedTemp.delete();
            }
        });
    }

    private static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static boolean isAdaptiveOrLive(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".flv") || lower.contains("pull-");
    }

    private static long downloadToTemp(String url, File target, String referer, String userAgent) throws Exception {
        String cookie = CookieManager.getInstance().getCookie(url);
        Request.Builder builder = new Request.Builder().url(url).get()
                .header("User-Agent", userAgent).header("Accept", "*/*")
                .header("Accept-Encoding", "identity");
        if (cookie != null && !cookie.isEmpty()) builder.header("Cookie", cookie);
        if (isHttpUrl(referer)) builder.header("Referer", referer);
        try (Response response = CLIENT.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) throw new java.io.IOException("HTTP " + response.code() + " أثناء تنزيل أحد المسارات");
            ResponseBody body = response.body();
            if (body == null) throw new java.io.IOException("استجابة مسار الوسائط فارغة");
            String mime = body.contentType() == null ? "" : body.contentType().toString().toLowerCase(Locale.ROOT);
            if (mime.startsWith("text/html") || mime.startsWith("application/json")
                    || mime.startsWith("text/plain") && body.contentLength() >= 0 && body.contentLength() < 1024) {
                throw new java.io.IOException("الموقع أعاد صفحة ويب بدلاً من مسار الوسائط؛ تحقق من تسجيل الدخول");
            }
            long received = 0L;
            try (InputStream input = body.byteStream(); OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    received += count;
                }
                output.flush();
            }
            if (received <= 0) throw new java.io.IOException("أحد مسارات الوسائط وصل فارغاً");
            if (body.contentLength() > 0 && received != body.contentLength()) {
                throw new java.io.IOException("أحد مسارات الوسائط لم يكتمل تنزيله");
            }
            return received;
        }
    }

    private static void muxTracks(File videoFile, File audioFile, File outputFile) throws Exception {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            audioExtractor.setDataSource(audioFile.getAbsolutePath());
            int videoIndex = findTrack(videoExtractor, "video/");
            int audioIndex = findTrack(audioExtractor, "audio/");
            if (videoIndex < 0) throw new java.io.IOException("الرابط المحدد لا يحتوي على مسار فيديو صالح");
            if (audioIndex < 0) throw new java.io.IOException("الرابط المحدد لا يحتوي على مسار صوت صالح");

            videoExtractor.selectTrack(videoIndex);
            audioExtractor.selectTrack(audioIndex);
            MediaFormat videoFormat = videoExtractor.getTrackFormat(videoIndex);
            MediaFormat audioFormat = audioExtractor.getTrackFormat(audioIndex);
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOut = muxer.addTrack(videoFormat);
            int audioOut = muxer.addTrack(audioFormat);
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                try { muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION)); } catch (Exception ignored) {}
            }
            muxer.start();
            muxerStarted = true;

            int videoCapacity = sampleCapacity(videoFormat);
            int audioCapacity = sampleCapacity(audioFormat);
            ByteBuffer videoBuffer = ByteBuffer.allocateDirect(videoCapacity);
            ByteBuffer audioBuffer = ByteBuffer.allocateDirect(audioCapacity);
            MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
            long videoTime = videoExtractor.getSampleTime();
            long audioTime = audioExtractor.getSampleTime();
            while (videoTime >= 0 || audioTime >= 0) {
                if (audioTime < 0 || (videoTime >= 0 && videoTime <= audioTime)) {
                    videoBuffer.clear();
                    int size = videoExtractor.readSampleData(videoBuffer, 0);
                    if (size < 0) {
                        videoTime = -1L;
                        continue;
                    }
                    videoInfo.set(0, size, Math.max(0L, videoTime), videoExtractor.getSampleFlags());
                    muxer.writeSampleData(videoOut, videoBuffer, videoInfo);
                    videoExtractor.advance();
                    videoTime = videoExtractor.getSampleTime();
                } else {
                    audioBuffer.clear();
                    int size = audioExtractor.readSampleData(audioBuffer, 0);
                    if (size < 0) {
                        audioTime = -1L;
                        continue;
                    }
                    audioInfo.set(0, size, Math.max(0L, audioTime), audioExtractor.getSampleFlags());
                    muxer.writeSampleData(audioOut, audioBuffer, audioInfo);
                    audioExtractor.advance();
                    audioTime = audioExtractor.getSampleTime();
                }
            }
            muxer.stop();
            muxerStarted = false;
            if (outputFile.length() <= 0) throw new java.io.IOException("فشل إنشاء ملف MP4 من المسارين");
        } finally {
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Exception ignored) {}
                }
                try { muxer.release(); } catch (Exception ignored) {}
            }
            try { videoExtractor.release(); } catch (Exception ignored) {}
            try { audioExtractor.release(); } catch (Exception ignored) {}
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.containsKey(MediaFormat.KEY_MIME) ? format.getString(MediaFormat.KEY_MIME) : null;
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int sampleCapacity(MediaFormat format) {
        int size = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 1024 * 1024;
        return Math.max(1024 * 1024, Math.min(size, 16 * 1024 * 1024));
    }

    private static OutputStream openDestination(Context context, Uri destination) throws Exception {
        if ("file".equalsIgnoreCase(destination.getScheme())) {
            return new FileOutputStream(new File(destination.getPath()));
        }
        return context.getContentResolver().openOutputStream(destination, "w");
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
        if (hinted != null && !hinted.trim().isEmpty() && !hinted.contains("*")
                && !hinted.equalsIgnoreCase("application/octet-stream")) {
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
