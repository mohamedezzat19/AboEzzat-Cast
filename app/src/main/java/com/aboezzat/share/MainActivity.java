package com.aboezzat.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends Activity {

    private static final int TARGET_PORT = 8081;
    private static final int PICK_MEDIA_REQUEST = 101;
    private static final int PICK_SLIDESHOW_REQUEST = 102;
    private static final int VOICE_SEARCH_REQUEST = 103;

    private DrawerLayout drawerLayout;
    private EditText etTvIp;
    private EditText etHomeSearch;
    private TextView tvStatusBadge;
    private SharedPreferences prefs;
    private final OkHttpClient client = new OkHttpClient();
    private LocalMediaServer localServer;
    private Uri selectedMediaUri = null;

    private final List<Uri> slideshowQueue = new ArrayList<>();
    private int currentSlideIndex = 0;
    private boolean isSlideshowRunning = false;

    private final List<Uri> videoQueue = new ArrayList<>();
    private int currentVideoIndex = 0;

    private final Handler slideHandler = new Handler(Looper.getMainLooper());
    private final Runnable slideRunnable = new Runnable() {
        @Override
        public void run() {
            if (!slideshowQueue.isEmpty() && isSlideshowRunning) {
                currentSlideIndex = (currentSlideIndex + 1) % slideshowQueue.size();
                selectedMediaUri = slideshowQueue.get(currentSlideIndex);
                castLocalMediaToTv(false);
                slideHandler.postDelayed(this, 5000);
            }
        }
    };

    private boolean clipboardShortcutLaunch;
    private boolean accessibilityReminderShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        clipboardShortcutLaunch = getIntent() != null
                && getIntent().getBooleanExtra("clipboard_cast_shortcut", false);
        if (clipboardShortcutLaunch) setTheme(android.R.style.Theme_Translucent_NoTitleBar);
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        restoreMediaQueues();
        startLocalServer();

        // The shortcut uses a separate translucent task: process the copied link
        // without showing the normal Cast home screen.
        if (handleClipboardCastShortcut(getIntent())) return;

        setContentView(R.layout.activity_main);

        // Silently check the Classic app's own release channel on launch.
        new AppUpdater(this).checkForUpdate(false);

        if (handleIncomingShareIntent(getIntent())) {
            return;
        }

        initViews();
        setupNavigationDrawer();
        setupBottomNavigation();
        applyDarkMode(findViewById(R.id.drawer_layout), prefs.getBoolean("dark_mode", true));
        if (getIntent().getBooleanExtra("open_media_picker", false)) {
            new Handler(Looper.getMainLooper()).post(() -> openMediaPicker(false));
        } else if (getIntent().getBooleanExtra("open_library", false)) {
            new Handler(Looper.getMainLooper()).post(() -> showLibraryDialog());
        }
        else if (getIntent().getBooleanExtra("open_downloads", false)) {
            new Handler(Looper.getMainLooper()).post(() -> openDownloads());
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        etTvIp = findViewById(R.id.et_ip);
        tvStatusBadge = findViewById(R.id.tv_status_badge);
        Button btnSave = findViewById(R.id.btn_save);
        etHomeSearch = findViewById(R.id.et_home_search);
        Button btnSearchMic = findViewById(R.id.btn_search_mic);
        Button btnSearchClear = findViewById(R.id.btn_search_clear);
        Button btnSearchGo = findViewById(R.id.btn_search_go);

        findViewById(R.id.btn_open_drawer).setOnClickListener(v -> {
            if (drawerLayout != null) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        String savedIp = prefs.getString("tv_ip", "");
        etTvIp.setText(savedIp);
        updateReceiverStatus();
        tvStatusBadge.setOnClickListener(v -> scanForTvDevices());
        tvStatusBadge.setOnLongClickListener(v -> { showManualIpDialog(); return true; });
        btnSearchClear.setOnClickListener(v -> {
            etHomeSearch.setText("");
            hideHomeSearchKeyboard();
            etHomeSearch.clearFocus();
        });
        btnSearchGo.setOnClickListener(v -> openSearchResult());
        etHomeSearch.setOnEditorActionListener((v, actionId, event) -> { openSearchResult(); return true; });
        etHomeSearch.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) hideHomeSearchKeyboard();
        });
        btnSearchMic.setOnClickListener(v -> startVoiceSearch());

        btnSave.setOnClickListener(v -> {
            String ip = etTvIp.getText().toString().trim();
            prefs.edit().putString("tv_ip", ip).apply();
            tvStatusBadge.setText("● تم الحفظ");
            Toast.makeText(MainActivity.this, "تم حفظ IP الشاشة ✓", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.card_browser).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, BrowserActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        findViewById(R.id.card_local_media).setOnClickListener(v -> openMediaPicker(false));
        findViewById(R.id.card_slideshow).setOnClickListener(v -> openMediaPicker(true));

        findViewById(R.id.btn_play_pause).setOnClickListener(v -> {
            if (!slideshowQueue.isEmpty()) {
                if (isSlideshowRunning) {
                    isSlideshowRunning = false;
                    slideHandler.removeCallbacks(slideRunnable);
                    Toast.makeText(this, "تم إيقاف عرض الصور مؤقتاً ⏸️", Toast.LENGTH_SHORT).show();
                } else {
                    isSlideshowRunning = true;
                    slideHandler.post(slideRunnable);
                    Toast.makeText(this, "استئناف عرض الصور ▶️", Toast.LENGTH_SHORT).show();
                }
            } else {
                sendRemoteCommand("play_pause");
            }
        });

        findViewById(R.id.btn_stop).setOnClickListener(v -> stopAndClearPlaybackQueues());

        findViewById(R.id.btn_rewind).setOnClickListener(v -> sendRemoteCommand("rw"));
        findViewById(R.id.btn_forward).setOnClickListener(v -> sendRemoteCommand("ff"));

        findViewById(R.id.btn_next).setOnClickListener(v -> {
            if (!slideshowQueue.isEmpty()) {
                currentSlideIndex = (currentSlideIndex + 1) % slideshowQueue.size();
                selectedMediaUri = slideshowQueue.get(currentSlideIndex);
                castLocalMediaToTv(false);
            } else if (videoQueue.size() > 1 && currentVideoIndex < videoQueue.size() - 1) {
                currentVideoIndex++;
                selectedMediaUri = videoQueue.get(currentVideoIndex);
                castLocalMediaToTv(true);
            } else {
                sendRemoteCommand("next");
            }
        });

        findViewById(R.id.btn_prev).setOnClickListener(v -> {
            if (!slideshowQueue.isEmpty()) {
                currentSlideIndex = (currentSlideIndex - 1 + slideshowQueue.size()) % slideshowQueue.size();
                selectedMediaUri = slideshowQueue.get(currentSlideIndex);
                castLocalMediaToTv(false);
            } else if (videoQueue.size() > 1 && currentVideoIndex > 0) {
                currentVideoIndex--;
                selectedMediaUri = videoQueue.get(currentVideoIndex);
                castLocalMediaToTv(true);
            } else {
                sendRemoteCommand("prev");
            }
        });

        findViewById(R.id.btn_vol_up).setOnClickListener(v -> sendRemoteCommand("volume_up"));
        findViewById(R.id.btn_vol_down).setOnClickListener(v -> sendRemoteCommand("volume_down"));
        findViewById(R.id.btn_mute).setOnClickListener(v -> sendRemoteCommand("mute"));
    }

    private void setupBottomNavigation() {
        Button home = findViewById(R.id.bottom_home);
        Button media = findViewById(R.id.bottom_media);
        Button library = findViewById(R.id.bottom_library);

        home.setOnClickListener(v -> {
            View scroll = findViewById(R.id.main_content_scroll);
            if (scroll instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) scroll).smoothScrollTo(0, 0);
            }
            home.setTextColor(0xFFFFD700);
            media.setTextColor(0xFFFFFFFF);
            library.setTextColor(0xFFFFFFFF);
        });

        media.setOnClickListener(v -> {
            media.setTextColor(0xFFFFD700);
            home.setTextColor(0xFFFFFFFF);
            library.setTextColor(0xFFFFFFFF);
            openMediaPicker(false);
        });

        library.setOnClickListener(v -> {
            library.setTextColor(0xFFFFD700);
            home.setTextColor(0xFFFFFFFF);
            media.setTextColor(0xFFFFFFFF);
            showLibraryDialog();
        });
    }

    private void setupNavigationDrawer() {
        // 1. المتصفح
        findViewById(R.id.menu_browser).setOnClickListener(v -> {
            closeDrawer();
            Intent browserIntent = new Intent(MainActivity.this, BrowserActivity.class);
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(browserIntent);
        });

        // 2. اكتشاف أجهزة الاستقبال على الشبكة
        findViewById(R.id.menu_receiver).setOnClickListener(v -> {
            closeDrawer();
            scanForTvDevices();
        });

        // 2. ملفات الهاتف
        findViewById(R.id.menu_local_media).setOnClickListener(v -> {
            closeDrawer();
            openMediaPicker(false);
        });

        // 3. IPTV
        findViewById(R.id.menu_iptv).setOnClickListener(v -> {
            closeDrawer();
            showIptvDialog();
        });

        // 4. الوسائط الأخيرة
        findViewById(R.id.menu_recent_media).setOnClickListener(v -> {
            closeDrawer();
            showRecentMediaDialog();
        });

        // Saved direct media links, kept separately from browser bookmarks.
        findViewById(R.id.menu_saved_media).setOnClickListener(v -> {
            closeDrawer();
            showSavedMediaDialog();
        });

        // 5. قائمة التشغيل
        findViewById(R.id.menu_playlist).setOnClickListener(v -> {
            closeDrawer();
            showPlaylistDialog();
        });

        // 6. العلامات المرجعية
        findViewById(R.id.menu_bookmarks).setOnClickListener(v -> {
            closeDrawer();
            showBookmarksDialog();
        });

        // 7. سجل المتصفح
        findViewById(R.id.menu_history).setOnClickListener(v -> {
            closeDrawer();
            showBrowserHistory(false);
        });

        // 8. الأكثر زيارة
        findViewById(R.id.menu_most_visited).setOnClickListener(v -> {
            closeDrawer();
            showBrowserHistory(true);
        });

        // 9. التنزيلات
        findViewById(R.id.menu_downloads).setOnClickListener(v -> {
            closeDrawer();
            openDownloads();
        });

        // 10. الوضع المظلم
        findViewById(R.id.menu_dark_mode).setOnClickListener(v -> {
            closeDrawer();
            toggleDarkMode();
        });

        // 11. النسخ الاحتياطي / استعادة
        findViewById(R.id.menu_backup).setOnClickListener(v -> {
            closeDrawer();
            showBackupRestoreDialog();
        });

        // 12. إعدادات
        findViewById(R.id.menu_settings).setOnClickListener(v -> {
            closeDrawer();
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("إعدادات Abo Ezzat Share")
                    .setItems(new String[]{"التحقق من التحديثات", "إعداد عنوان IP الشاشة", "تفعيل الاختصار العائم عند نسخ رابط", "إعدادات أندرويد وأذونات التطبيق"}, (dialog, which) -> {
                        if (which == 0) {
                            new AppUpdater(MainActivity.this).checkForUpdate(true);
                        } else if (which == 1) {
                            showManualIpDialog();
                        } else if (which == 2) {
                            openClipboardShortcutSetup();
                        } else {
                            openAppPermissionSettings();
                        }
                    })
                    .setNegativeButton("إغلاق", null)
                    .show();
        });

        // 13. خروج
        findViewById(R.id.menu_exit).setOnClickListener(v -> {
            closeDrawer();
            finishAffinity();
        });
    }


    private void openClipboardShortcutSetup() {
        new AlertDialog.Builder(this)
                .setTitle("اختصار AboEzzat Cast العائم")
                .setMessage("لتشغيل الاختصار عند نسخ رابط فيديو مدعوم، فعّل الإذنين التاليين مرة واحدة:\n\n1) السماح بالظهور فوق التطبيقات الأخرى.\n2) تفعيل خدمة إمكانية الوصول باسم «اختصار AboEzzat Cast عند النسخ».\n\nالخدمة تعرض الأيقونة فقط عندما يظهر رابط فيديو مدعوم ضمن إشعار النسخ، ولا تقرأ محتوى الحافظة في الخلفية. تظهر الأيقونة 3 ثوانٍ ويمكنك سحبها لمكان مناسب.")
                .setPositiveButton("إذن الظهور فوق التطبيقات", (d, w) -> {
                    try {
                        Intent overlay = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(overlay);
                    } catch (Exception e) {
                        Toast.makeText(this, "افتح إعدادات التطبيق وفعّل الظهور فوق التطبيقات", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("إعداد إمكانية الوصول", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "افتح إعدادات إمكانية الوصول وفعّل خدمة AboEzzat Cast", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void openAppPermissionSettings() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "افتح إعدادات أندرويد > التطبيقات > AboEzzat Cast > الأذونات", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleDarkMode() {
        boolean enabled = !prefs.getBoolean("dark_mode", true);
        prefs.edit().putBoolean("dark_mode", enabled).apply();
        applyDarkMode(findViewById(R.id.drawer_layout), enabled);
        Toast.makeText(this, enabled ? "تم تفعيل الوضع المظلم" : "تم إيقاف الوضع المظلم", Toast.LENGTH_SHORT).show();
    }

    private void applyDarkMode(View view, boolean dark) {
        if (view == null) return;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyDarkMode(group.getChildAt(i), dark);
            }
        }
        // Keep the individually designed section/card/button colors from XML.
        // Only update text contrast; do not overwrite backgrounds or button tints.
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (view instanceof EditText) {
                text.setTextColor(dark ? 0xFFFFFFFF : 0xFF202124);
                ((EditText) view).setHintTextColor(dark ? 0xFFAAAAAA : 0xFF666666);
            }
        }
    }

    private void showBackupRestoreDialog() {
        new AlertDialog.Builder(this).setTitle("النسخ الاحتياطي والاستعادة")
                .setItems(new String[]{"تصدير الإعدادات إلى ملف", "استعادة الإعدادات من ملف"}, (dialog, which) -> {
                    Intent intent = new Intent(which == 0 ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(which == 0 ? "application/json" : "*/*");
                    if (which == 0) intent.putExtra(Intent.EXTRA_TITLE, "AboEzzatCast-settings.json");
                    startActivityForResult(intent, which == 0 ? 501 : 502);
                }).setNegativeButton("إغلاق", null).show();
    }

    private void exportSettings(Uri uri) {
        SharedPreferences browserPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("تعذر فتح الملف");
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("format", "AboEzzatCastDrawerBackup");
            json.put("version", 3);
            json.put("tv_ip", prefs.getString("tv_ip", ""));
            json.put("dark_mode", prefs.getBoolean("dark_mode", true));
            json.put("custom_sites", new org.json.JSONArray(new ArrayList<>(prefs.getStringSet("custom_sites", new HashSet<String>()))));
            // Include Cast-specific media data managed from the navigation drawer.
            json.put("saved_media_urls", prefs.getString("saved_media_urls", "[]"));
            json.put("video_queue_uris", prefs.getString("video_queue_uris", "[]"));
            json.put("slideshow_queue_uris", prefs.getString("slideshow_queue_uris", "[]"));
            json.put("recent_media_uris", prefs.getString("recent_media_uris", "[]"));
            json.put("current_video_index", currentVideoIndex);
            json.put("current_slide_index", currentSlideIndex);
            json.put("browser_history", new org.json.JSONArray(new ArrayList<>(browserPrefs.getStringSet("browser_history", new HashSet<String>()))));
            json.put("bookmarks", new org.json.JSONArray(new ArrayList<>(browserPrefs.getStringSet("custom_sites", new HashSet<String>()))));
            org.json.JSONObject metadata = new org.json.JSONObject();
            for (String url : browserPrefs.getStringSet("browser_history", new HashSet<String>())) {
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("title", browserPrefs.getString("history_title_" + url, url));
                item.put("time", browserPrefs.getLong("history_time_" + url, 0));
                item.put("visits", browserPrefs.getInt("visit_count_" + url, 0));
                metadata.put(url, item);
            }
            json.put("history_metadata", metadata);
            out.write(json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Toast.makeText(this, "تم تصدير إعدادات وبيانات الستارة", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل التصدير: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importSettings(Uri uri) {
        SharedPreferences browserPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("تعذر قراءة الملف");
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[4096]; int count;
            while ((count = in.read(bytes)) != -1) buffer.write(bytes, 0, count);
            org.json.JSONObject json = new org.json.JSONObject(buffer.toString("UTF-8"));
            SharedPreferences.Editor edit = prefs.edit();
            SharedPreferences.Editor browserEdit = browserPrefs.edit();
            boolean restoreMediaQueueState = json.has("video_queue_uris") || json.has("slideshow_queue_uris");
            for (String key : new String[]{"saved_media_urls", "video_queue_uris", "slideshow_queue_uris", "recent_media_uris"}) {
                if (json.has(key)) {
                    String value = json.optString(key, "[]");
                    // Validate every imported list before changing preferences.
                    new org.json.JSONArray(value);
                    edit.putString(key, value);
                }
            }
            if (json.has("tv_ip")) edit.putString("tv_ip", json.optString("tv_ip", ""));
            if (json.has("dark_mode")) edit.putBoolean("dark_mode", json.optBoolean("dark_mode", true));
            if (json.has("custom_sites")) {
                org.json.JSONArray array = json.optJSONArray("custom_sites");
                Set<String> sites = new HashSet<>();
                if (array != null) for (int i = 0; i < array.length(); i++) sites.add(array.optString(i));
                edit.putStringSet("custom_sites", sites);
            }
            if (json.has("browser_history")) {
                org.json.JSONArray array = json.optJSONArray("browser_history");
                Set<String> history = new HashSet<>();
                if (array != null) for (int i = 0; i < array.length(); i++) history.add(array.optString(i));
                browserEdit.putStringSet("browser_history", history);
            }
            if (json.has("bookmarks")) {
                org.json.JSONArray array = json.optJSONArray("bookmarks");
                Set<String> bookmarks = new HashSet<>();
                if (array != null) for (int i = 0; i < array.length(); i++) bookmarks.add(array.optString(i));
                browserEdit.putStringSet("custom_sites", bookmarks);
            }
            org.json.JSONObject metadata = json.optJSONObject("history_metadata");
            if (metadata != null) {
                java.util.Iterator<String> keys = metadata.keys();
                while (keys.hasNext()) {
                    String url = keys.next();
                    org.json.JSONObject item = metadata.optJSONObject(url);
                    if (item == null) continue;
                    browserEdit.putString("history_title_" + url, item.optString("title", url));
                    browserEdit.putLong("history_time_" + url, item.optLong("time", 0));
                    browserEdit.putInt("visit_count_" + url, item.optInt("visits", 0));
                }
            }
            edit.apply();
            browserEdit.apply();
            if (restoreMediaQueueState) {
                restoreMediaQueues();
                currentVideoIndex = Math.min(Math.max(0, json.optInt("current_video_index", 0)),
                        Math.max(0, videoQueue.size() - 1));
                currentSlideIndex = Math.min(Math.max(0, json.optInt("current_slide_index", 0)),
                        Math.max(0, slideshowQueue.size() - 1));
            }
            if (etTvIp != null) etTvIp.setText(prefs.getString("tv_ip", ""));
            updateReceiverStatus();
            applyDarkMode(findViewById(R.id.drawer_layout), prefs.getBoolean("dark_mode", true));
            Toast.makeText(this, "تمت استعادة إعدادات الستارة والمفضلة والسجل", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل الاستعادة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    private void stopAndClearPlaybackQueues() {
        // Stop every local playback mode and discard both persisted playlists.
        isSlideshowRunning = false;
        slideHandler.removeCallbacks(slideRunnable);
        slideshowQueue.clear();
        videoQueue.clear();
        currentSlideIndex = 0;
        currentVideoIndex = 0;
        selectedMediaUri = null;
        persistMediaQueues();

        // Stop whatever is currently playing on the receiver, including web video.
        sendRemoteCommand("stop");

        View scroll = findViewById(R.id.main_content_scroll);
        if (scroll instanceof android.widget.ScrollView) {
            ((android.widget.ScrollView) scroll).smoothScrollTo(0, 0);
        }
        Toast.makeText(this, "تم إيقاف التشغيل ومسح قوائم الصور والفيديو والعودة للرئيسية ⏹",
                Toast.LENGTH_SHORT).show();
    }

    private void clearBrowserHistory(SharedPreferences browserPrefs) {
        SharedPreferences.Editor edit = browserPrefs.edit();
        // Remove every history metadata key, not only URLs still present in the set.
        for (String key : browserPrefs.getAll().keySet()) {
            if (key.startsWith("history_title_") || key.startsWith("history_time_")
                    || key.startsWith("visit_count_")) {
                edit.remove(key);
            }
        }
        edit.remove("browser_history");
        edit.putLong("history_cleared_at", System.currentTimeMillis());
        // Commit before returning so a reopen/restart cannot observe stale history.
        edit.commit();
    }

    private void showBrowserHistory(boolean mostVisited) {
        SharedPreferences browserPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        Set<String> saved = new HashSet<>(browserPrefs.getStringSet("browser_history", new HashSet<String>()));
        List<String> urls = new ArrayList<>(saved);
        urls.removeIf(url -> url == null || url.trim().isEmpty());
        if (mostVisited) {
            urls.sort((a, b) -> Integer.compare(browserPrefs.getInt("visit_count_" + b, 0),
                    browserPrefs.getInt("visit_count_" + a, 0)));
        } else {
            urls.sort((a, b) -> Long.compare(browserPrefs.getLong("history_time_" + b, 0),
                    browserPrefs.getLong("history_time_" + a, 0)));
        }
        if (urls.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(mostVisited ? "الأكثر زيارة" : "سجل التصفح")
                    .setMessage("لسه مفيش صفحات اتسجلت. افتح مواقع من المتصفح الأول.")
                    .setPositiveButton("فتح المتصفح", (d, w) -> startActivity(new Intent(this, BrowserActivity.class)))
                    .setNegativeButton("إغلاق", null).show();
            return;
        }

        String[] labels = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            String title = browserPrefs.getString("history_title_" + url, url);
            labels[i] = (mostVisited ? (browserPrefs.getInt("visit_count_" + url, 0) + " زيارة · ") : "")
                    + title + "\n" + url;
        }

        android.widget.ListView listView = new android.widget.ListView(this);
        listView.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(mostVisited ? "الأكثر زيارة" : "سجل التصفح")
                .setView(listView)
                .setNeutralButton("مسح السجل كله", (d, w) -> {
                    clearBrowserHistory(browserPrefs);
                    Toast.makeText(this, "تم مسح سجل التصفح بالكامل", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إغلاق", null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(MainActivity.this, BrowserActivity.class);
            intent.putExtra("url", urls.get(position));
            startActivity(intent);
            dialog.dismiss();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String url = urls.get(position);
            String title = browserPrefs.getString("history_title_" + url, url);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("حذف الصفحة من السجل؟")
                    .setMessage(title + "\n" + url)
                    .setPositiveButton("حذف", (confirm, which) -> {
                        Set<String> updated = new HashSet<>(browserPrefs.getStringSet("browser_history", new HashSet<String>()));
                        updated.remove(url);
                        browserPrefs.edit().putStringSet("browser_history", updated)
                                .remove("history_title_" + url)
                                .remove("history_time_" + url)
                                .remove("visit_count_" + url).apply();
                        dialog.dismiss();
                        Toast.makeText(MainActivity.this, "تم حذف الصفحة من السجل", Toast.LENGTH_SHORT).show();
                        showBrowserHistory(mostVisited);
                    })
                    .setNegativeButton("إلغاء", null)
                    .show();
            return true;
        });
        dialog.show();
    }

    private void closeDrawer() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
    }

    private void showIptvDialog() {
        EditText input = new EditText(this);
        input.setHint("الصق رابط بث IPTV (m3u8 أو flv)");
        input.setTextColor(0xFFFFFFFF);
        input.setBackgroundColor(0xFF262833);
        input.setPadding(16, 16, 16, 16);

        new AlertDialog.Builder(this)
                .setTitle("تشغيل IPTV على الشاشة")
                .setView(input)
                .setPositiveButton("بث مباشر 📺", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        castUrlDirectlyToTv(url);
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private String getMediaDisplayName(Uri uri, int index) {
        if (uri == null) return "ملف " + (index + 1);
        String name = uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = uri.toString();
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        return (index + 1) + ". " + name;
    }

    // Library layout modeled on the supplied screenshot.
    private void showLibraryDialog() {
        SharedPreferences browserPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF101014);
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        scroll.addView(content);

        TextView title = new TextView(this);
        title.setText("مكتبتك");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(23);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.RIGHT);
        content.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("افتح عنصرًا للرجوع إليه، واضغط مطولًا لحذفه");
        subtitle.setTextColor(0xFF9B9B9F);
        subtitle.setTextSize(13);
        subtitle.setGravity(android.view.Gravity.RIGHT);
        android.widget.LinearLayout.LayoutParams subtitleParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(8);
        subtitleParams.bottomMargin = dp(12);
        content.addView(subtitle, subtitleParams);

        addLibrarySectionTitle(content, "☆  المفضلة");
        Set<String> bookmarks = new HashSet<>(browserPrefs.getStringSet("custom_sites", new HashSet<String>()));
        List<String> entries = new ArrayList<>(), names = new ArrayList<>(), urls = new ArrayList<>();
        for (String entry : bookmarks) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length == 2) { entries.add(entry); names.add(parts[0]); urls.add(parts[1]); }
        }
        if (entries.isEmpty()) {
            addLibraryEmptyText(content, "لا توجد عناصر بعد");
        } else {
            for (int i = 0; i < entries.size(); i++) {
                final int index = i;
                TextView card = addLibraryCard(content, names.get(i));
                card.setOnClickListener(v -> openLibraryUrl(urls.get(index)));
                card.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("حذف العلامة المرجعية؟")
                            .setMessage(names.get(index) + "\n" + urls.get(index))
                            .setPositiveButton("حذف", (d, w) -> {
                                Set<String> updated = new HashSet<>(browserPrefs.getStringSet("custom_sites", new HashSet<String>()));
                                updated.remove(entries.get(index));
                                browserPrefs.edit().putStringSet("custom_sites", updated).apply();
                                Toast.makeText(MainActivity.this, "تم حذف العلامة المرجعية", Toast.LENGTH_SHORT).show();
                                showLibraryDialog();
                            }).setNegativeButton("إلغاء", null).show();
                    return true;
                });
            }
        }

        addLibrarySectionTitle(content, "◷  سجل التصفح");
        Set<String> history = new HashSet<>(browserPrefs.getStringSet("browser_history", new HashSet<String>()));
        List<String> historyUrls = new ArrayList<>(history);
        historyUrls.removeIf(url -> url == null || url.trim().isEmpty());
        historyUrls.sort((a, b) -> Long.compare(browserPrefs.getLong("history_time_" + b, 0),
                browserPrefs.getLong("history_time_" + a, 0)));
        for (String url : historyUrls) {
            final String pageUrl = url;
            String pageTitle = browserPrefs.getString("history_title_" + url, url);
            TextView card = addLibraryCard(content, pageTitle);
            card.setOnClickListener(v -> openLibraryUrl(pageUrl));
            card.setOnLongClickListener(v -> {
                String name = browserPrefs.getString("history_title_" + pageUrl, pageUrl);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("حذف الصفحة من السجل؟")
                        .setMessage(name + "\n" + pageUrl)
                        .setPositiveButton("حذف", (d, w) -> {
                            Set<String> updated = new HashSet<>(browserPrefs.getStringSet("browser_history", new HashSet<String>()));
                            updated.remove(pageUrl);
                            browserPrefs.edit().putStringSet("browser_history", updated)
                                    .remove("history_title_" + pageUrl).remove("history_time_" + pageUrl)
                                    .remove("visit_count_" + pageUrl).apply();
                            Toast.makeText(MainActivity.this, "تم حذف الصفحة من السجل", Toast.LENGTH_SHORT).show();
                            showLibraryDialog();
                        }).setNegativeButton("إلغاء", null).show();
                return true;
            });
        }
        if (historyUrls.isEmpty()) addLibraryEmptyText(content, "لا توجد صفحات في سجل التصفح");

        TextView clearHistory = new TextView(this);
        clearHistory.setText("🗑  مسح سجل التصفح بالكامل");
        clearHistory.setTextColor(0xFFFFFFFF);
        clearHistory.setTextSize(15);
        clearHistory.setTypeface(null, android.graphics.Typeface.BOLD);
        clearHistory.setGravity(android.view.Gravity.CENTER);
        clearHistory.setPadding(dp(12), dp(12), dp(12), dp(12));
        clearHistory.setBackground(libraryCardBackground(0xFF252630));
        android.widget.LinearLayout.LayoutParams clearParams = new android.widget.LinearLayout.LayoutParams(-1, -2);
        clearParams.topMargin = dp(12);
        clearParams.bottomMargin = dp(10);
        content.addView(clearHistory, clearParams);
        clearHistory.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this)
                .setTitle("مسح سجل التصفح بالكامل؟")
                .setMessage("سيتم حذف جميع الصفحات المحفوظة في السجل.")
                .setPositiveButton("مسح", (d, w) -> {
                    clearBrowserHistory(browserPrefs);
                    Toast.makeText(MainActivity.this, "تم مسح سجل التصفح بالكامل", Toast.LENGTH_SHORT).show();
                    showLibraryDialog();
                }).setNegativeButton("إلغاء", null).show());

        addLibrarySectionTitle(content, "الوسائط");
        TextView recent = addLibraryCard(content, "الوسائط الأخيرة");
        recent.setOnClickListener(v -> showRecentMediaDialog());
        TextView playlist = addLibraryCard(content, "قائمة التشغيل");
        playlist.setOnClickListener(v -> showPlaylistDialog());
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.setView(scroll);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF101014));
            dialog.getWindow().setLayout(-1, getResources().getDisplayMetrics().heightPixels - dp(76));
            dialog.getWindow().setGravity(android.view.Gravity.TOP);
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addLibrarySectionTitle(android.widget.LinearLayout content, String text) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextColor(0xFFFFD400);
        heading.setTextSize(17);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        params.bottomMargin = dp(8);
        content.addView(heading, params);
    }

    private void addLibraryEmptyText(android.widget.LinearLayout content, String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        empty.setTextColor(0xFF99999D);
        empty.setTextSize(14);
        empty.setGravity(android.view.Gravity.CENTER);
        content.addView(empty, new android.widget.LinearLayout.LayoutParams(-1, dp(48)));
    }

    private TextView addLibraryCard(android.widget.LinearLayout content, String text) {
        TextView card = new TextView(this);
        card.setText(text);
        card.setTextColor(0xFFF7F7F8);
        card.setTextSize(15);
        card.setTypeface(null, android.graphics.Typeface.BOLD);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(libraryCardBackground(0xFF1B1C22));
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(-1, dp(56));
        params.bottomMargin = dp(12);
        content.addView(card, params);
        return card;
    }

    private android.graphics.drawable.GradientDrawable libraryCardBackground(int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(14));
        return shape;
    }

    private void openLibraryUrl(String url) {
        Intent intent = new Intent(MainActivity.this, BrowserActivity.class);
        intent.putExtra("url", url);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    private void showRecentMediaDialog() {
        List<Uri> recent = readUriList("recent_media_uris");
        if (recent.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("الوسائط الأخيرة")
                    .setMessage("لا توجد وسائط حديثة محفوظة. اختر فيديو أو صورة من زر الوسائط لإضافتها هنا.")
                    .setPositiveButton("اختيار ملفات", (d, w) -> openMediaPicker(false))
                    .setNegativeButton("إغلاق", null).show();
            return;
        }
        String[] labels = new String[recent.size()];
        for (int i = 0; i < recent.size(); i++) labels[i] = getMediaDisplayName(recent.get(i), i);
        new AlertDialog.Builder(this).setTitle("الوسائط الأخيرة")
                .setItems(labels, (dialog, which) -> {
                    Uri chosen = recent.get(which);
                    selectedMediaUri = chosen;
                    if (videoQueue.contains(chosen)) currentVideoIndex = videoQueue.indexOf(chosen);
                    if (slideshowQueue.contains(chosen)) currentSlideIndex = slideshowQueue.indexOf(chosen);
                    castLocalMediaToTv(true);
                }).setNegativeButton("إغلاق", null).show();
    }

    private void showPlaylistDialog() {
        if (videoQueue.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("قائمة التشغيل")
                    .setMessage("قائمة التشغيل فارغة. أضف فيديوهات من زر الوسائط أولاً.")
                    .setPositiveButton("إضافة فيديوهات", (d, w) -> openMediaPicker(false))
                    .setNegativeButton("إغلاق", null).show();
            return;
        }
        String[] items = new String[videoQueue.size()];
        for (int i = 0; i < videoQueue.size(); i++) items[i] = getMediaDisplayName(videoQueue.get(i), i);
        new AlertDialog.Builder(this).setTitle("قائمة التشغيل (" + videoQueue.size() + " ملفات)")
                .setItems(items, (dialog, which) -> {
                    currentVideoIndex = which;
                    selectedMediaUri = videoQueue.get(currentVideoIndex);
                    castLocalMediaToTv(true);
                }).setPositiveButton("إضافة فيديوهات", (d, w) -> openMediaPicker(false))
                .setNegativeButton("إغلاق", null).show();
    }

    private void showBookmarksDialog() {
        SharedPreferences bookmarkPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        Set<String> customSites = new HashSet<>(bookmarkPrefs.getStringSet("custom_sites", new HashSet<String>()));
        List<String> names = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        for (String entry : customSites) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length == 2) {
                names.add(parts[0]);
                urls.add(parts[1]);
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, "لا توجد علامات مرجعية محفوظة بعد", Toast.LENGTH_SHORT).show();
            return;
        }

        android.widget.ListView listView = new android.widget.ListView(this);
        listView.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, names));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("العلامات المرجعية (اضغط مطولاً للحذف)")
                .setView(listView)
                .setNegativeButton("إغلاق", null)
                .create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Intent intent = new Intent(MainActivity.this, BrowserActivity.class);
            intent.putExtra("url", urls.get(position));
            startActivity(intent);
            dialog.dismiss();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String entry = entries.get(position);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("حذف العلامة المرجعية؟")
                    .setMessage(names.get(position) + "\\n" + urls.get(position))
                    .setPositiveButton("حذف", (confirm, which) -> {
                        Set<String> updated = new HashSet<>(bookmarkPrefs.getStringSet("custom_sites", new HashSet<String>()));
                        updated.remove(entry);
                        bookmarkPrefs.edit().putStringSet("custom_sites", updated).apply();
                        dialog.dismiss();
                        Toast.makeText(MainActivity.this, "تم حذف العلامة المرجعية", Toast.LENGTH_SHORT).show();
                        showBookmarksDialog();
                    })
                    .setNegativeButton("إلغاء", null)
                    .show();
            return true;
        });
        dialog.show();
    }


    private org.json.JSONArray readSavedMediaUrls() {
        try {
            return new org.json.JSONArray(prefs.getString("saved_media_urls", "[]"));
        } catch (Exception ignored) {
            return new org.json.JSONArray();
        }
    }

    private void showSavedMediaDialog() {
        org.json.JSONArray saved = readSavedMediaUrls();
        List<String> urls = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < saved.length(); i++) {
            String url = saved.optString(i, "").trim();
            if (url.isEmpty() || !url.startsWith("http://") && !url.startsWith("https://")) continue;
            urls.add(url);
            String host;
            try { host = Uri.parse(url).getHost(); } catch (Exception ignored) { host = null; }
            labels.add((host == null || host.trim().isEmpty() ? "وسائط محفوظة" : host) + "\n" + url);
        }

        android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("الوسائط المحفوظة")
                .setView(list)
                .setPositiveButton("إضافة رابط", (d, w) -> showAddSavedMediaDialog())
                .setNegativeButton("إغلاق", null)
                .create();

        list.setOnItemClickListener((parent, view, position, id) -> {
            castUrlDirectlyToTv(urls.get(position));
            dialog.dismiss();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            String url = urls.get(position);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("حذف الوسيط المحفوظ؟")
                    .setMessage(url)
                    .setPositiveButton("حذف", (confirm, which) -> {
                        org.json.JSONArray latest = readSavedMediaUrls();
                        org.json.JSONArray kept = new org.json.JSONArray();
                        for (int i = 0; i < latest.length(); i++) {
                            String candidate = latest.optString(i, "");
                            if (!url.equals(candidate)) kept.put(candidate);
                        }
                        prefs.edit().putString("saved_media_urls", kept.toString()).apply();
                        dialog.dismiss();
                        Toast.makeText(MainActivity.this, "تم حذف الوسيط المحفوظ", Toast.LENGTH_SHORT).show();
                        showSavedMediaDialog();
                    })
                    .setNegativeButton("إلغاء", null)
                    .show();
            return true;
        });
        dialog.show();
    }

    private void showAddSavedMediaDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https:// رابط الفيديو أو الصوت المباشر");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("إضافة وسيط محفوظ")
                .setMessage("أضف رابط بث أو ملف وسائط مباشر. اضغط عليه لاحقًا لإرساله إلى الشاشة.")
                .setView(input)
                .setPositiveButton("حفظ", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                        Toast.makeText(this, "أدخل رابطًا يبدأ بـ http أو https", Toast.LENGTH_LONG).show();
                        return;
                    }
                    org.json.JSONArray saved = readSavedMediaUrls();
                    for (int i = 0; i < saved.length(); i++) {
                        if (url.equals(saved.optString(i, ""))) {
                            Toast.makeText(this, "الرابط محفوظ بالفعل", Toast.LENGTH_SHORT).show();
                            showSavedMediaDialog();
                            return;
                        }
                    }
                    saved.put(url);
                    prefs.edit().putString("saved_media_urls", saved.toString()).apply();
                    Toast.makeText(this, "تم حفظ رابط الوسائط", Toast.LENGTH_SHORT).show();
                    showSavedMediaDialog();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void openDownloads() {
        final String key = "in_app_download_items";
        org.json.JSONArray items;
        try { items = new org.json.JSONArray(prefs.getString(key, "[]")); }
        catch (Exception e) { items = new org.json.JSONArray(); }

        final ArrayList<org.json.JSONObject> records = new ArrayList<>();
        final ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            org.json.JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            records.add(item);
            String status = item.optString("status", "failed");
            String state;
            if ("complete".equals(status)) {
                long bytes = item.optLong("bytes", 0L);
                state = bytes > 0 ? "اكتمل ✓ — " + formatDownloadSize(bytes) : "ملف غير صالح (صفر بايت)";
            } else if ("downloading".equals(status)) {
                int progress = item.optInt("progress", -1);
                state = progress >= 0 ? "جارٍ التنزيل " + progress + "%" : "جارٍ التنزيل";
            } else {
                state = "فشل: " + item.optString("error", "تعذر تنزيل الملف");
            }
            labels.add(item.optString("title", "تنزيل") + " — " + item.optString("file", "")
                    + "\\n" + state);
        }

        if (records.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("تنزيلاتي داخل AboEzzat Cast")
                    .setMessage("لسه ما نزلتش أي ملفات من المتصفح.")
                    .setPositiveButton("حسنًا", null).show();
            return;
        }

        android.widget.ListView list = new android.widget.ListView(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("تنزيلاتي داخل AboEzzat Cast")
                .setView(list).setNegativeButton("إغلاق", null).create();
        list.setOnItemClickListener((parent, view, position, rowId) -> {
            if (position < 0 || position >= records.size()) return;
            org.json.JSONObject item = records.get(position);
            new AlertDialog.Builder(this).setTitle(item.optString("title", "التنزيل"))
                    .setItems(new String[]{"فتح الملف", "حذف التنزيل من القائمة والجهاز"}, (d, choice) -> {
                        if (choice == 0) {
                            String status = item.optString("status", "");
                            long bytes = item.optLong("bytes", 0L);
                            String savedUri = item.optString("uri", "");
                            if (!"complete".equals(status) || bytes <= 0 || savedUri.isEmpty()) {
                                Toast.makeText(this, "الملف لم يكتمل أو لم يتم حفظ بياناته بشكل سليم", Toast.LENGTH_LONG).show();
                                return;
                            }
                            try {
                                String mime = item.optString("mime", "");
                                if (mime.isEmpty() || mime.contains("*")) {
                                    String type = item.optString("type", "video");
                                    mime = "image".equals(type) ? "image/jpeg"
                                            : "audio".equals(type) ? "audio/mpeg" : "video/mp4";
                                }
                                Intent open = new Intent(Intent.ACTION_VIEW);
                                open.setDataAndType(Uri.parse(savedUri), mime);
                                open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(open);
                            } catch (Exception e) {
                                Toast.makeText(this, "لا يوجد مشغل مناسب لهذا الملف", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            BrowserFileDownloader.deleteFile(this, item);
                            org.json.JSONArray updated = new org.json.JSONArray();
                            for (int j = 0; j < records.size(); j++) {
                                if (j != position) updated.put(records.get(j));
                            }
                            prefs.edit().putString(key, updated.toString()).apply();
                            dialog.dismiss();
                            openDownloads();
                        }
                    }).setNegativeButton("إلغاء", null).show();
        });
        dialog.show();
    }

    private String formatDownloadSize(long bytes) {
        if (bytes < 1024) return bytes + " بايت";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f كيلوبايت", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f ميجابايت", bytes / (1024.0 * 1024.0));
    }

    private void updateReceiverStatus() {
        String ip = prefs.getString("tv_ip", "").trim();
        if (ip.isEmpty()) {
            tvStatusBadge.setText("● اضغط لفحص الأجهزة • ضغطة مطولة لإدخال IP");
            return;
        }
        tvStatusBadge.setText("● جارٍ التحقق من " + ip + "...");
        AsyncTask.execute(() -> {
            boolean reachable = false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, TARGET_PORT), 1200);
                reachable = true;
            } catch (Exception ignored) { }
            final boolean isReachable = reachable;
            runOnUiThread(() -> tvStatusBadge.setText(isReachable
                    ? "● متصل بـ " + ip : "● محفوظ، غير متصل: " + ip));
        });
    }
private void showManualIpDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("مثال: 192.168.1.100");
        input.setText(prefs.getString("tv_ip", ""));
        new AlertDialog.Builder(this).setTitle("إدخال IP يدويًا").setView(input)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) {
                        prefs.edit().putString("tv_ip", ip).apply();
                        etTvIp.setText(ip);
                        updateReceiverStatus();
                    }
                }).setNegativeButton("إلغاء", null).show();
    }

    private void hideHomeSearchKeyboard() {
        if (etHomeSearch == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etHomeSearch.getWindowToken(), 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (etHomeSearch != null) {
            etHomeSearch.clearFocus();
            hideHomeSearchKeyboard();
        }
        remindIfAccessibilityDisabled();
    }

    private boolean isClipboardAccessibilityEnabled() {
        try {
            String enabled = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            String target = new android.content.ComponentName(
                    this, ClipboardShortcutAccessibilityService.class).flattenToString();
            for (String service : enabled.split(":")) {
                if (target.equalsIgnoreCase(service)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void remindIfAccessibilityDisabled() {
        if (clipboardShortcutLaunch || accessibilityReminderShown || isFinishing()
                || isClipboardAccessibilityEnabled()) return;
        accessibilityReminderShown = true;

        new AlertDialog.Builder(this)
                .setTitle("زر النسخ العائم غير مفعّل")
                .setMessage("يبدو أن أندرويد أوقف خدمة إمكانية الوصول بعد تحديث التطبيق. فعّل خدمة «اختصار AboEzzat Cast عند النسخ» من القائمة التي ستفتح الآن. إذا كان الخيار مقفولًا، افتح معلومات التطبيق من إعدادات الهاتف واختر «السماح بالإعدادات المقيّدة» ثم ارجع وفعّل الخدمة.")
                .setPositiveButton("فتح إعدادات إمكانية الوصول", (dialog, which) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "افتح إعدادات إمكانية الوصول يدويًا", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("لاحقًا", null)
                .show();
    }

    private void openSearchResult() {
        String query = etHomeSearch.getText().toString().trim();
        hideHomeSearchKeyboard();
        etHomeSearch.clearFocus();
        if (query.isEmpty()) return;
        String target;
        if (query.startsWith("http://") || query.startsWith("https://")) target = query;
        else if (query.matches("(?i)^[a-z0-9.-]+\\.[a-z]{2,}(:[0-9]+)?(/.*)?$")) target = "https://" + query;
        else {
            try { target = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"); }
            catch (Exception e) { target = "https://www.google.com"; }
        }
        Intent intent = new Intent(MainActivity.this, BrowserActivity.class);
        intent.putExtra("url", target);
        startActivity(intent);
    }

    private void startVoiceSearch() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "قل ما تريد البحث عنه");
            startActivityForResult(intent, VOICE_SEARCH_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "ميزة البحث الصوتي غير متاحة على الهاتف", Toast.LENGTH_SHORT).show();
        }
    }

    private void scanForTvDevices() {
        tvStatusBadge.setText("🔍 جاري الفحص...");
        Toast.makeText(this, "جاري فحص الشبكة لاكتشاف الأجهزة المتاحة...", Toast.LENGTH_SHORT).show();

        AsyncTask.execute(() -> {
            String phoneIp = getDeviceIpAddress();
            if (phoneIp.isEmpty() || !phoneIp.contains(".")) {
                runOnUiThread(() -> tvStatusBadge.setText("● تأكد من الواي فاي"));
                return;
            }

            String subnet = phoneIp.substring(0, phoneIp.lastIndexOf(".") + 1);
            final List<String> availableDevices = new ArrayList<>();

            for (int i = 1; i < 255; i++) {
                String testIp = subnet + i;
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(testIp, TARGET_PORT), 35);
                    socket.close();
                    availableDevices.add(testIp);
                } catch (Exception ignored) {}
            }

            runOnUiThread(() -> {
                if (!availableDevices.isEmpty()) {
                    showDeviceSelectionDialog(availableDevices);
                } else {
                    tvStatusBadge.setText("● لم تكتشف أجهزة");
                    Toast.makeText(MainActivity.this, "لم يتم العثور على أي جهاز فاتح منفذ 8081", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showDeviceSelectionDialog(final List<String> devices) {
        String[] deviceArray = devices.toArray(new String[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("اختر جهاز الاستقبال (منفذ 8081)");
        builder.setItems(deviceArray, (dialog, which) -> {
            String chosenIp = devices.get(which);
            etTvIp.setText(chosenIp);
            prefs.edit().putString("tv_ip", chosenIp).apply();
            updateReceiverStatus();
            Toast.makeText(MainActivity.this, "تم اختيار وحفظ: " + chosenIp, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("إلغاء", null);
        builder.show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (handleClipboardCastShortcut(intent)) return;
        if (handleIncomingShareIntent(intent)) return;

        if (intent != null && intent.getBooleanExtra("open_media_picker", false)) {
            new Handler(Looper.getMainLooper()).post(() -> openMediaPicker(false));
        } else if (intent != null && intent.getBooleanExtra("open_library", false)) {
            // The Library tab must always open bookmarks and browsing history.
            // Recent media is a separate action and must not run on Library navigation.
            new Handler(Looper.getMainLooper()).post(() -> showLibraryDialog());
        }
        else if (intent != null && intent.getBooleanExtra("open_downloads", false)) {
            new Handler(Looper.getMainLooper()).post(() -> openDownloads());
        }
    }

    private boolean handleClipboardCastShortcut(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("clipboard_cast_shortcut", false)) return false;
        intent.removeExtra("clipboard_cast_shortcut");

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard == null || !clipboard.hasPrimaryClip()) {
                    Toast.makeText(this, "لا يوجد رابط فيديو في الحافظة", Toast.LENGTH_SHORT).show();
                    if (clipboardShortcutLaunch) finish();
                    return;
                }
                ClipData clip = clipboard.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) {
                    Toast.makeText(this, "لا يوجد رابط في الحافظة", Toast.LENGTH_SHORT).show();
                    return;
                }
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                if (text == null) {
                    Toast.makeText(this, "الحافظة لا تحتوي على رابط فيديو", Toast.LENGTH_SHORT).show();
                    if (clipboardShortcutLaunch) finish();
                    return;
                }
                // Re-enter through the exact same ACTION_SEND handler used by
                // Android's Sharesheet when the user selects AboEzzat Cast.
                // This keeps URL parsing and direct-vs-browser routing identical.
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, text.toString());
                if (!handleIncomingShareIntent(shareIntent)) {
                    Toast.makeText(this, "لم يتم العثور على رابط قابل للمشاركة", Toast.LENGTH_SHORT).show();
                    if (clipboardShortcutLaunch) finish();
                }
            } catch (Exception e) {
                Toast.makeText(this, "تعذر قراءة الرابط من الحافظة", Toast.LENGTH_SHORT).show();
                if (clipboardShortcutLaunch) finish();
            }
        }, 300L);
        return true;
    }

    private boolean isLikelyVideoClipboardUrl(String rawUrl) {
        try {
            Uri uri = Uri.parse(rawUrl);
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if (path.matches(".*\\.(mp4|m3u8|webm|mov|mkv|avi)$")) return true;
            if (host.equals("youtu.be") || host.endsWith("youtube.com")
                    || host.endsWith("youtube-nocookie.com") || host.equals("fb.watch")) return true;
            if (host.endsWith("facebook.com") || host.equals("fb.com"))
                return path.contains("/watch") || path.contains("/reel")
                        || path.contains("/videos/") || path.contains("/share/v/");
            if (host.endsWith("tiktok.com")) return path.contains("/video/") || path.contains("/live");
            if (host.endsWith("instagram.com"))
                return path.contains("/reel/") || path.contains("/tv/") || path.matches("/p/[^/]+/?");
            if (host.endsWith("threads.net")) return path.contains("/post/");
            if (host.equals("x.com") || host.endsWith("twitter.com")) return path.contains("/status/");
            return host.endsWith("vimeo.com") || host.endsWith("dailymotion.com")
                    || host.endsWith("twitch.tv") || host.endsWith("streamable.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean handleIncomingShareIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) && intent.getType() != null) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) {
                Pattern pattern = Pattern.compile("https?://\\S+");
                Matcher matcher = pattern.matcher(sharedText);
                if (matcher.find()) {
                    processSharedLink(matcher.group(0));
                    return true;
                }
            }
        }
        return false;
    }

    private void processSharedLink(String url) {
        String lowerUrl = url.toLowerCase(java.util.Locale.ROOT);
        boolean isInstagram = lowerUrl.contains("instagram.com");
        boolean isThreads = lowerUrl.contains("threads.net");
        boolean isTikTokLive = lowerUrl.contains("tiktok.com/live")
                || lowerUrl.matches(".*tiktok\\.com/@[^/]+/live.*")
                || lowerUrl.contains("live.tiktok.com");
        // Facebook, YouTube and regular TikTok links go straight to Receiver.
        // Keep browser handling only for sites/links that need page-side extraction.
        boolean isBrowserRequired = isInstagram || isThreads || isTikTokLive;

        if (isBrowserRequired) {
            Intent browserIntent = new Intent(this, BrowserActivity.class);
            browserIntent.setAction(Intent.ACTION_VIEW);
            browserIntent.setData(Uri.parse(url));
            startActivity(browserIntent);
            finish();
        } else {
            castUrlDirectlyToTv(url);
        }
    }

    private void castUrlDirectlyToTv(String mediaUrl) {
        String tvIp = prefs.getString("tv_ip", "").trim();
        if (tvIp.isEmpty()) {
            Toast.makeText(this, "يرجى تعيين عنوان الشاشة أولاً", Toast.LENGTH_LONG).show();
            if (clipboardShortcutLaunch) finish();
            return;
        }

        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http")) endpoint = "http://" + endpoint;
            if (!endpoint.contains(":" + TARGET_PORT)) endpoint += ":" + TARGET_PORT;
            final String receiverEndpoint = endpoint;

            String fullUrl = receiverEndpoint + "/?url=" + URLEncoder.encode(mediaUrl, "UTF-8");
            Request req = new Request.Builder().url(fullUrl).build();
            AtomicBoolean requestHeadersSent = new AtomicBoolean(false);
            OkHttpClient trackedClient = client.newBuilder()
                    .eventListener(new EventListener() {
                        @Override
                        public void requestHeadersEnd(Call call, Request request) {
                            requestHeadersSent.set(true);
                        }
                    }).build();
            trackedClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    // The receiver can accept and start playback, then the socket can
                    // close before OkHttp receives the acknowledgement. Verify the receiver
                    // with its lightweight ping endpoint before showing a failure.
                    Request ping = new Request.Builder()
                            .url(receiverEndpoint + "/?command=ping")
                            .get()
                            .build();
                    // Once the request headers reached Receiver, the receiver may
                    // have accepted playback and closed the socket before replying.
                    // Report the send instead of showing a misleading acknowledgement error.
                    if (requestHeadersSent.get()) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "تم الإرسال ✅", Toast.LENGTH_SHORT).show();
                            finish();
                        });
                        return;
                    }
                    client.newCall(ping).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call pingCall, IOException pingError) {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "تعذر الاتصال بالشاشة", Toast.LENGTH_SHORT).show();
                                finish();
                            });
                        }

                        @Override
                        public void onResponse(Call pingCall, Response pingResponse) {
                            pingResponse.close();
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "الشاشة متصلة، لكن لم يصل طلب الوسائط", Toast.LENGTH_SHORT).show();
                                finish();
                            });
                        }
                    });
                }

                @Override
                public void onResponse(Call call, Response response) {
                    boolean accepted = response.isSuccessful();
                    response.close();
                    runOnUiThread(() -> {
                        String message = accepted
                                ? "تم الإرسال ✅"
                                : "الشاشة رفضت الطلب (HTTP)";
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }
            });
        } catch (Exception ignored) {
            if (clipboardShortcutLaunch) finish();
        }
    }

    private void openMediaPicker(boolean multipleImages) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        if (multipleImages) {
            intent.setType("image/*");
            startActivityForResult(intent, PICK_SLIDESHOW_REQUEST);
        } else {
            intent.setType("*/*");
            String[] mimeTypes = {"video/*", "audio/*"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            startActivityForResult(intent, PICK_MEDIA_REQUEST);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 501 || requestCode == 502) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                if (requestCode == 501) exportSettings(data.getData());
                else importSettings(data.getData());
            }
            return;
        }
        if (requestCode == VOICE_SEARCH_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> voiceResults = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (voiceResults != null && !voiceResults.isEmpty()) {
                    etHomeSearch.setText(voiceResults.get(0));
                    etHomeSearch.setSelection(etHomeSearch.getText().length());
                }
            }
            return;
        }
        slideHandler.removeCallbacks(slideRunnable);
        isSlideshowRunning = false;

        if (resultCode == RESULT_OK && data != null) {
            if (requestCode == PICK_MEDIA_REQUEST) {
                videoQueue.clear();
                slideshowQueue.clear();
                isSlideshowRunning = false;
                ClipData clipData = data.getClipData();

                if (clipData != null) {
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        Uri uri = clipData.getItemAt(i).getUri();
                        takePermissions(uri);
                        videoQueue.add(uri);
                    }
                } else if (data.getData() != null) {
                    takePermissions(data.getData());
                    videoQueue.add(data.getData());
                }

                if (!videoQueue.isEmpty()) {
                    currentVideoIndex = 0;
                    persistMediaQueues();
                    for (Uri mediaUri : videoQueue) addRecentMedia(mediaUri);
                    selectedMediaUri = videoQueue.get(0);
                    castLocalMediaToTv(true);
                    if (videoQueue.size() > 1) {
                        Toast.makeText(this, "تم تحميل (" + videoQueue.size() + ") وسائط، استخدم التالي والسابق للتنقل", Toast.LENGTH_LONG).show();
                    }
                }
            } else if (requestCode == PICK_SLIDESHOW_REQUEST) {
                slideshowQueue.clear();
                videoQueue.clear();
                currentVideoIndex = 0;
                ClipData clipData = data.getClipData();

                if (clipData != null) {
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        Uri uri = clipData.getItemAt(i).getUri();
                        takePermissions(uri);
                        slideshowQueue.add(uri);
                    }
                } else if (data.getData() != null) {
                    takePermissions(data.getData());
                    slideshowQueue.add(data.getData());
                }

                if (!slideshowQueue.isEmpty()) {
                    currentSlideIndex = 0;
                    persistMediaQueues();
                    for (Uri mediaUri : slideshowQueue) addRecentMedia(mediaUri);
                    selectedMediaUri = slideshowQueue.get(0);
                    castLocalMediaToTv(true);
                    if (slideshowQueue.size() > 1) {
                        isSlideshowRunning = true;
                        slideHandler.postDelayed(slideRunnable, 5000);
                        Toast.makeText(this, "بدأ العرض التقديمي للصور (" + slideshowQueue.size() + ")", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    }

    private List<Uri> readUriList(String key) {
        List<Uri> result = new ArrayList<>();
        try {
            org.json.JSONArray array = new org.json.JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) {
                    Uri uri = Uri.parse(value);
                    if (!result.contains(uri)) result.add(uri);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void writeUriList(String key, List<Uri> values) {
        org.json.JSONArray array = new org.json.JSONArray();
        for (Uri uri : values) if (uri != null) array.put(uri.toString());
        prefs.edit().putString(key, array.toString()).apply();
    }

    private void restoreMediaQueues() {
        videoQueue.clear();
        videoQueue.addAll(readUriList("video_queue_uris"));
        slideshowQueue.clear();
        slideshowQueue.addAll(readUriList("slideshow_queue_uris"));
        currentVideoIndex = 0;
        currentSlideIndex = 0;
    }

    private void persistMediaQueues() {
        writeUriList("video_queue_uris", videoQueue);
        writeUriList("slideshow_queue_uris", slideshowQueue);
    }

    private void addRecentMedia(Uri uri) {
        if (uri == null) return;
        List<Uri> recent = readUriList("recent_media_uris");
        recent.remove(uri);
        recent.add(0, uri);
        while (recent.size() > 30) recent.remove(recent.size() - 1);
        writeUriList("recent_media_uris", recent);
    }

    private void takePermissions(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    private String getDeviceIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                return Formatter.formatIpAddress(ip);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void castLocalMediaToTv(boolean showToast) {
        String tvIp = prefs.getString("tv_ip", "").trim();
        if (tvIp.isEmpty()) {
            if (showToast) Toast.makeText(this, "يرجى كتابة وحفظ IP الشاشة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        String phoneIp = getDeviceIpAddress();
        if (phoneIp.isEmpty() || phoneIp.equals("0.0.0.0")) {
            if (showToast) Toast.makeText(this, "تأكد من اتصال الهاتف بالواي فاي", Toast.LENGTH_SHORT).show();
            return;
        }

        String mime = getContentResolver().getType(selectedMediaUri);
        String targetUrl = (mime != null && mime.startsWith("image/")) ?
                "http://" + phoneIp + ":8088/media.jpg" : "http://" + phoneIp + ":8088/media.mp4";

        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http")) endpoint = "http://" + endpoint;
            if (!endpoint.contains(":" + TARGET_PORT)) endpoint += ":" + TARGET_PORT;

            final String receiverEndpoint = endpoint;
            String fullUrl = receiverEndpoint + "/?url=" + URLEncoder.encode(targetUrl, "UTF-8");
            Request req = new Request.Builder().url(fullUrl).get().build();
            AtomicBoolean requestHeadersSent = new AtomicBoolean(false);
            OkHttpClient trackedClient = client.newBuilder()
                    .eventListener(new EventListener() {
                        @Override
                        public void requestHeadersEnd(Call call, Request request) {
                            requestHeadersSent.set(true);
                        }
                    }).build();

            trackedClient.newCall(req).enqueue(new Callback() {
                private void showSendResult(String message) {
                    if (showToast) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public void onFailure(Call call, IOException error) {
                    // Receiver may accept the media and start playback, then close the
                    // original HTTP connection before the phone receives its response.
                    if (requestHeadersSent.get()) {
                        showSendResult("تم إرسال الوسائط للشاشة ✓");
                        return;
                    }

                    Request ping = new Request.Builder()
                            .url(receiverEndpoint + "/?command=ping")
                            .get()
                            .build();
                    client.newCall(ping).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call pingCall, IOException pingError) {
                            showSendResult("تعذر الاتصال بالشاشة");
                        }

                        @Override
                        public void onResponse(Call pingCall, Response pingResponse) {
                            boolean receiverAlive = pingResponse.isSuccessful();
                            pingResponse.close();
                            showSendResult(receiverAlive
                                    ? "الشاشة متصلة، لكن لم يصل طلب الوسائط"
                                    : "تعذر تأكيد استقبال الوسائط");
                        }
                    });
                }

                @Override
                public void onResponse(Call call, Response response) {
                    boolean accepted = response.isSuccessful();
                    response.close();
                    showSendResult(accepted
                            ? "تم إرسال الوسائط للشاشة ✓"
                            : "الشاشة رفضت طلب الوسائط");
                }
            });
        } catch (Exception ignored) {}
    }

    private void sendRemoteCommand(String cmd) {
        String tvIp = prefs.getString("tv_ip", "").trim();
        if (tvIp.isEmpty()) return;

        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http")) endpoint = "http://" + endpoint;
            if (!endpoint.contains(":" + TARGET_PORT)) endpoint += ":" + TARGET_PORT;

            String fullUrl = endpoint + "/?command=" + cmd;
            Request req = new Request.Builder().url(fullUrl).build();
            client.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {}
                @Override
                public void onResponse(Call call, Response response) {}
            });
        } catch (Exception ignored) {}
    }

    private void startLocalServer() {
        try {
            if (localServer == null) {
                localServer = new LocalMediaServer(8088);
                localServer.start();
            }
        } catch (Exception ignored) {}
    }

    private class LocalMediaServer extends NanoHTTPD {
        public LocalMediaServer(int port) { super(port); }

        @Override
        public Response serve(IHTTPSession session) {
            // Capture one URI per request; playback may issue several Range requests.
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
                if (fileLength <= 0) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "text/plain", "Unable to determine media length");

                String mime = getContentResolver().getType(mediaUri);
                if (mime == null || mime.trim().isEmpty()
                        || "application/octet-stream".equalsIgnoreCase(mime)) {
                    String name = getMediaDisplayName(mediaUri, 0);
                    if (name != null) {
                        int dot = name.lastIndexOf('.');
                        if (dot >= 0 && dot < name.length() - 1) {
                            String guessed = android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
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
                java.io.InputStream fis = new java.io.FilterInputStream(rawStream) {
                    @Override
                    public void close() throws IOException {
                        try { super.close(); } finally {
                            try { responseAfd.close(); } catch (IOException ignored) {}
                        }
                    }
                };
                long toSkip = start;
                while (toSkip > 0) {
                    long skipped = fis.skip(toSkip);
                    if (skipped <= 0) {
                        if (fis.read() == -1) throw new IOException("Cannot seek to requested range");
                        skipped = 1;
                    }
                    toSkip -= skipped;
                }

                Response response = newFixedLengthResponse(
                        partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                        mime, fis, contentLength);
                response.addHeader("Content-Length", String.valueOf(contentLength));
                response.addHeader("Accept-Ranges", "bytes");
                response.addHeader("Cache-Control", "no-store");
                response.addHeader("Access-Control-Allow-Origin", "*");
                if (partial) response.addHeader("Content-Range",
                        "bytes " + start + "-" + end + "/" + fileLength);
                return response;
            } catch (Exception e) {
                android.util.Log.e("AboEzzatCast", "Failed to serve local media", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                        "Error loading local media");
            } finally {
                // Keep the descriptor open while NanoHTTPD streams the response;
                // the wrapped response stream closes it after transmission.
            }
        }

        private long queryMediaLength(Uri uri) {
            android.database.Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.SIZE}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (column >= 0 && !cursor.isNull(column)) return cursor.getLong(column);
                }
            } catch (Exception e) {
                android.util.Log.w("AboEzzatCast", "Could not query local media size", e);
            } finally {
                if (cursor != null) cursor.close();
            }
            return -1L;
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        slideHandler.removeCallbacks(slideRunnable);
        if (localServer != null) {
            localServer.stop();
        }
    }
}
