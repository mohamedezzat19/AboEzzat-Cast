package com.aboezzat.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.format.Formatter;
import android.util.Base64;
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
import java.util.UUID;
import java.security.KeyStore;
import java.nio.ByteBuffer;
import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
    private static final String PREF_RECEIVER_SECRET = "receiver_auth_secret_";
    private static final int PICK_MEDIA_REQUEST = 101;
    private static final int PICK_SLIDESHOW_REQUEST = 102;
    private static final int VOICE_SEARCH_REQUEST = 103;

    private static final String PREF_SAVED_RECEIVERS = "saved_receivers_json";
    private static class SavedReceiverDevice {
        String name;
        String ip;
        SavedReceiverDevice(String name, String ip) {
            this.name = name == null ? "" : name;
            this.ip = ip == null ? "" : ip;
        }
    }

    private DrawerLayout drawerLayout;
    private EditText etTvIp;
    private EditText etHomeSearch;
    private TextView tvStatusBadge;
    private SharedPreferences prefs;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                Request original = chain.request();
                if (original.url().port() == TARGET_PORT
                        && !"/pair".equals(original.url().encodedPath())) {
                    String secret = decryptReceiverSecret(getSharedPreferences("AboEzzatSharePrefs",
                            MODE_PRIVATE | MODE_MULTI_PROCESS).getString(PREF_RECEIVER_SECRET + original.url().host(), ""));
                    if (secret != null && !secret.isEmpty()) {
                        long timestamp = System.currentTimeMillis();
                        String nonce = UUID.randomUUID().toString();
                        String pathAndQuery = original.url().encodedPath()
                                + (original.url().encodedQuery() == null ? ""
                                : "?" + original.url().encodedQuery());
                        String signature;
                        try {
                            byte[] requestBody = new byte[0];
                            if (original.body() != null) {
                                okio.Buffer bodyBuffer = new okio.Buffer();
                                original.body().writeTo(bodyBuffer);
                                requestBody = bodyBuffer.readByteArray();
                            }
                            String bodyHash = Base64.encodeToString(
                                    java.security.MessageDigest.getInstance("SHA-256").digest(requestBody),
                                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                            String canonical = original.method() + "\n" + pathAndQuery
                                    + "\n" + bodyHash + "\n" + timestamp + "\n" + nonce;
                            Mac mac = Mac.getInstance("HmacSHA256");
                            mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "HmacSHA256"));
                            signature = Base64.encodeToString(
                                    mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                        } catch (Exception signingError) {
                            throw new IOException("Unable to sign receiver request", signingError);
                        }
                        Request signed = original.newBuilder()
                                .header("X-AboEzzat-Time", String.valueOf(timestamp))
                                .header("X-AboEzzat-Nonce", nonce)
                                .header("X-AboEzzat-Signature", signature)
                                .build();
                        return chain.proceed(signed);
                    }
                }
                return chain.proceed(original);
            })
            .build();
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
                setSelectedMediaUri(slideshowQueue.get(currentSlideIndex));
                castLocalMediaToTv(false);
                slideHandler.postDelayed(this, 5000);
            }
        }
    };

    private boolean clipboardShortcutLaunch;
    private boolean webVideoCasterCaptureLaunch;
    private boolean accessibilityReminderShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        clipboardShortcutLaunch = getIntent() != null
                && getIntent().getBooleanExtra("clipboard_cast_shortcut", false);
        webVideoCasterCaptureLaunch = getIntent() != null
                && getIntent().getBooleanExtra("web_video_caster_capture_shortcut", false);
        if (clipboardShortcutLaunch || webVideoCasterCaptureLaunch)
            setTheme(android.R.style.Theme_Translucent_NoTitleBar);
        super.onCreate(savedInstanceState);

        // Start the app-wide foreground session while the user is opening the app.
        CastKeepAliveService.start(this);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8403);
        }

        prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
        restoreMediaQueues();

        // The shortcut uses a separate translucent task: process the copied link
        // without showing the normal Cast home screen.
        if (handleClipboardCastShortcut(getIntent())) return;
        if (handleWebVideoCasterCaptureShortcut(getIntent())) return;

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
        String pendingSendUrl = getIntent().getStringExtra("send_saved_url_to_tv");
        if (pendingSendUrl != null && !pendingSendUrl.trim().isEmpty()) {
            final String urlToSend = pendingSendUrl.trim();
            new Handler(Looper.getMainLooper()).post(() -> sendUrlToTvFromUi(urlToSend));
            getIntent().removeExtra("send_saved_url_to_tv");
        }
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
        tvStatusBadge.setOnClickListener(v -> showSavedReceiverDialog());
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
                setSelectedMediaUri(slideshowQueue.get(currentSlideIndex));
                castLocalMediaToTv(false);
            } else if (videoQueue.size() > 1 && currentVideoIndex < videoQueue.size() - 1) {
                currentVideoIndex++;
                setSelectedMediaUri(videoQueue.get(currentVideoIndex));
                castLocalMediaToTv(true);
            } else {
                sendRemoteCommand("next");
            }
        });

        findViewById(R.id.btn_prev).setOnClickListener(v -> {
            if (!slideshowQueue.isEmpty()) {
                currentSlideIndex = (currentSlideIndex - 1 + slideshowQueue.size()) % slideshowQueue.size();
                setSelectedMediaUri(slideshowQueue.get(currentSlideIndex));
                castLocalMediaToTv(false);
            } else if (videoQueue.size() > 1 && currentVideoIndex > 0) {
                currentVideoIndex--;
                setSelectedMediaUri(videoQueue.get(currentVideoIndex));
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

        // FG Code has its own entry in the main navigation drawer.
        findViewById(R.id.menu_fgcode).setOnClickListener(v -> {
            closeDrawer();
            Intent fgIntent = new Intent(MainActivity.this, BrowserActivity.class);
            fgIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            fgIntent.putExtra("open_fg_code_dialog", true);
            startActivity(fgIntent);
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
            new AlertDialog.Builder(MainActivity.this, R.style.ModernDialogTheme)
                    .setTitle("إعدادات Abo Ezzat Share")
                    .setItems(new String[]{"التحقق من التحديثات", "إعداد عنوان IP الشاشة", "تفعيل الاختصار العائم عند نسخ رابط", "تشخيص مشكلة الفقاعة", "إعدادات أندرويد وأذونات التطبيق", "إعداد VPN داخل التطبيق", "🔐 ربط آمن مع الريسيفر"}, (dialog, which) -> {
                        if (which == 0) {
                            new AppUpdater(MainActivity.this).checkForUpdate(true);
                        } else if (which == 1) {
                            showManualIpDialog();
                        } else if (which == 2) {
                            openClipboardShortcutSetup();
                        } else if (which == 3) {
                            showClipboardShortcutDiagnostics();
                        } else if (which == 4) {
                            openAppPermissionSettings();
                        } else if (which == 5) {
                            startActivity(new Intent(MainActivity.this, VpnActivity.class));
                        } else if (which == 6) {
                            showReceiverPairingDialog();
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
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("اختصار AboEzzat Cast العائم")
                .setMessage("لتشغيل الاختصار العائم، فعّل الإذنين التاليين مرة واحدة:\n\n1) السماح بالظهور فوق التطبيقات الأخرى.\n2) تفعيل خدمة إمكانية الوصول الخاصة بـ AboEzzat Cast.\n\nالخدمة تراقب أحداث Web Video Caster فقط، وتقرأ عناوين الروابط التي يتيحها التطبيق ضمن عناصر إمكانية الوصول؛ لا تصوّر الشاشة ولا تضغط داخل Web Video Caster ولا تقرأ ملفاته الخاصة. كما تحتفظ باختصار النسخ الحالي. تظهر الفقاعة عند العثور على رابط ويمكنك سحبها لمكان مناسب.")
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

    private void showClipboardShortcutDiagnostics() {
        SharedPreferences diagnosticPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
        boolean serviceConnected = diagnosticPrefs.getBoolean("diag_service_connected", false);
        boolean accessibilityMasterEnabled = isAccessibilityMasterSwitchEnabled();
        boolean accessibilityComponentEnabled = isClipboardAccessibilityComponentEnabled();
        int clipboardCount = diagnosticPrefs.getInt("diag_clipboard_count", 0);
        String mime = diagnosticPrefs.getString("diag_last_clipboard_mime", "لا توجد بيانات");
        String lastPackage = diagnosticPrefs.getString("diag_last_accessibility_package", "لا توجد أحداث");
        int eventType = diagnosticPrefs.getInt("diag_last_accessibility_type", 0);
        int copyEventCount = diagnosticPrefs.getInt("diag_accessibility_copy_count", 0);
        String lastCopyPackage = diagnosticPrefs.getString("diag_last_copy_package", "لم يتم رصد أمر نسخ");
        String clipboardHost = diagnosticPrefs.getString("diag_last_clipboard_host", "لم يتم فحص نافذة النظام بعد");
        boolean systemClipboardMatch = diagnosticPrefs.getBoolean("diag_last_systemui_url_match", false);
        String bubbleResult = diagnosticPrefs.getString("diag_last_bubble_result", "لا توجد بيانات");
        int sourceEvents = diagnosticPrefs.getInt("diag_source_event_count", 0);
        int sourceClicks = diagnosticPrefs.getInt("diag_source_click_count", 0);
        String sourcePackage = diagnosticPrefs.getString("diag_last_source_package", "لا توجد بيانات");
        String sourceText = diagnosticPrefs.getString("diag_last_source_text", "لا توجد بيانات");
        String clickPackage = diagnosticPrefs.getString("diag_last_click_package", "لا توجد بيانات");
        String clickText = diagnosticPrefs.getString("diag_last_click_text", "لا توجد بيانات");
        String wvcUrls = diagnosticPrefs.getString("wvc_captured_urls", "[]");
        String lifecycleLog = diagnosticPrefs.getString("diag_lifecycle_log", "لا توجد أحداث لدورة حياة الخدمة بعد");
        String lastLifecycle = diagnosticPrefs.getString("diag_last_lifecycle_event", "لا يوجد");
        int wvcCount = 0;
        try { wvcCount = new org.json.JSONArray(wvcUrls).length(); } catch (Exception ignored) {}
        boolean overlay = android.provider.Settings.canDrawOverlays(this);
        String report = "تشخيص اختصار AboEzzat Cast\n"
                + "مفتاح إمكانية الوصول العام: " + (accessibilityMasterEnabled ? "مفعّل" : "متوقف") + "\n"
                + "خدمة AboEzzat ضمن الخدمات المفعّلة: " + (accessibilityComponentEnabled ? "نعم" : "لا") + "\n"
                + "خدمة إمكانية الوصول متصلة (آخر حالة مسجلة): " + (serviceConnected ? "نعم" : "لا") + "\n"
                + "إذن الظهور فوق التطبيقات: " + (overlay ? "مفعّل" : "غير مفعّل") + "\n"
                + "مرات وصول حدث الحافظة: " + clipboardCount + "\n"
                + "آخر أنواع MIME: " + mime + "\n"
                + "آخر تطبيق أرسل حدث إمكانية الوصول: " + lastPackage + "\n"
                + "نوع آخر حدث: " + eventType + "\n"
                + "مرات رصد أمر نسخ مؤكد: " + copyEventCount + "\n"
                + "آخر تطبيق رُصد منه أمر نسخ: " + lastCopyPackage + "\n"
                + "عدد أحداث التطبيقات المصدر: " + sourceEvents + "\n"
                + "عدد أحداث الضغط في التطبيقات المصدر: " + sourceClicks + "\n"
                + "آخر تطبيق مصدر: " + sourcePackage + "\n"
                + "نص آخر حدث مصدر: " + sourceText + "\n"
                + "آخر تطبيق حدث فيه ضغط: " + clickPackage + "\n"
                + "نص آخر عنصر تم ضغطه: " + clickText + "\n"
                + "عدد روابط Web Video Caster الملتقطة: " + wvcCount + "\n"
                + "روابط Web Video Caster: " + wvcUrls + "\n"
                + "آخر نافذة نظام تم فحصها: " + clipboardHost + "\n"
                + "هل عُثر على رابط HTTP/HTTPS في نافذة النظام؟ " + (systemClipboardMatch ? "نعم" : "لا") + "\n"
                + "نتيجة عرض الفقاعة: " + bubbleResult + "\n"
                + "آخر حدث لدورة حياة الخدمة: " + lastLifecycle + "\n"
                + "سجل تشغيل/إغلاق الخدمة والتطبيق:\n" + lifecycleLog;
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("تشخيص مشكلة الفقاعة")
                .setMessage(report)
                .setPositiveButton("نسخ التقرير", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("AboEzzat Cast diagnostics", report));
                        Toast.makeText(this, "تم نسخ تقرير التشخيص", Toast.LENGTH_SHORT).show();
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
        new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("النسخ الاحتياطي والاستعادة")
                .setItems(new String[]{"تصدير الإعدادات إلى ملف", "استعادة الإعدادات من ملف"}, (dialog, which) -> {
                    Intent intent = new Intent(which == 0 ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(which == 0 ? "application/json" : "*/*");
                    if (which == 0) intent.putExtra(Intent.EXTRA_TITLE, "AboEzzatCast-settings.json");
                    startActivityForResult(intent, which == 0 ? 501 : 502);
                }).setNegativeButton("إغلاق", null).show();
    }

    private void exportSettings(Uri uri) {
        SharedPreferences browserPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
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
        SharedPreferences browserPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
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
        setSelectedMediaUri(null);
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

    private String getCompactSavedUrlLabel(String url) {
        if (url == null || url.trim().isEmpty()) return "رابط محفوظ";
        try {
            Uri parsed = Uri.parse(url);
            String host = parsed.getHost();
            if (host != null && !host.trim().isEmpty()) return host;
        } catch (Exception ignored) {}
        return "رابط محفوظ";
    }

    private void showFullUrlDialog(String title, String url) {
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle(title == null || title.trim().isEmpty() ? "العنوان الكامل" : title)
                .setMessage(url == null ? "" : url)
                .setPositiveButton("نسخ الرابط", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null && url != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("URL", url));
                        Toast.makeText(this, "تم نسخ الرابط الكامل", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showBrowserHistory(boolean mostVisited) {
        SharedPreferences browserPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
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
            new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle(mostVisited ? "الأكثر زيارة" : "سجل التصفح")
                    .setMessage("لسه مفيش صفحات اتسجلت. افتح مواقع من المتصفح الأول.")
                    .setPositiveButton("فتح المتصفح", (d, w) -> startActivity(new Intent(this, BrowserActivity.class)))
                    .setNegativeButton("إغلاق", null).show();
            return;
        }

        String[] labels = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            String title = browserPrefs.getString("history_title_" + url, "");
            if (title == null || title.trim().isEmpty() || title.trim().equals(url)
                    || title.trim().startsWith("http://") || title.trim().startsWith("https://"))
                title = getCompactSavedUrlLabel(url);
            labels[i] = (mostVisited ? (browserPrefs.getInt("visit_count_" + url, 0) + " زيارة · ") : "")
                    + title;
        }

        android.widget.ListView listView = new android.widget.ListView(this);
        listView.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels));
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme)
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
            String title = browserPrefs.getString("history_title_" + url, "");
            if (title == null || title.trim().isEmpty() || title.trim().equals(url)
                    || title.trim().startsWith("http://") || title.trim().startsWith("https://"))
                title = getCompactSavedUrlLabel(url);
            showLinkActionsDialog(title, url, () -> {
                Set<String> updated = new HashSet<>(browserPrefs.getStringSet("browser_history", new HashSet<String>()));
                updated.remove(url);
                browserPrefs.edit().putStringSet("browser_history", updated)
                        .remove("history_title_" + url).remove("history_time_" + url)
                        .remove("visit_count_" + url).apply();
                dialog.dismiss();
                Toast.makeText(MainActivity.this, "تم حذف الصفحة من السجل", Toast.LENGTH_SHORT).show();
                showBrowserHistory(mostVisited);
            }, () -> sendUrlToTvFromUi(url));
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

        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("تشغيل IPTV على الشاشة")
                .setView(input)
                .setPositiveButton("بث مباشر 📺", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) castUrlDirectlyToTv(url);
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
        SharedPreferences browserPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
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
                    showLinkActionsDialog(names.get(index), urls.get(index), () -> {
                        Set<String> updated = new HashSet<>(browserPrefs.getStringSet("custom_sites", new HashSet<String>()));
                        updated.remove(entries.get(index));
                        browserPrefs.edit().putStringSet("custom_sites", updated).apply();
                        Toast.makeText(MainActivity.this, "تم حذف العلامة المرجعية", Toast.LENGTH_SHORT).show();
                        showLibraryDialog();
                    }, () -> sendUrlToTvFromUi(urls.get(index)));
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
            String pageTitle = browserPrefs.getString("history_title_" + url, "");
            if (pageTitle == null || pageTitle.trim().isEmpty() || pageTitle.equals(url))
                pageTitle = getCompactSavedUrlLabel(url);
            TextView card = addLibraryCard(content, pageTitle);
            card.setOnClickListener(v -> openLibraryUrl(pageUrl));
            card.setOnLongClickListener(v -> {
                String name = browserPrefs.getString("history_title_" + pageUrl, pageUrl);
                showLinkActionsDialog(name, pageUrl, () -> {
                    Set<String> updated = new HashSet<>(browserPrefs.getStringSet("browser_history", new HashSet<String>()));
                    updated.remove(pageUrl);
                    browserPrefs.edit().putStringSet("browser_history", updated)
                            .remove("history_title_" + pageUrl).remove("history_time_" + pageUrl)
                            .remove("visit_count_" + pageUrl).apply();
                    Toast.makeText(MainActivity.this, "تم حذف الصفحة من السجل", Toast.LENGTH_SHORT).show();
                    showLibraryDialog();
                }, () -> sendUrlToTvFromUi(pageUrl));
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
        clearHistory.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this, R.style.ModernDialogTheme)
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
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme).create();
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


    private void showLinkActionsDialog(String title, String url, Runnable deleteAction, Runnable sendAction) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(12));
        TextView fullUrl = new TextView(this);
        fullUrl.setText(url);
        fullUrl.setTextColor(0xFFE5E7EB);
        fullUrl.setTextSize(12);
        fullUrl.setTextIsSelectable(true);
        fullUrl.setTextDirection(View.TEXT_DIRECTION_LTR);
        fullUrl.setPadding(dp(4), dp(8), dp(4), dp(14));
        panel.addView(fullUrl);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        panel.addView(actions);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle(title == null || title.trim().isEmpty() ? "إجراءات الرابط" : title)
                .setView(panel)
                .setNegativeButton("إغلاق", null)
                .create();
        addLinkActionButton(actions, dialog, "🗑️", "مسح الرابط", deleteAction, 0xFFB3261E);
        addLinkActionButton(actions, dialog, "📋", "نسخ الرابط", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("URL", url));
                Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show();
            }
        }, 0xFF245B8F);
        addLinkActionButton(actions, dialog, "📤", "إرسال الرابط", sendAction, 0xFF287A52);
        dialog.show();
    }

    private void addLinkActionButton(LinearLayout parent, AlertDialog dialog, String icon, String label,
                                     Runnable action, int backgroundColor) {
        TextView button = new TextView(this);
        button.setText(icon + "\n" + label);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(12);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(4), dp(12), dp(4), dp(12));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(backgroundColor);
        shape.setCornerRadius(dp(12));
        button.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(76), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(button, params);
        button.setOnClickListener(v -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
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
            new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("الوسائط الأخيرة")
                    .setMessage("لا توجد وسائط حديثة محفوظة. اختر فيديو أو صورة من زر الوسائط لإضافتها هنا.")
                    .setPositiveButton("اختيار ملفات", (d, w) -> openMediaPicker(false))
                    .setNegativeButton("إغلاق", null).show();
            return;
        }
        String[] labels = new String[recent.size()];
        for (int i = 0; i < recent.size(); i++) labels[i] = getMediaDisplayName(recent.get(i), i);
        new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("الوسائط الأخيرة")
                .setItems(labels, (dialog, which) -> {
                    Uri chosen = recent.get(which);
                    setSelectedMediaUri(chosen);
                    if (videoQueue.contains(chosen)) currentVideoIndex = videoQueue.indexOf(chosen);
                    if (slideshowQueue.contains(chosen)) currentSlideIndex = slideshowQueue.indexOf(chosen);
                    castLocalMediaToTv(true);
                }).setNegativeButton("إغلاق", null).show();
    }

    private void showPlaylistDialog() {
        if (videoQueue.isEmpty()) {
            new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("قائمة التشغيل")
                    .setMessage("قائمة التشغيل فارغة. أضف فيديوهات من زر الوسائط أولاً.")
                    .setPositiveButton("إضافة فيديوهات", (d, w) -> openMediaPicker(false))
                    .setNegativeButton("إغلاق", null).show();
            return;
        }
        String[] items = new String[videoQueue.size()];
        for (int i = 0; i < videoQueue.size(); i++) items[i] = getMediaDisplayName(videoQueue.get(i), i);
        new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("قائمة التشغيل (" + videoQueue.size() + " ملفات)")
                .setItems(items, (dialog, which) -> {
                    currentVideoIndex = which;
                    setSelectedMediaUri(videoQueue.get(currentVideoIndex));
                    castLocalMediaToTv(true);
                }).setPositiveButton("إضافة فيديوهات", (d, w) -> openMediaPicker(false))
                .setNegativeButton("إغلاق", null).show();
    }

    private void showBookmarksDialog() {
        SharedPreferences bookmarkPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
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
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme)
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
            showLinkActionsDialog(names.get(position), urls.get(position), () -> {
                Set<String> updated = new HashSet<>(bookmarkPrefs.getStringSet("custom_sites", new HashSet<String>()));
                updated.remove(entry);
                bookmarkPrefs.edit().putStringSet("custom_sites", updated).apply();
                dialog.dismiss();
                Toast.makeText(MainActivity.this, "تم حذف العلامة المرجعية", Toast.LENGTH_SHORT).show();
                showBookmarksDialog();
            }, () -> sendUrlToTvFromUi(urls.get(position)));
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
            labels.add(host == null || host.trim().isEmpty() ? "وسائط محفوظة" : host);
        }

        android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels));
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme)
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
            showLinkActionsDialog(getCompactSavedUrlLabel(url), url, () -> {
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
            }, () -> sendUrlToTvFromUi(url));
            return true;
        });
        dialog.show();
    }

    private void showAddSavedMediaDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https:// رابط الفيديو أو الصوت المباشر");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
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
        DownloadManagerDialog.show(this, "all", "");
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
        new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("إدخال IP يدويًا").setView(input)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) {
                        prefs.edit().putString("tv_ip", ip).apply();
                        etTvIp.setText(ip);
                        updateReceiverStatus();
                    }
                }).setNegativeButton("إلغاء", null).show();
    }

    private void showReceiverPairingDialog() {
        String tvIp = prefs.getString("tv_ip", "").trim();
        if (tvIp.isEmpty()) {
            Toast.makeText(this, "احفظ عنوان IP الشاشة أولاً", Toast.LENGTH_LONG).show();
            showManualIpDialog();
            return;
        }
        EditText codeInput = new EditText(this);
        codeInput.setSingleLine(true);
        codeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        codeInput.setHint("كود الربط المكوّن من 8 أرقام");
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density + 0.5f);
        panel.setPadding(padding, padding / 2, padding, 0);
        TextView instructions = new TextView(this);
        instructions.setText("على جهاز الريسيفر: الإعدادات > إظهار كود الربط الآمن. تأكد أن الجهازين على نفس شبكة Wi-Fi، ثم أدخل الكود الظاهر على الشاشة.");
        panel.addView(instructions);
        panel.addView(codeInput);
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("🔐 ربط آمن مع الريسيفر")
                .setView(panel)
                .setPositiveButton("ربط الآن", (dialog, which) -> {
                    String code = codeInput.getText().toString().trim();
                    if (!code.matches("[0-9]{8}")) {
                        Toast.makeText(this, "أدخل كودًا صحيحًا من 8 أرقام", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        String endpoint = tvIp;
                        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://"))
                            endpoint = "http://" + endpoint;
                        if (!endpoint.contains(":" + TARGET_PORT)) endpoint += ":" + TARGET_PORT;
                        String pairUrl = endpoint + "/pair?code=" + URLEncoder.encode(code, "UTF-8");
                        Request request = new Request.Builder().url(pairUrl).get().build();
                        client.newCall(request).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException error) {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                        "تعذر الاتصال بالريسيفر. تأكد من الشبكة وعنوان IP.", Toast.LENGTH_LONG).show());
                            }
                            @Override public void onResponse(Call call, Response response) throws IOException {
                                String responseText = response.body() == null ? "" : response.body().string();
                                boolean successful = response.isSuccessful();
                                response.close();
                                String secret = "";
                                try {
                                    if (successful) secret = new org.json.JSONObject(responseText).optString("secret", "");
                                } catch (Exception ignored) { }
                                final String pairedSecret = secret;
                                runOnUiThread(() -> {
                                    if (successful && pairedSecret.length() >= 40) {
                                        String pairedHost = Uri.parse(endpoint).getHost();
                                        prefs.edit().putString(PREF_RECEIVER_SECRET + pairedHost,
                                                encryptReceiverSecret(pairedSecret)).apply();
                                        Toast.makeText(MainActivity.this,
                                                "✅ تم الربط الآمن بنجاح. تقدر تبعت للشاشة دلوقتي.",
                                                Toast.LENGTH_LONG).show();
                                    } else {
                                        Toast.makeText(MainActivity.this,
                                                "فشل الربط: الكود غير صحيح أو انتهت صلاحيته. اعرض كودًا جديدًا من الريسيفر.",
                                                Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        });
                    } catch (Exception error) {
                        Toast.makeText(this, "تعذر تجهيز طلب الربط", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
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

    private boolean isAccessibilityMasterSwitchEnabled() {
        try {
            return android.provider.Settings.Secure.getInt(
                    getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isClipboardAccessibilityComponentEnabled() {
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

    private boolean isClipboardAccessibilityEnabled() {
        // Android has two separate settings: the global accessibility switch
        // and the enabled-service component list. Both must be on.
        return isAccessibilityMasterSwitchEnabled()
                && isClipboardAccessibilityComponentEnabled();
    }

    private void remindIfAccessibilityDisabled() {
        if (clipboardShortcutLaunch || accessibilityReminderShown || isFinishing()
                || isClipboardAccessibilityEnabled()) return;

        // If MIUI/Android disables the service after an earlier reminder,
        // allow a fresh reminder on a later app visit without showing it on
        // every resume while the user is navigating Settings.
        android.content.SharedPreferences reminderPrefs =
                getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
        long now = System.currentTimeMillis();
        long lastReminder = reminderPrefs.getLong("accessibility_reminder_last_shown", 0L);
        if (now - lastReminder < 24L * 60L * 60L * 1000L) return;

        accessibilityReminderShown = true;
        reminderPrefs.edit().putLong("accessibility_reminder_last_shown", now).apply();

        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("زر النسخ العائم غير مفعّل")
                .setMessage("لتفعيل اختصار النسخ العائم، فعّل خدمة «اختصار AboEzzat Cast عند النسخ» من إعدادات إمكانية الوصول. لن نكرر هذا التنبيه خلال نفس إصدار التطبيق.")
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


    private List<SavedReceiverDevice> loadSavedReceiverDevices() {
        List<SavedReceiverDevice> devices = new ArrayList<>();
        String raw = prefs.getString(PREF_SAVED_RECEIVERS, "[]");
        try {
            org.json.JSONArray array = new org.json.JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                String ip = item.optString("ip", "").trim();
                if (!ip.isEmpty() && !containsReceiverIp(devices, ip)) {
                    devices.add(new SavedReceiverDevice(name.isEmpty() ? "شاشة " + (devices.size() + 1) : name, ip));
                }
            }
        } catch (Exception ignored) { }
        String selectedIp = prefs.getString("tv_ip", "").trim();
        if (!selectedIp.isEmpty() && !containsReceiverIp(devices, selectedIp)) {
            devices.add(0, new SavedReceiverDevice("الشاشة الحالية", selectedIp));
            saveSavedReceiverDevices(devices);
        }
        return devices;
    }

    private boolean containsReceiverIp(List<SavedReceiverDevice> devices, String ip) {
        for (SavedReceiverDevice device : devices) {
            if (device.ip.equalsIgnoreCase(ip)) return true;
        }
        return false;
    }

    private void saveSavedReceiverDevices(List<SavedReceiverDevice> devices) {
        org.json.JSONArray array = new org.json.JSONArray();
        try {
            for (SavedReceiverDevice device : devices) {
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("name", device.name);
                item.put("ip", device.ip);
                array.put(item);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString(PREF_SAVED_RECEIVERS, array.toString()).apply();
    }

    private void selectSavedReceiver(SavedReceiverDevice device, AlertDialog dialog) {
        prefs.edit().putString("tv_ip", device.ip).apply();
        if (etTvIp != null) etTvIp.setText(device.ip);
        updateReceiverStatus();
        if (dialog != null) dialog.dismiss();
    }

    private boolean isReceiverOnline(String ip) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, TARGET_PORT), 700);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showSavedReceiverDialog() {
        List<SavedReceiverDevice> devices = loadSavedReceiverDevices();
        final String activeIp = prefs.getString("tv_ip", "").trim();
        // Keep the currently selected receiver at the top; all other saved
        // receivers remain below it in their existing saved order.
        devices.sort((a, b) -> {
            boolean aActive = a.ip.equalsIgnoreCase(activeIp);
            boolean bActive = b.ip.equalsIgnoreCase(activeIp);
            return Boolean.compare(bActive, aActive);
        });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 18, 24, 14);
        root.setBackgroundColor(Color.rgb(15, 23, 42));

        TextView heading = new TextView(this);
        heading.setText("الأجهزة المحفوظة");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(19);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(4, 4, 4, 14);
        root.addView(heading);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (devices.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("مفيش أجهزة محفوظة لسه. اضغط «إضافة جهاز» لحفظ عنوان شاشة.");
            empty.setTextColor(0xFFCBD5E1);
            empty.setTextSize(14);
            empty.setPadding(8, 18, 8, 18);
            list.addView(empty);
        }
        final AlertDialog[] dialogRef = new AlertDialog[1];
        for (SavedReceiverDevice device : devices) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(14, 11, 14, 10);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(0xFF1E293B);
            cardBg.setCornerRadius(16);
            card.setBackground(cardBg);
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(this);
            title.setText(device.name);
            title.setTextColor(Color.WHITE);
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView status = new TextView(this);
            boolean isActiveDevice = device.ip.equalsIgnoreCase(activeIp);
            status.setText(isActiveDevice ? "◆ نشط" : "● محفوظ");
            status.setTextColor(isActiveDevice ? 0xFFFBBF24 : 0xFF94A3B8);
            status.setTextSize(11);
            top.addView(status);
            card.addView(top);
            TextView ip = new TextView(this);
            ip.setText(device.ip);
            ip.setTextColor(0xFF94A3B8);
            ip.setTextSize(12);
            ip.setPadding(0, 4, 0, 8);
            card.addView(ip);
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            TextView edit = new TextView(this);
            edit.setText("تعديل");
            edit.setTextColor(0xFFBFDBFE);
            edit.setGravity(Gravity.CENTER);
            edit.setPadding(18, 10, 18, 10);
            GradientDrawable editBg = new GradientDrawable();
            editBg.setColor(0xFF172554);
            editBg.setCornerRadius(12);
            editBg.setStroke(1, 0xFF3B82F6);
            edit.setBackground(editBg);
            TextView delete = new TextView(this);
            delete.setText("حذف");
            delete.setTextColor(0xFFFECACA);
            delete.setGravity(Gravity.CENTER);
            delete.setPadding(18, 10, 18, 10);
            GradientDrawable deleteBg = new GradientDrawable();
            deleteBg.setColor(0xFF3F1D2A);
            deleteBg.setCornerRadius(12);
            deleteBg.setStroke(1, 0xFFB45369);
            delete.setBackground(deleteBg);
            LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(-2, -2);
            editParams.setMargins(0, 0, dp(12), 0);
            actions.addView(edit, editParams);
            actions.addView(delete);
            card.addView(actions);
            card.setOnClickListener(v -> selectSavedReceiver(device, dialogRef[0]));
            edit.setOnClickListener(v -> showEditReceiverDialog(device));
            delete.setOnClickListener(v -> new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                    .setTitle("حذف الجهاز؟")
                    .setMessage("سيتم حذف " + device.name + " من القائمة المحفوظة.")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف", (d, w) -> {
                        List<SavedReceiverDevice> latest = loadSavedReceiverDevices();
                        latest.removeIf(item -> item.ip.equalsIgnoreCase(device.ip));
                        saveSavedReceiverDevices(latest);
                        showSavedReceiverDialog();
                    }).show());
            list.addView(card, new LinearLayout.LayoutParams(-1, -2));
            View gap = new View(this);
            list.addView(gap, new LinearLayout.LayoutParams(1, 8));
            AsyncTask.execute(() -> {
                boolean online = isReceiverOnline(device.ip);
                runOnUiThread(() -> {
                    if (!isFinishing() && status.getParent() != null) {
                        boolean active = device.ip.equalsIgnoreCase(prefs.getString("tv_ip", "").trim());
                        // "متصل" is reserved for the selected active receiver
                        // whose Receiver service actually answers on port 8081.
                        status.setText(active ? (online ? "● متصل" : "◆ نشط")
                                : "● محفوظ");
                        status.setTextColor(active && online ? 0xFF4ADE80
                                : (active ? 0xFFFBBF24 : 0xFF94A3B8));
                    }
                });
            });
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, 12, 0, 0);
        TextView add = new TextView(this);
        add.setText("＋ إضافة جهاز");
        add.setTextColor(Color.WHITE);
        add.setGravity(Gravity.CENTER);
        add.setPadding(16, 12, 16, 12);
        GradientDrawable addBg = new GradientDrawable();
        addBg.setColor(0xFF1D4ED8);
        addBg.setCornerRadius(14);
        addBg.setStroke(1, 0xFF60A5FA);
        add.setBackground(addBg);
        TextView scan = new TextView(this);
        scan.setText("⌕  بحث");
        scan.setTextColor(0xFFBFDBFE);
        scan.setGravity(Gravity.CENTER);
        scan.setPadding(18, 12, 18, 12);
        GradientDrawable scanBg = new GradientDrawable();
        scanBg.setColor(0xFF1E293B);
        scanBg.setCornerRadius(14);
        scanBg.setStroke(1, 0xFF64748B);
        scan.setBackground(scanBg);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(0, -2, 1f);
        addParams.setMargins(0, 0, dp(12), 0);
        footer.addView(add, addParams);
        footer.addView(scan, new LinearLayout.LayoutParams(-2, -2));
        root.addView(footer);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme).setView(root)
                .setNegativeButton("إغلاق", null).create();
        dialogRef[0] = dialog;
        add.setOnClickListener(v -> {
            if (dialog.isShowing()) dialog.dismiss();
            showEditReceiverDialog(null);
        });
        scan.setOnClickListener(v -> {
            if (dialog.isShowing()) dialog.dismiss();
            scanForTvDevices();
        });
        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    private void showEditReceiverDialog(SavedReceiverDevice existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(20, 8, 20, 4);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("اسم الشاشة (مثلاً: تلفزيون الصالة)");
        name.setText(existing == null ? "" : existing.name);
        EditText ip = new EditText(this);
        ip.setSingleLine(true);
        ip.setHint("عنوان IP أو اسم الجهاز");
        ip.setText(existing == null ? "" : existing.ip);
        form.addView(name);
        form.addView(ip);
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle(existing == null ? "إضافة جهاز جديد" : "تعديل الجهاز")
                .setView(form)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String deviceName = name.getText().toString().trim();
                    String deviceIp = ip.getText().toString().trim();
                    if (deviceIp.isEmpty()) {
                        Toast.makeText(this, "اكتب عنوان IP أولاً", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (deviceName.isEmpty()) deviceName = "شاشة " + (loadSavedReceiverDevices().size() + 1);
                    List<SavedReceiverDevice> latest = loadSavedReceiverDevices();
                    if (existing != null) {
                        for (SavedReceiverDevice item : latest) {
                            if (item.ip.equalsIgnoreCase(existing.ip)) {
                                item.name = deviceName;
                                item.ip = deviceIp;
                            }
                        }
                        if (prefs.getString("tv_ip", "").trim().equalsIgnoreCase(existing.ip)) {
                            prefs.edit().putString("tv_ip", deviceIp).apply();
                            if (etTvIp != null) etTvIp.setText(deviceIp);
                            updateReceiverStatus();
                        }
                    } else {
                        for (SavedReceiverDevice item : latest) {
                            if (item.ip.equalsIgnoreCase(deviceIp)) {
                                item.name = deviceName;
                                saveSavedReceiverDevices(latest);
                                showSavedReceiverDialog();
                                return;
                            }
                        }
                        latest.add(new SavedReceiverDevice(deviceName, deviceIp));
                    }
                    saveSavedReceiverDevices(latest);
                    showSavedReceiverDialog();
                }).show();
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
        List<SavedReceiverDevice> saved = loadSavedReceiverDevices();
        for (String ip : devices) {
            if (!containsReceiverIp(saved, ip)) {
                saved.add(new SavedReceiverDevice("Receiver " + ip.substring(ip.lastIndexOf('.') + 1), ip));
            }
        }
        saveSavedReceiverDevices(saved);
        showSavedReceiverDialog();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        String sendUrl = intent == null ? null : intent.getStringExtra("send_saved_url_to_tv");
        if (sendUrl != null && !sendUrl.trim().isEmpty()) {
            intent.removeExtra("send_saved_url_to_tv");
            sendUrlToTvFromUi(sendUrl.trim());
            return;
        }

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
                    Toast.makeText(this, "الحافظة لا تحتوي على رابط", Toast.LENGTH_SHORT).show();
                    if (clipboardShortcutLaunch) finish();
                    return;
                }

                // Accept any copied web URL (not only a hard-coded list of platforms).
                // The share target below performs the real extraction, exactly as it
                // does when selected from Android's Sharesheet.
                Matcher urlMatcher = Pattern.compile("(?i)(https?://[^\\s]+|www\\.[^\\s]+)")
                        .matcher(text.toString());
                if (!urlMatcher.find()) {
                    Toast.makeText(this, "النص المنسوخ لا يحتوي على رابط", Toast.LENGTH_SHORT).show();
                    if (clipboardShortcutLaunch) finish();
                    return;
                }
                String copiedUrl = urlMatcher.group(1).replaceAll("[),،.]+$", "");
                if (copiedUrl.regionMatches(true, 0, "www.", 0, 4)) {
                    copiedUrl = "https://" + copiedUrl;
                }
                try {
                    Uri parsed = Uri.parse(copiedUrl);
                    if (parsed.getHost() == null || !(copiedUrl.startsWith("http://")
                            || copiedUrl.startsWith("https://"))) throw new IllegalArgumentException("Invalid URL");
                } catch (Exception invalidUrl) {
                    Toast.makeText(this, "الرابط المنسوخ غير صالح", Toast.LENGTH_SHORT).show();
                    if (clipboardShortcutLaunch) finish();
                    return;
                }

                // Launch the exact same exported share target that Android's
                // Sharesheet invokes. This preserves ShareActivity's platform-specific
                // extraction (including YouTube) instead of MainActivity's URL shortcut.
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setClass(this, ShareActivity.class);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, copiedUrl);
                startActivity(shareIntent);
                if (clipboardShortcutLaunch) finish();
            } catch (Exception e) {
                Toast.makeText(this, "تعذر قراءة الرابط من الحافظة", Toast.LENGTH_SHORT).show();
                if (clipboardShortcutLaunch) finish();
            }
        }, 300L);
        return true;
    }

    private boolean handleWebVideoCasterCaptureShortcut(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("web_video_caster_capture_shortcut", false)) return false;
        intent.removeExtra("web_video_caster_capture_shortcut");
        new Handler(Looper.getMainLooper()).post(() -> {
            SharedPreferences capturePrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
            java.util.ArrayList<String> urls = new java.util.ArrayList<>();
            try {
                org.json.JSONArray array = new org.json.JSONArray(
                        capturePrefs.getString("wvc_captured_urls", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    String url = array.optString(i, "");
                    if (url.startsWith("http://") || url.startsWith("https://")) urls.add(url);
                }
            } catch (Exception ignored) {}
            if (urls.isEmpty()) {
                Toast.makeText(this, "Web Video Caster لم يعرض روابط يمكن قراءتها", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            String[] labels = new String[urls.size()];
            for (int i = 0; i < urls.size(); i++)
                labels[i] = (i + 1) + ". " + getCompactSavedUrlLabel(urls.get(i));
            android.widget.ListView list = new android.widget.ListView(this);
            list.setAdapter(new android.widget.ArrayAdapter<String>(this,
                    android.R.layout.simple_list_item_1, labels));
            AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                    .setTitle("روابط Web Video Caster الملتقطة")
                    .setView(list)
                    .setNegativeButton("إغلاق", (d, w) -> finish())
                    .setNeutralButton("مسح القائمة", (d, w) -> {
                        capturePrefs.edit().remove("wvc_captured_urls")
                                .putInt("wvc_capture_count", 0).apply();
                        finish();
                    })
                    .create();
            list.setOnItemLongClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < urls.size()) {
                    String selectedUrl = urls.get(position);
                    showFullUrlDialog("رابط Web Video Caster الكامل", selectedUrl);
                }
                return true;
            });
            list.setOnItemClickListener((parent, view, position, id) -> {
                String selectedUrl = urls.get(position);
                new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                        .setTitle("تشغيل الرابط")
                        .setMessage(selectedUrl)
                        .setPositiveButton("إرسال إلى AboEzzat Receiver", (d, w) -> {
                            removeCapturedWebVideoCasterUrl(selectedUrl);
                            dialog.dismiss();
                            castUrlDirectlyToTv(selectedUrl);
                        })
                        .setNeutralButton("فتح في متصفح Cast", (d, w) -> {
                            removeCapturedWebVideoCasterUrl(selectedUrl);
                            dialog.dismiss();
                            Intent browserIntent = new Intent(this, BrowserActivity.class);
                            browserIntent.putExtra("url", selectedUrl);
                            startActivity(browserIntent);
                            finish();
                        })
                        .setNegativeButton("رجوع", null)
                        .show();
            });
            dialog.show();
        });
        return true;
    }

    private void removeCapturedWebVideoCasterUrl(String selectedUrl) {
        SharedPreferences capturePrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
        try {
            org.json.JSONArray old = new org.json.JSONArray(
                    capturePrefs.getString("wvc_captured_urls", "[]"));
            org.json.JSONArray updated = new org.json.JSONArray();
            for (int i = 0; i < old.length(); i++) {
                String value = old.optString(i, "");
                if (!selectedUrl.equals(value)) updated.put(value);
            }
            capturePrefs.edit().putString("wvc_captured_urls", updated.toString())
                    .putInt("wvc_capture_count", updated.length()).apply();
        } catch (Exception ignored) {}
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
        if (!tvIp.isEmpty() && !hasSecureReceiverPairing()) {
            showPairingRequiredMessage();
            if (clipboardShortcutLaunch) finish();
            return;
        }
        if (tvIp.isEmpty()) {
            if (clipboardShortcutLaunch) finish();
            return;
        }
        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http")) endpoint = "http://" + endpoint;
            if (!endpoint.contains(":" + TARGET_PORT)) endpoint += ":" + TARGET_PORT;
            String fullUrl = endpoint + "/?url=" + URLEncoder.encode(mediaUrl, "UTF-8") + "&open=browser";
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
                private void finishSend() {
                    runOnUiThread(() -> {
                        if (requestHeadersSent.get()) {
                            Toast.makeText(MainActivity.this, "✅ تم الإرسال", Toast.LENGTH_SHORT).show();
                        }
                        finish();
                    });
                }
                @Override public void onFailure(Call call, IOException error) { finishSend(); }
                @Override public void onResponse(Call call, Response response) {
                    response.close();
                    finishSend();
                }
            });
        } catch (Exception ignored) {
            if (clipboardShortcutLaunch) finish();
        }
    }

    private String encryptReceiverSecret(String plainText) {
        try {
            SecretKey key = getOrCreateReceiverKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            ByteBuffer packed = ByteBuffer.allocate(4 + iv.length + encrypted.length);
            packed.putInt(iv.length).put(iv).put(encrypted);
            return Base64.encodeToString(packed.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            android.util.Log.e("AboEzzatCast", "Could not protect receiver pairing key", e);
            return "";
        }
    }

    private String decryptReceiverSecret(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] packedBytes = Base64.decode(encoded, Base64.NO_WRAP);
            ByteBuffer packed = ByteBuffer.wrap(packedBytes);
            int ivLength = packed.getInt();
            if (ivLength < 12 || ivLength > 16 || packed.remaining() <= ivLength) return "";
            byte[] iv = new byte[ivLength];
            packed.get(iv);
            byte[] encrypted = new byte[packed.remaining()];
            packed.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateReceiverKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            android.util.Log.w("AboEzzatCast", "Stored receiver pairing key is unavailable on this device");
            return "";
        }
    }

    private SecretKey getOrCreateReceiverKey() throws Exception {
        final String alias = "AboEzzatCast.ReceiverAuthKey";
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) keyStore.getEntry(alias, null)).getSecretKey();
    }

    private boolean hasSecureReceiverPairing() {
        String savedIp = prefs.getString("tv_ip", "").trim();
        if (savedIp.isEmpty()) return false;
        try {
            String address = savedIp.startsWith("http://") || savedIp.startsWith("https://")
                    ? savedIp : "http://" + savedIp;
            String host = Uri.parse(address).getHost();
            return host != null && !prefs.getString(PREF_RECEIVER_SECRET + host, "").isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showPairingRequiredMessage() {
        Toast.makeText(this, "لازم تعمل ربط آمن مع الريسيفر أولاً من الإعدادات > ربط آمن مع الريسيفر.", Toast.LENGTH_LONG).show();
    }

    private void sendUrlToTvFromUi(String mediaUrl) {
        String tvIp = prefs.getString("tv_ip", "").trim();
        if (tvIp.isEmpty()) {
            Toast.makeText(this, "احفظ عنوان IP الشاشة أولاً", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasSecureReceiverPairing()) {
            showPairingRequiredMessage();
            return;
        }
        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http")) endpoint = "http://" + endpoint;
            if (!endpoint.contains(":" + TARGET_PORT)) endpoint += ":" + TARGET_PORT;
            String fullUrl = endpoint + "/?url=" + URLEncoder.encode(mediaUrl, "UTF-8") + "&open=browser";
            Request req = new Request.Builder().url(fullUrl).build();
            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "تعذر إرسال الرابط إلى الشاشة", Toast.LENGTH_LONG).show());
                }
                @Override public void onResponse(Call call, Response response) {
                    boolean successful = response.isSuccessful();
                    response.close();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            successful ? "✅ تم إرسال الرابط إلى الشاشة" : "تعذر إرسال الرابط إلى الشاشة",
                            Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception error) {
            Toast.makeText(this, "تعذر تجهيز الرابط للإرسال", Toast.LENGTH_SHORT).show();
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
                    setSelectedMediaUri(videoQueue.get(0));
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
                    setSelectedMediaUri(slideshowQueue.get(0));
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
        if (tvIp.isEmpty()) return;
        if (!hasSecureReceiverPairing()) {
            if (showToast) showPairingRequiredMessage();
            return;
        }
        String phoneIp = getDeviceIpAddress();
        if (phoneIp.isEmpty() || phoneIp.equals("0.0.0.0")) return;

        String mime = getContentResolver().getType(selectedMediaUri);
        String targetUrl = (mime != null && mime.startsWith("image/"))
                ? "http://" + phoneIp + ":8088/media.jpg"
                : "http://" + phoneIp + ":8088/media.mp4";
        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http")) endpoint = "http://" + endpoint;
            if (!endpoint.contains(":" + TARGET_PORT)) endpoint += ":" + TARGET_PORT;
            String fullUrl = endpoint + "/?url=" + URLEncoder.encode(targetUrl, "UTF-8");
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
                private void finishSend() {
                    if (!showToast || !requestHeadersSent.get()) return;
                    runOnUiThread(() -> Toast.makeText(
                            MainActivity.this, "✅ تم الإرسال", Toast.LENGTH_SHORT).show());
                }
                @Override public void onFailure(Call call, IOException error) { finishSend(); }
                @Override public void onResponse(Call call, Response response) {
                    response.close();
                    finishSend();
                }
            });
        } catch (Exception ignored) { }
    }

    private void sendRemoteCommand(String cmd) {
        String tvIp = prefs.getString("tv_ip", "").trim();
        if (tvIp.isEmpty()) return;
        if (!hasSecureReceiverPairing()) {
            showPairingRequiredMessage();
            return;
        }

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

    private void setSelectedMediaUri(Uri uri) {
        selectedMediaUri = uri;
        CastKeepAliveService.setSelectedMediaUri(this, uri);
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
        try {
            SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
            String previous = prefs.getString("diag_lifecycle_log", "");
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date());
            String line = stamp + " | MAIN onDestroy | pid=" + android.os.Process.myPid();
            String[] old = previous.isEmpty() ? new String[0] : previous.split("\n");
            StringBuilder log = new StringBuilder();
            int start = Math.max(0, old.length - 11);
            for (int i = start; i < old.length; i++) if (!old[i].isEmpty()) log.append(old[i]).append("\n");
            log.append(line);
            prefs.edit().putString("diag_lifecycle_log", log.toString())
                    .putString("diag_last_main_event", "MAIN onDestroy")
                    .apply();
        } catch (Exception ignored) {}
        super.onDestroy();
        slideHandler.removeCallbacks(slideRunnable);
    }
}
