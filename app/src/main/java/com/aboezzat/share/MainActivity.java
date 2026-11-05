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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        startLocalServer();

        // Silently check the Classic app's own release channel on launch.
        new AppUpdater(this).checkForUpdate(false);

        if (handleIncomingShareIntent(getIntent())) {
            return;
        }

        initViews();
        setupNavigationDrawer();
        setupBottomNavigation();
        applyDarkMode(findViewById(R.id.drawer_layout), prefs.getBoolean("dark_mode", true));
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
        btnSearchClear.setOnClickListener(v -> etHomeSearch.setText(""));
        btnSearchGo.setOnClickListener(v -> openSearchResult());
        etHomeSearch.setOnEditorActionListener((v, actionId, event) -> { openSearchResult(); return true; });
        btnSearchMic.setOnClickListener(v -> startVoiceSearch());

        btnSave.setOnClickListener(v -> {
            String ip = etTvIp.getText().toString().trim();
            prefs.edit().putString("tv_ip", ip).apply();
            tvStatusBadge.setText("● تم الحفظ");
            Toast.makeText(MainActivity.this, "تم حفظ IP الشاشة ✓", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.card_browser).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, BrowserActivity.class);
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

        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            // Stop the local slideshow timer first, then stop playback on Receiver.
            isSlideshowRunning = false;
            slideHandler.removeCallbacks(slideRunnable);
            slideshowQueue.clear();
            videoQueue.clear();
            currentSlideIndex = 0;
            currentVideoIndex = 0;
            selectedMediaUri = null;
            sendRemoteCommand("stop");
            Toast.makeText(this, "تم إرسال أمر إيقاف الفيديو ⏹", Toast.LENGTH_SHORT).show();
        });

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
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("مكتبة Abo Ezzat Cast")
                    .setItems(new String[]{"الوسائط الأخيرة", "قائمة التشغيل", "العلامات المرجعية", "اختيار ملفات من الهاتف"}, (dialog, which) -> {
                        if (which == 0) showRecentMediaDialog();
                        else if (which == 1) showPlaylistDialog();
                        else if (which == 2) showBookmarksDialog();
                        else openMediaPicker(false);
                    })
                    .setNegativeButton("إغلاق", null)
                    .show();
        });
    }

    private void setupNavigationDrawer() {
        // 1. المتصفح
        findViewById(R.id.menu_browser).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(MainActivity.this, BrowserActivity.class));
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
                    .setItems(new String[]{"التحقق من التحديثات", "إعداد عنوان IP الشاشة"}, (dialog, which) -> {
                        if (which == 0) {
                            new AppUpdater(MainActivity.this).checkForUpdate(true);
                        } else if (etTvIp != null) {
                            etTvIp.requestFocus();
                            Toast.makeText(this, "عدل عنوان IP الشاشة واضغط حفظ", Toast.LENGTH_SHORT).show();
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
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("تعذر فتح الملف");
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("tv_ip", prefs.getString("tv_ip", ""));
            json.put("custom_sites", new org.json.JSONArray(new ArrayList<>(prefs.getStringSet("custom_sites", new HashSet<String>()))));
            json.put("dark_mode", prefs.getBoolean("dark_mode", true));
            out.write(json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Toast.makeText(this, "تم تصدير الإعدادات", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل التصدير: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importSettings(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("تعذر قراءة الملف");
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[4096]; int count;
            while ((count = in.read(bytes)) != -1) buffer.write(bytes, 0, count);
            org.json.JSONObject json = new org.json.JSONObject(buffer.toString("UTF-8"));
            SharedPreferences.Editor edit = prefs.edit();
            if (json.has("tv_ip")) edit.putString("tv_ip", json.optString("tv_ip", ""));
            if (json.has("dark_mode")) edit.putBoolean("dark_mode", json.optBoolean("dark_mode", true));
            if (json.has("custom_sites")) {
                org.json.JSONArray array = json.optJSONArray("custom_sites");
                Set<String> sites = new HashSet<>();
                if (array != null) for (int i = 0; i < array.length(); i++) sites.add(array.optString(i));
                edit.putStringSet("custom_sites", sites);
            }
            edit.apply();
            if (etTvIp != null) etTvIp.setText(prefs.getString("tv_ip", ""));
            updateReceiverStatus();
            applyDarkMode(findViewById(R.id.drawer_layout), prefs.getBoolean("dark_mode", true));
            Toast.makeText(this, "تم استعادة الإعدادات", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل الاستعادة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
        new AlertDialog.Builder(this).setTitle(mostVisited ? "الأكثر زيارة" : "سجل التصفح")
                .setItems(labels, (dialog, which) -> {
                    Intent intent = new Intent(MainActivity.this, BrowserActivity.class);
                    intent.putExtra("url", urls.get(which));
                    startActivity(intent);
                })
                .setNeutralButton("مسح السجل", (dialog, which) -> {
                    SharedPreferences.Editor edit = browserPrefs.edit();
                    for (String url : urls) {
                        edit.remove("history_title_" + url).remove("history_time_" + url)
                                .remove("visit_count_" + url);
                    }
                    edit.remove("browser_history").apply();
                    Toast.makeText(this, "تم مسح سجل التصفح", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إغلاق", null).show();
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

    private void showRecentMediaDialog() {
        List<Uri> recent = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Uri uri : videoQueue) {
            if (uri != null && seen.add(uri.toString())) recent.add(uri);
        }
        for (Uri uri : slideshowQueue) {
            if (uri != null && seen.add(uri.toString())) recent.add(uri);
        }
        if (recent.isEmpty()) {
            Toast.makeText(this, "لا توجد وسائط حديثة. اختر ملفات من قسم الوسائط أولاً.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[recent.size()];
        for (int i = 0; i < recent.size(); i++) labels[i] = getMediaDisplayName(recent.get(i), i);
        new AlertDialog.Builder(this)
                .setTitle("الوسائط الأخيرة")
                .setItems(labels, (dialog, which) -> {
                    Uri chosen = recent.get(which);
                    selectedMediaUri = chosen;
                    if (videoQueue.contains(chosen)) {
                        currentVideoIndex = videoQueue.indexOf(chosen);
                        castLocalMediaToTv(true);
                    } else {
                        currentSlideIndex = slideshowQueue.indexOf(chosen);
                        castLocalMediaToTv(false);
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showPlaylistDialog() {
        if (videoQueue.isEmpty()) {
            Toast.makeText(this, "قائمة التشغيل فارغة، حدد ملفات من قسم الوسائط", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[videoQueue.size()];
        for (int i = 0; i < videoQueue.size(); i++) {
            items[i] = getMediaDisplayName(videoQueue.get(i), i);
        }
        new AlertDialog.Builder(this)
                .setTitle("قائمة التشغيل (" + videoQueue.size() + " ملفات)")
                .setItems(items, (dialog, which) -> {
                    currentVideoIndex = which;
                    selectedMediaUri = videoQueue.get(currentVideoIndex);
                    castLocalMediaToTv(true);
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showBookmarksDialog() {
        Set<String> customSites = prefs.getStringSet("custom_sites", new HashSet<>());
        if (customSites == null || customSites.isEmpty()) {
            Toast.makeText(this, "لا توجد علامات مرجعية محفوظة بعد", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> names = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (String entry : customSites) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length == 2) {
                names.add(parts[0]);
                urls.add(parts[1]);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("العلامات المرجعية المفضلة")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    Intent intent = new Intent(MainActivity.this, BrowserActivity.class);
                    intent.putExtra("url", urls.get(which));
                    startActivity(intent);
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void openDownloads() {
        try {
            Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                Uri uri = Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath());
                intent.setDataAndType(uri, "*/*");
                startActivity(Intent.createChooser(intent, "فتح التنزيلات"));
            } catch (Exception ex) {
                Toast.makeText(this, "تعذر فتح التنزيلات تلقائياً", Toast.LENGTH_SHORT).show();
            }
        }
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

    private void openSearchResult() {
        String query = etHomeSearch.getText().toString().trim();
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
        handleIncomingShareIntent(intent);
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
        String lowerUrl = url.toLowerCase();
        boolean isBrowserRequired = lowerUrl.contains("facebook.com") || lowerUrl.contains("fb.watch") || 
                                    lowerUrl.contains("fb.com") || lowerUrl.contains("instagram.com");

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
            return;
        }

        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http")) endpoint = "http://" + endpoint;
            if (!endpoint.contains(":" + TARGET_PORT)) endpoint += ":" + TARGET_PORT;

            String fullUrl = endpoint + "/?url=" + URLEncoder.encode(mediaUrl, "UTF-8");
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
                    runOnUiThread(() -> {
                        String message = requestHeadersSent.get()
                                ? "تم إرسال الطلب للشاشة ✓"
                                : "تعذر الاتصال بالشاشة";
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) {
                    boolean accepted = response.isSuccessful();
                    response.close();
                    runOnUiThread(() -> {
                        String message = accepted
                                ? "تم الإرسال والتشغيل فوراً ✓"
                                : "الشاشة رفضت الطلب (HTTP)";
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }
            });
        } catch (Exception ignored) {
            finish();
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
                    selectedMediaUri = videoQueue.get(0);
                    castLocalMediaToTv(true);
                    if (videoQueue.size() > 1) {
                        Toast.makeText(this, "تم تحميل (" + videoQueue.size() + ") وسائط، استخدم التالي والسابق للتنقل", Toast.LENGTH_LONG).show();
                    }
                }
            } else if (requestCode == PICK_SLIDESHOW_REQUEST) {
                videoQueue.clear();
                slideshowQueue.clear();
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

            String fullUrl = endpoint + "/?url=" + URLEncoder.encode(targetUrl, "UTF-8");
            Request req = new Request.Builder().url(fullUrl).build();
            client.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (showToast) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "فشل الإرسال للشاشة", Toast.LENGTH_SHORT).show());
                    }
                }

                @Override
                public void onResponse(Call call, Response response) {
                    if (showToast) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "جاري العرض على الشاشة ✓", Toast.LENGTH_SHORT).show());
                    }
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
        public LocalMediaServer(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            if (selectedMediaUri != null) {
                try {
                    AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(selectedMediaUri, "r");
                    if (afd != null) {
                        long fileLength = afd.getLength();
                        String mime = getContentResolver().getType(selectedMediaUri);
                        if (mime == null) mime = "application/octet-stream";

                        Map<String, String> headers = session.getHeaders();
                        String range = headers.get("range");

                        if (range != null && range.startsWith("bytes=")) {
                            String[] parts = range.substring("bytes=".length()).split("-");
                            long start = Long.parseLong(parts[0]);
                            long end = (parts.length > 1 && !parts[1].isEmpty()) ? Long.parseLong(parts[1]) : fileLength - 1;
                            if (end >= fileLength) end = fileLength - 1;
                            long contentLength = end - start + 1;

                            FileInputStream fis = afd.createInputStream();
                            fis.skip(start);

                            Response res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, contentLength);
                            res.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
                            res.addHeader("Content-Length", String.valueOf(contentLength));
                            res.addHeader("Accept-Ranges", "bytes");
                            res.addHeader("Access-Control-Allow-Origin", "*");
                            return res;
                        } else {
                            FileInputStream fis = afd.createInputStream();
                            Response res = newFixedLengthResponse(Response.Status.OK, mime, fis, fileLength);
                            res.addHeader("Content-Length", String.valueOf(fileLength));
                            res.addHeader("Accept-Ranges", "bytes");
                            res.addHeader("Access-Control-Allow-Origin", "*");
                            return res;
                        }
                    }
                } catch (Exception e) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error loading file");
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No media selected");
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
