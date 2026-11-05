package com.aboezzat.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdater {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String RELEASES_API = "https://api.github.com/repos/mohamedezzat19/AboEzzat-Cast/releases/latest";
    private final Activity activity;

    public AppUpdater(Activity activity) { this.activity = activity; }

    public void checkForUpdate(boolean showResult) {
        IO.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "AboEzzat-Cast-Updater");
                JSONObject release;
                try (InputStream in = c.getInputStream();
                     java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    release = new JSONObject(out.toString("UTF-8"));
                } finally {
                    c.disconnect();
                }

                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("versionCode\\s*:\\s*(\\d+)")
                        .matcher(release.optString("body", ""));
                long latest = m.find() ? Long.parseLong(m.group(1)) : 0;
                long installed = installedVersionCode();
                String apkUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a != null && "app-release.apk".equals(a.optString("name"))) {
                            apkUrl = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                final String downloadUrl = apkUrl;
                if (latest <= installed || downloadUrl == null || downloadUrl.isEmpty()) {
                    if (showResult) MAIN.post(() -> Toast.makeText(activity,
                            "التطبيق محدث بالفعل، أو لم تُنشر نسخة موقّعة بعد.",
                            Toast.LENGTH_LONG).show());
                    return;
                }
                MAIN.post(() -> new AlertDialog.Builder(activity)
                        .setTitle("تحديث جديد متاح")
                        .setMessage("يوجد إصدار أحدث رقم " + latest + ". هل تريد تنزيله وتثبيته؟")
                        .setNegativeButton("لاحقًا", null)
                        .setPositiveButton("تحديث", (d, w) -> download(downloadUrl, latest))
                        .show());
            } catch (Exception e) {
                if (showResult) MAIN.post(() -> Toast.makeText(activity,
                        "تعذر التحقق من التحديث. تحقق من الإنترنت وحاول مرة أخرى.",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private long installedVersionCode() {
        try {
            PackageInfo p = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    private void download(String url, long version) {
        IO.execute(() -> {
            File apk = new File(activity.getExternalFilesDir(null), "aboezzat-classic-update-" + version + ".apk");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "AboEzzat-Share-Classic-Updater");
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK)
                    throw new IllegalStateException("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                } finally {
                    c.disconnect();
                }

                PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(
                        apk.getAbsolutePath(), android.content.pm.PackageManager.GET_META_DATA);
                if (archive == null || !activity.getPackageName().equals(archive.packageName))
                    throw new IllegalStateException("Downloaded APK is not the Classic package");
                long downloadedCode = Build.VERSION.SDK_INT >= 28
                        ? archive.getLongVersionCode() : archive.versionCode;
                if (downloadedCode <= installedVersionCode() || downloadedCode < version)
                    throw new IllegalStateException("Downloaded APK is not a newer expected build");
                MAIN.post(() -> install(apk));
            } catch (Exception e) {
                if (apk.exists()) apk.delete();
                android.util.Log.e("AboEzzatUpdater", "Classic update validation failed", e);
                MAIN.post(() -> Toast.makeText(activity,
                        "فشل تنزيل التحديث أو التحقق من ملفه. لم يتم تثبيت شيء.",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void install(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(activity)
                        .setTitle("السماح بالتثبيت")
                        .setMessage("فعّل السماح بتثبيت التطبيقات من هذا المصدر، ثم ارجع واضغط «التحقق من التحديث» مرة أخرى.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("فتح الإعدادات", (d, w) ->
                                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + activity.getPackageName()))))
                        .show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            android.util.Log.e("AboEzzatUpdater", "Could not launch Classic package installer", e);
            Toast.makeText(activity, "تعذر فتح مثبت التحديث.", Toast.LENGTH_LONG).show();
        }
    }
}
