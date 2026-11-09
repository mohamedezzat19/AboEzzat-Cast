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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        restoreMediaQueues();
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
        if (getIntent().getBooleanExtra("open_media_picker", false)) {
            new Handler(Looper.getMainLooper()).post(() -> openMediaPicker(false));
        } else if (getIntent().getBooleanExtra("open_library", false)) {
            new Handler(Looper.getMainLooper()).post(() -> showLibraryDialog());
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

        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            // Stop the local slideshow timer first, then stop playback on Receiver.
            isSlideshowRunning = false;
            slideHandler.removeCallbacks(slideRunnable);
            currentSlideIndex = 0;
            currentVideoIndex = 0;
            selectedMediaUri = null;
            persistMediaQueues();
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

        android.widget.ListView listView = new android.widget.ListView(this);
        listView.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(mostVisited ? "الأكثر زيارة" : "سجل التصفح")
                .setView(listView)
                .setNeutralButton("مسح السجل كله", (d, w) -> {
                    SharedPreferences.Editor edit = browserPrefs.edit();
                    for (String url : urls) {
                        edit.remove("history_title_" + url).remove("history_time_" + url)
                                .remove("visit_count_" + url);
                    }
                    edit.putLong("history_cleared_at", System.currentTimeMillis());
                    edit.remove("browser_history").apply();
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
                    SharedPreferences.Editor edit = browserPrefs.edit();
                    for (String url : historyUrls) edit.remove("history_title_" + url)
                            .remove("history_time_" + url).remove("visit_count_" + url);
                    edit.putLong("history_cleared_at", System.currentTimeMillis());
                    edit.remove("browser_history").apply();
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

        if (handleIncomingShareIntent(intent)) return;

        if (intent != null && intent.getBooleanExtra("open_media_picker", false)) {
            new Handler(Looper.getMainLooper()).post(() -> openMediaPicker(false));
        } else if (intent != null && intent.getBooleanExtra("open_library", false)) {
            // The Library tab must always open bookmarks and browsing history.
            // Recent media is a separate action and must not run on Library navigation.
            new Handler(Looper.getMainLooper()).post(() -> showLibraryDialog());
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
                    client.newCall(ping).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call pingCall, IOException pingError) {
                            runOnUiThread(() -> {
                                String message = requestHeadersSent.get()
                                        ? "تم إرسال الطلب، لكن تعذر تأكيد رد الشاشة"
                                        : "تعذر الاتصال بالشاشة";
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                                finish();
                            });
                        }

                        @Override
                        public void onResponse(Call pingCall, Response pingResponse) {
                            boolean receiverAlive = pingResponse.isSuccessful();
                            pingResponse.close();
                            runOnUiThread(() -> {
                                String message = receiverAlive
                                        ? "تم إرسال الطلب للشاشة ✓"
                                        : "تعذر تأكيد استقبال الطلب";
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
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
