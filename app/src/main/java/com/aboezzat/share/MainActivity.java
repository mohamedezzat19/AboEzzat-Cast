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
import android.widget.ImageView;
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
import android.view.ViewGroup;
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
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
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
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final int TARGET_PORT = 8081;
    private static final String PREF_RECEIVER_SECRET = "receiver_auth_secret_";
    private static final String PREF_RECEIVER_TLS_PIN = "receiver_tls_certificate_pin_";
    private static final int PICK_MEDIA_REQUEST = 101;
    private static final int PICK_SLIDESHOW_REQUEST = 102;
    private static final int VOICE_SEARCH_REQUEST = 103;
    private static final int PICK_RECEIVER_QR_IMAGE_REQUEST = 104;
    private static final int PICK_NAMED_PLAYLIST_ITEMS_REQUEST = 105;
    private String activeNamedPlaylist = null;

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
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(chain -> {
                Request original = chain.request();
                if (!original.url().isHttps()) {
                    throw new IOException("Receiver API requires pinned HTTPS");
                }
                boolean pairingRequest = "POST".equalsIgnoreCase(original.method())
                        && "/pair".equals(original.url().encodedPath());
                if (!pairingRequest) {
                        String clientId = getOrCreateReceiverClientId();
                        String secret = decryptReceiverSecret(getSharedPreferences("AboEzzatSharePrefs",
                                MODE_PRIVATE | MODE_MULTI_PROCESS).getString(
                                        PREF_RECEIVER_SECRET + original.url().host(), ""));
                        if (secret == null || secret.isEmpty()) {
                            throw new IOException("Receiver pairing secret is unavailable");
                        }
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
                                    + "\n" + bodyHash + "\n" + timestamp + "\n" + nonce
                                    + "\n" + clientId;
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
                                .header("X-AboEzzat-Client", clientId)
                                .build();
                        return chain.proceed(signed);
                }
                return chain.proceed(original);
            })
            .build();
    private Uri selectedMediaUri = null;

    private final List<Uri> slideshowQueue = new ArrayList<>();
    private int currentSlideIndex = 0;
    private boolean isSlideshowRunning = false;
    private long slideIntervalMs = 5000L;

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
                slideHandler.postDelayed(this, slideIntervalMs);
            }
        }
    };

    private boolean clipboardShortcutLaunch;
    private boolean webVideoCasterCaptureLaunch;
    private boolean accessibilityReminderShown;
    private String pendingPairingReceiverIp = "";

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
        updateDarkModeMenuLabel(prefs.getBoolean("dark_mode", true));
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
                    .setTitle("إعدادات أبو عزت كاست")
                    .setItems(new String[]{"التحقق من التحديثات", "إعداد عنوان IP الشاشة", "تفعيل الاختصار العائم عند نسخ رابط", "تشخيص مشكلة الفقاعة", "إعدادات أندرويد وأذونات التطبيق", "إعداد VPN داخل التطبيق", "استهلاك البيانات"}, (dialog, which) -> {
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
                            showDataUsageDialog();
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



    private void showDataUsageDialog() {
        SharedPreferences usage = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
        long localBytes = usage.getLong("usage_local_transfer_total", 0L);
        long todayBytes = usage.getLong("usage_local_transfer_today", 0L);
        long monthBytes = usage.getLong("usage_local_transfer_month", 0L);
        String message = "نقل الملفات من الهاتف إلى الريسيفر عبر الواي فاي المحلي\n"
                + "الإجمالي: " + android.text.format.Formatter.formatFileSize(this, localBytes) + "\n"
                + "اليوم: " + android.text.format.Formatter.formatFileSize(this, todayBytes) + "\n"
                + "هذا الشهر: " + android.text.format.Formatter.formatFileSize(this, monthBytes) + "\n\n"
                + "هذه الأرقام تقيس بايتات ملفات الهاتف التي أرسلها خادم كاست محليًا إلى الريسيفر، ولا تخصم من باقة الإنترنت عند استخدام شبكة محلية فعلًا.\n\n"
                + "فيديوهات الإنترنت مثل YouTube وTikTok يشغّلها الريسيفر من مصدرها؛ لا يستطيع كاست وحده قياس استهلاكها بدقة حاليًا. استهلاكها الفعلي يعتمد على جودة الفيديو ومدة المشاهدة، وقد يختلف حسب التطبيق والمصدر.";
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("استهلاك البيانات")
                .setMessage(message)
                .setNeutralButton("تصفير إحصائيات النقل المحلي", (d, w) -> {
                    usage.edit().putLong("usage_local_transfer_total", 0L)
                            .putLong("usage_local_transfer_today", 0L)
                            .putLong("usage_local_transfer_month", 0L)
                            .putString("usage_local_transfer_day", "")
                            .putString("usage_local_transfer_month_key", "").apply();
                    Toast.makeText(this, "تم تصفير إحصائيات النقل المحلي", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("إغلاق", null)
                .show();
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
        updateDarkModeMenuLabel(enabled);
        Toast.makeText(this, enabled ? "تم تفعيل الوضع المظلم" : "تم تفعيل الوضع الفاتح", Toast.LENGTH_SHORT).show();
    }

    private void updateDarkModeMenuLabel(boolean dark) {
        View item = findViewById(R.id.menu_dark_mode);
        if (!(item instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup row = (android.view.ViewGroup) item;
        if (row.getChildCount() > 1 && row.getChildAt(0) instanceof TextView) ((TextView) row.getChildAt(0)).setText(dark ? "☀️" : "🌙");
        if (row.getChildCount() > 1 && row.getChildAt(1) instanceof TextView) ((TextView) row.getChildAt(1)).setText(dark ? "الوضع الفاتح" : "الوضع المظلم");
    }

    private void applyDarkMode(View view, boolean dark) {
        if (view == null) return;
        final int page = dark ? 0xFF061329 : 0xFFF2F6FC;
        final int primaryText = dark ? 0xFFF4F8FF : 0xFF17263D;
        final int secondaryText = dark ? 0xFFB8C8DC : 0xFF53647A;
        if (view.getId() == R.id.drawer_layout || view.getId() == R.id.main_content_scroll) { if (dark) view.setBackgroundResource(R.drawable.bg_starfield); else view.setBackgroundColor(page); }
        else if (view.getId() == R.id.nav_view) view.setBackgroundColor(dark ? 0xFF080F1D : 0xFFE5EDF8);
        else if (view.getId() == R.id.bottom_navigation) { if (dark) view.setBackgroundResource(R.drawable.bg_bottom_navigation); else view.setBackgroundColor(0xFFDCE7F5); }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyDarkMode(group.getChildAt(i), dark);
        }
        if (view instanceof EditText) {
            EditText edit = (EditText) view;
            edit.setTextColor(primaryText);
            edit.setHintTextColor(secondaryText);
        } else if (view instanceof TextView && !(view instanceof Button)) {
            TextView text = (TextView) view;
            float[] hsv = new float[3];
            Color.colorToHSV(text.getCurrentTextColor(), hsv);
            if (hsv[1] < 0.14f) text.setTextColor(hsv[2] < 0.82f ? secondaryText : primaryText);
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
            labels[i] = (mostVisited ? (browserPrefs.getInt("visit_count_" + url, 0) + " زيارة · ") : "") + title;
        }

        LinearLayout historyPanel = new LinearLayout(this);
        historyPanel.setOrientation(LinearLayout.VERTICAL);
        historyPanel.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView expand = new TextView(this);
        expand.setText("▸ عرض " + urls.size() + " عنصر");
        expand.setTextColor(0xFFFFD54F);
        expand.setTextSize(15);
        expand.setPadding(dp(12), dp(14), dp(12), dp(14));
        expand.setBackground(libraryCardBackground(0xFF252630));
        historyPanel.addView(expand);
        android.widget.ListView listView = new android.widget.ListView(this);
        listView.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels));
        listView.setVisibility(View.GONE);
        historyPanel.addView(listView, new LinearLayout.LayoutParams(-1, dp(Math.min(urls.size(), 8) * 54)));
        expand.setOnClickListener(v -> {
            boolean opening = listView.getVisibility() != View.VISIBLE;
            listView.setVisibility(opening ? View.VISIBLE : View.GONE);
            expand.setText((opening ? "▾ إخفاء العناصر" : "▸ عرض " + urls.size() + " عنصر"));
        });
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle(mostVisited ? "الأكثر زيارة" : "سجل التصفح")
                .setView(historyPanel)
                .setNeutralButton("مسح السجل كله", (d, w) -> {
                    clearBrowserHistory(browserPrefs);
                    Toast.makeText(this, "تم مسح سجل التصفح بالكامل", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إغلاق", null).create();

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

        addLibrarySectionTitle(content, "الأكثر زيارة");
        TextView mostVisited = addLibraryCard(content, "عرض المواقع الأكثر زيارة");
        mostVisited.setOnClickListener(v -> showMostVisitedSitesDialog());

        addLibrarySectionTitle(content, "الوسائط");
        TextView recent = addLibraryCard(content, "الوسائط الأخيرة");
        recent.setOnClickListener(v -> showRecentMediaDialog());
        TextView playlist = addLibraryCard(content, "قائمة التشغيل");
        playlist.setOnClickListener(v -> showPlaylistDialog());
        TextView slideshowControls = addLibraryCard(content, "التحكم في عرض الصور");
        slideshowControls.setOnClickListener(v -> showSlideshowControls());
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme).create();
        dialog.setView(scroll);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF101014));
            dialog.getWindow().setLayout(-1, getResources().getDisplayMetrics().heightPixels - dp(76));
            dialog.getWindow().setGravity(android.view.Gravity.TOP);
        }
    }

    private void showMostVisitedSitesDialog() {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS);
        Set<String> saved = prefs.getStringSet("browser_history", new HashSet<String>());
        List<String> urls = new ArrayList<>(saved == null ? new HashSet<String>() : saved);
        urls.removeIf(url -> url == null || url.trim().isEmpty());
        urls.sort((a, b) -> {
            int visits = Integer.compare(prefs.getInt("visit_count_" + b, 0),
                    prefs.getInt("visit_count_" + a, 0));
            return visits != 0 ? visits : Long.compare(prefs.getLong("history_time_" + b, 0L),
                    prefs.getLong("history_time_" + a, 0L));
        });
        if (urls.size() > 30) urls = new ArrayList<>(urls.subList(0, 30));
        if (urls.isEmpty()) {
            new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("الأكثر زيارة")
                    .setMessage("لا توجد بيانات زيارات كافية حتى الآن.")
                    .setPositiveButton("إغلاق", null).show();
            return;
        }
        List<String> rankedUrls = urls;
        android.widget.ListView list = new android.widget.ListView(this);
        list.setBackgroundColor(0xFF101014);
        list.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, rankedUrls) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                String url = rankedUrls.get(position);
                String title = prefs.getString("history_title_" + url, "");
                String host = getCompactSavedUrlLabel(url);
                if (title == null || title.trim().isEmpty() || title.equals(url)
                        || title.startsWith("http://") || title.startsWith("https://")) title = host;
                TextView first = row.findViewById(android.R.id.text1);
                TextView second = row.findViewById(android.R.id.text2);
                first.setText((position + 1) + ". " + title);
                first.setTextColor(Color.WHITE);
                first.setSingleLine(true);
                first.setEllipsize(android.text.TextUtils.TruncateAt.END);
                second.setText(host + " • " + prefs.getInt("visit_count_" + url, 0) + " زيارة");
                second.setTextColor(Color.LTGRAY);
                second.setSingleLine(true);
                return row;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("الأكثر زيارة")
                .setView(list)
                .setNegativeButton("إغلاق", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < rankedUrls.size()) {
                openLibraryUrl(rankedUrls.get(position));
                dialog.dismiss();
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= rankedUrls.size()) return true;
            String url = rankedUrls.get(position);
            String title = prefs.getString("history_title_" + url, "الموقع");
            new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                    .setTitle(title == null || title.isEmpty() ? "الموقع" : title)
                    .setItems(new String[]{"فتح الموقع", "حذف من سجل التصفح"}, (d, which) -> {
                        if (which == 0) {
                            openLibraryUrl(url);
                            dialog.dismiss();
                        } else {
                            Set<String> updated = new HashSet<>(prefs.getStringSet(
                                    "browser_history", new HashSet<String>()));
                            updated.remove(url);
                            prefs.edit().putStringSet("browser_history", updated)
                                    .remove("history_title_" + url)
                                    .remove("history_time_" + url)
                                    .remove("visit_count_" + url).apply();
                            dialog.dismiss();
                            showMostVisitedSitesDialog();
                        }
                    }).show();
            return true;
        });
        dialog.show();
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
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        for (int i = 0; i < recent.size(); i++) {
            final Uri item = recent.get(i);
            final int index = i;
            rows.addView(createMediaQueueRow(item, getMediaDisplayName(item, i), () -> {
                setSelectedMediaUri(item);
                if (videoQueue.contains(item)) currentVideoIndex = videoQueue.indexOf(item);
                if (slideshowQueue.contains(item)) currentSlideIndex = slideshowQueue.indexOf(item);
                castLocalMediaToTv(true);
            }, () -> {
                recent.remove(item);
                writeUriList("recent_media_uris", recent);
                showRecentMediaDialog();
            }));
        }
        new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("الوسائط الأخيرة (" + recent.size() + ")")
                .setView(scroll).setNegativeButton("إغلاق", null).show();
    }


    private JSONObject loadNamedPlaylists() {
        try {
            String raw = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS)
                    .getString("named_media_playlists_v1", "{}");
            return new JSONObject(raw == null ? "{}" : raw);
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private void putNamedPlaylistItems(JSONObject playlists, String name, JSONArray items) {
        try { playlists.put(name, items); } catch (Exception ignored) {}
    }

    private void saveNamedPlaylists(JSONObject playlists) {
        getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE | MODE_MULTI_PROCESS)
                .edit().putString("named_media_playlists_v1", playlists.toString()).apply();
    }

    private void showPlaylistDialog() {
        JSONObject playlists = loadNamedPlaylists();
        List<String> names = new ArrayList<>();
        java.util.Iterator<String> keys = playlists.keys();
        while (keys.hasNext()) names.add(keys.next());
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        List<String> choices = new ArrayList<>(names);
        choices.add("＋ إنشاء قائمة جديدة");
        choices.add("إدارة قائمة التشغيل الحالية القديمة");
        String[] labels = choices.toArray(new String[0]);
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("قوائم التشغيل المحفوظة")
                .setItems(labels, (d, which) -> {
                    if (which == names.size()) {
                        EditText input = new EditText(this);
                        input.setSingleLine(true);
                        input.setHint("اسم القائمة");
                        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                                .setTitle("إنشاء قائمة تشغيل")
                                .setView(input)
                                .setPositiveButton("إنشاء", (x, w) -> {
                                    String name = input.getText().toString().trim();
                                    if (name.isEmpty()) {
                                        Toast.makeText(this, "اكتب اسم القائمة أولًا", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    JSONObject saved = loadNamedPlaylists();
                                    if (saved.has(name)) {
                                        Toast.makeText(this, "اسم القائمة موجود بالفعل", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    putNamedPlaylistItems(saved, name, new JSONArray());
                                    saveNamedPlaylists(saved);
                                    activeNamedPlaylist = name;
                                    showNamedPlaylistDialog(name);
                                })
                                .setNegativeButton("إلغاء", null).show();
                    } else if (which == names.size() + 1) {
                        showLegacyPlaylistDialog();
                    } else if (which >= 0 && which < names.size()) {
                        activeNamedPlaylist = names.get(which);
                        showNamedPlaylistDialog(activeNamedPlaylist);
                    }
                })
                .setNegativeButton("إغلاق", null).show();
    }

    private void showLegacyPlaylistDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        for (int i = 0; i < videoQueue.size(); i++) {
            final int index = i;
            Uri uri = videoQueue.get(i);
            rows.addView(createMediaQueueRow(uri, getMediaDisplayName(uri, i), () -> {
                currentVideoIndex = index;
                setSelectedMediaUri(uri);
                castLocalMediaToTv(true);
            }, () -> {
                videoQueue.remove(index);
                currentVideoIndex = Math.min(currentVideoIndex, Math.max(0, videoQueue.size() - 1));
                persistMediaQueues();
                showLegacyPlaylistDialog();
            }));
        }
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(300)));
        new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("القائمة القديمة")
                .setView(panel).setPositiveButton("إضافة فيديوهات", (d,w) -> openMediaPicker(false))
                .setNeutralButton("مسح", (d,w) -> {
                    videoQueue.clear(); currentVideoIndex = 0; persistMediaQueues();
                    Toast.makeText(this, "تم مسح القائمة القديمة", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("رجوع", (d,w) -> showPlaylistDialog()).show();
    }

    private void showAddPlaylistLinkDialog(String name) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://...");
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("إضافة رابط إنترنت")
                .setMessage("سيحاول الريسيفر تجهيز الرابط وتشغيل الوسائط المدعومة منه.")
                .setView(input)
                .setPositiveButton("إضافة", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    Uri parsed = Uri.parse(value);
                    String scheme = parsed.getScheme();
                    if (value.length() > 8192 || parsed.getHost() == null || parsed.getUserInfo() != null
                            || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                        Toast.makeText(this, "اكتب رابط HTTP أو HTTPS صحيحًا.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    JSONObject all = loadNamedPlaylists();
                    JSONArray items = all.optJSONArray(name);
                    if (items == null) items = new JSONArray();
                    if (items.length() >= 50) {
                        Toast.makeText(this, "الحد الأقصى 50 عنصرًا في القائمة.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    JSONObject entry = new JSONObject();
                    try {
                        entry.put("url", value);
                        entry.put("sourceUrl", value);
                        entry.put("name", getCompactSavedUrlLabel(value));
                        entry.put("mime", "text/uri-list");
                        entry.put("headers", new JSONObject());
                        items.put(entry);
                        putNamedPlaylistItems(all, name, items);
                        saveNamedPlaylists(all);
                        showNamedPlaylistDialog(name);
                    } catch (Exception error) {
                        Toast.makeText(this, "تعذر حفظ الرابط.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private void castNamedPlaylistToTv(String name) {
        List<String> targets = getPlaybackReceiverTargets();
        if (targets.isEmpty()) {
            Toast.makeText(this, "مفيش أجهزة محددة ومربوطة بأمان للبث.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!CastKeepAliveService.isLocalServerReady()) {
            Toast.makeText(this, "خادم بث الملفات لم يصبح جاهزًا بعد؛ انتظر لحظة.", Toast.LENGTH_LONG).show();
            return;
        }
        JSONObject all = loadNamedPlaylists();
        JSONArray savedItems = all.optJSONArray(name);
        if (savedItems == null || savedItems.length() == 0) {
            Toast.makeText(this, "القائمة فارغة.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (savedItems.length() > 50) {
            Toast.makeText(this, "الحد الأقصى للإرسال في المرة الواحدة 50 عنصرًا.", Toast.LENGTH_LONG).show();
            return;
        }
        String phoneIp = getDeviceIpAddress();
        if (phoneIp == null || phoneIp.isEmpty() || "0.0.0.0".equals(phoneIp)) {
            Toast.makeText(this, "تعذر تحديد عنوان الهاتف على شبكة Wi-Fi.", Toast.LENGTH_LONG).show();
            return;
        }
        final String mediaPin;
        try {
            mediaPin = CastTlsIdentity.getCertificateFingerprint();
        } catch (Exception error) {
            Toast.makeText(this, "تعذر تجهيز شهادة تشفير الوسائط.", Toast.LENGTH_LONG).show();
            return;
        }
        if (mediaPin == null || !mediaPin.matches("[A-Za-z0-9_-]{43}")) {
            Toast.makeText(this, "بصمة شهادة الوسائط غير صالحة.", Toast.LENGTH_LONG).show();
            return;
        }
        JSONArray queueItems = new JSONArray();
        try {
            for (int i = 0; i < savedItems.length(); i++) {
                JSONObject saved = savedItems.optJSONObject(i);
                if (saved == null) continue;
                String remoteUrl = saved.optString("url", "");
                if (!remoteUrl.isEmpty()) {
                    Uri parsed = Uri.parse(remoteUrl);
                    String scheme = parsed.getScheme();
                    if (parsed.getHost() == null
                            || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) continue;
                    JSONObject entry = new JSONObject();
                    entry.put("url", remoteUrl);
                    entry.put("sourceUrl", saved.optString("sourceUrl", remoteUrl));
                    entry.put("headers", saved.optJSONObject("headers") == null
                            ? new JSONObject() : saved.optJSONObject("headers"));
                    queueItems.put(entry);
                    continue;
                }
                Uri uri = Uri.parse(saved.optString("uri", ""));
                if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) continue;
                String mime = saved.optString("mime", getContentResolver().getType(uri));
                if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/")
                        || mime.startsWith("audio/"))) continue;
                CastKeepAliveService.setSelectedMediaUri(this, uri);
                String token = CastKeepAliveService.getMediaAccessToken();
                if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
                    throw new IOException("Unable to register local media item");
                }
                String mediaUrl = "https://" + phoneIp + ":8088"
                        + getLocalMediaRoutePath(uri, mime) + "?token=" + token;
                JSONObject entry = new JSONObject();
                entry.put("url", mediaUrl);
                entry.put("mediaPin", mediaPin);
                entry.put("headers", new JSONObject());
                queueItems.put(entry);
            }
        } catch (Exception error) {
            Toast.makeText(this, "تعذر تجهيز أحد ملفات القائمة: " + error.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (queueItems.length() == 0) {
            Toast.makeText(this, "القائمة لا تحتوي على ملفات صور أو فيديو أو صوت صالحة.", Toast.LENGTH_LONG).show();
            return;
        }
        probeReceiversBeforeQueueSend(targets, queueItems);
    }

    private void probeReceiversBeforeQueueSend(List<String> targets, JSONArray queueItems) {
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        Runnable[] finishProbe = new Runnable[1];
        finishProbe[0] = () -> {
            if (completed.incrementAndGet() != targets.size()) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (active.get() == 0) {
                    sendNamedQueueBatch(targets, queueItems, "replace");
                    return;
                }
                new AlertDialog.Builder(MainActivity.this, R.style.ModernDialogTheme)
                        .setTitle("فيه محتوى شغال على أجهزة الاستقبال")
                        .setMessage("تم تأكيد وجود تشغيل فعلي على " + active.get() + " من "
                                + targets.size() + " أجهزة. اختار تشغيل القائمة بدل الحالي أو إضافتها بعده.")
                        .setPositiveButton("تشغيل بدل الحالي",
                                (dialog, which) -> sendNamedQueueBatch(targets, queueItems, "replace"))
                        .setNeutralButton("إضافة بعد الحالي",
                                (dialog, which) -> sendNamedQueueBatch(targets, queueItems, "append"))
                        .setNegativeButton("إلغاء", null)
                        .show();
            });
        };
        for (String target : targets) {
            try {
                String endpoint = buildReceiverHttpsEndpoint(target);
                String host = Uri.parse(endpoint).getHost();
                if (host == null) throw new IOException("Invalid receiver host");
                OkHttpClient client = getPinnedReceiverClient(host).newBuilder()
                        .connectTimeout(1800, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(2200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .callTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS).build();
                Request request = new Request.Builder().url(endpoint + "/?command=status").get().build();
                client.newCall(request).enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException error) {
                        finishProbe[0].run();
                    }
                    @Override public void onResponse(Call call, Response response) {
                        try {
                            if (response.isSuccessful() && response.body() != null) {
                                JSONObject state = new JSONObject(response.body().string());
                                long updated = state.optLong("mediaStateUpdatedAt", 0L);
                                long age = System.currentTimeMillis() - updated;
                                String type = state.optString("mediaType", "none");
                                if (state.optBoolean("mediaActive", false) && updated > 0L
                                        && age >= -5000L && age <= 60000L
                                        && ("video".equals(type) || "audio".equals(type) || "image".equals(type))) {
                                    active.incrementAndGet();
                                }
                            }
                        } catch (Exception ignored) {
                            // Stale or malformed status is not proof of active playback.
                        } finally {
                            response.close();
                            finishProbe[0].run();
                        }
                    }
                });
            } catch (Exception error) {
                finishProbe[0].run();
            }
        }
    }

    private void sendNamedQueueBatch(List<String> targets, JSONArray queueItems, String mode) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("mode", mode);
            payload.put("items", queueItems);
        } catch (Exception error) {
            Toast.makeText(this, "تعذر تجهيز بيانات قائمة التشغيل.", Toast.LENGTH_LONG).show();
            return;
        }
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        for (String target : targets) {
            try {
                String endpoint = buildReceiverHttpsEndpoint(target);
                String host = Uri.parse(endpoint).getHost();
                if (host == null) throw new IOException("Invalid receiver host");
                OkHttpClient client = getPinnedReceiverClient(host);
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json; charset=utf-8"), payload.toString());
                Request request = new Request.Builder().url(endpoint + "/queue").post(body).build();
                client.newCall(request).enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException error) {
                        finishQueueTarget(targets.size(), accepted, finished);
                    }
                    @Override public void onResponse(Call call, Response response) {
                        try {
                            if (response.isSuccessful()) accepted.incrementAndGet();
                        } finally {
                            response.close();
                            finishQueueTarget(targets.size(), accepted, finished);
                        }
                    }
                });
            } catch (Exception error) {
                finishQueueTarget(targets.size(), accepted, finished);
            }
        }
    }

    private void finishQueueTarget(int total, java.util.concurrent.atomic.AtomicInteger accepted,
                                   java.util.concurrent.atomic.AtomicInteger finished) {
        if (finished.incrementAndGet() == total) runOnUiThread(() -> {
            Toast.makeText(this, "تم إرسال قائمة التشغيل إلى " + accepted.get()
                    + " من " + total + " أجهزة", Toast.LENGTH_LONG).show();
        });
    }

    private void showNamedPlaylistDialog(String name) {
        JSONObject all = loadNamedPlaylists();
        JSONArray items = all.optJSONArray(name);
        if (items == null) items = new JSONArray();
        final JSONArray playlistItems = items;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView playAll = new TextView(this);
        playAll.setText("▶  تشغيل القائمة كاملة على الريسيفر");
        playAll.setTextColor(Color.WHITE);
        playAll.setTextSize(14);
        playAll.setGravity(Gravity.CENTER);
        playAll.setPadding(dp(12), dp(13), dp(12), dp(13));
        android.graphics.drawable.GradientDrawable playBg = new android.graphics.drawable.GradientDrawable();
        playBg.setColor(0xFF087F9C);
        playBg.setCornerRadius(dp(14));
        playAll.setBackground(playBg);
        playAll.setOnClickListener(v -> castNamedPlaylistToTv(name));
        panel.addView(playAll, new LinearLayout.LayoutParams(-1, -2));
        TextView addLink = new TextView(this);
        addLink.setText("＋  إضافة رابط إنترنت للقائمة");
        addLink.setTextColor(0xFF80DEEA);
        addLink.setTextSize(13);
        addLink.setGravity(Gravity.CENTER);
        addLink.setPadding(dp(10), dp(11), dp(10), dp(11));
        addLink.setOnClickListener(v -> showAddPlaylistLinkDialog(name));
        panel.addView(addLink, new LinearLayout.LayoutParams(-1, -2));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        for (int i = 0; i < playlistItems.length(); i++) {
            JSONObject item = playlistItems.optJSONObject(i);
            if (item == null) continue;
            final int index = i;
            Uri uri = Uri.parse(item.optString("uri", ""));
            String title = item.optString("name", getMediaDisplayName(uri, i));
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(6), dp(6), dp(6));
            TextView label = new TextView(this);
            label.setText((i + 1) + ". " + title + "\n" + (item.has("url") ? "رابط إنترنت" : item.optString("mime", "وسائط")));
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView up = new TextView(this); up.setText("↑"); up.setTextColor(Color.WHITE); up.setPadding(dp(8),dp(8),dp(8),dp(8));
            TextView down = new TextView(this); down.setText("↓"); down.setTextColor(Color.WHITE); down.setPadding(dp(8),dp(8),dp(8),dp(8));
            TextView remove = new TextView(this); remove.setText("حذف"); remove.setTextColor(0xFFFFB4AB); remove.setPadding(dp(8),dp(8),dp(8),dp(8));
            row.addView(up); row.addView(down); row.addView(remove);
            row.setOnClickListener(v -> {
                String savedUrl = item.optString("url", "");
                if (!savedUrl.isEmpty()) {
                    castUrlDirectlyToTv(savedUrl);
                } else {
                    setSelectedMediaUri(uri);
                    castLocalMediaToTv(true);
                }
            });
            up.setOnClickListener(v -> moveNamedPlaylistItem(name, index, -1));
            down.setOnClickListener(v -> moveNamedPlaylistItem(name, index, 1));
            remove.setOnClickListener(v -> removeNamedPlaylistItem(name, index));
            rows.addView(row);
            View divider = new View(this); divider.setBackgroundColor(0xFF374151);
            rows.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        if (playlistItems.length() == 0) {
            TextView empty = new TextView(this); empty.setText("القائمة فارغة. أضف صورًا أو فيديوهات أو ملفات صوتية."); empty.setTextColor(Color.LTGRAY);
            rows.addView(empty);
        }
        // Give the dialog content a real measured height; a zero-height weighted
        // ScrollView inside a wrap-content dialog can collapse the playlist rows.
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(360)));
        new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle(name + " (" + playlistItems.length() + ")")
                .setView(panel)
                .setPositiveButton("إضافة وسائط", (d,w) -> openNamedPlaylistPicker(name))
                .setNeutralButton("مسح القائمة", (d,w) -> new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                        .setTitle("مسح القائمة؟").setMessage("سيتم حذف عناصر القائمة فقط دون حذف الملفات الأصلية.")
                        .setPositiveButton("مسح", (x,y) -> {
                            JSONObject saved = loadNamedPlaylists(); putNamedPlaylistItems(saved, name, new JSONArray()); saveNamedPlaylists(saved);
                            showNamedPlaylistDialog(name);
                        }).setNegativeButton("إلغاء", null).show())
                .setNegativeButton("القوائم", (d,w) -> showPlaylistDialog()).show();
    }

    private void openNamedPlaylistPicker(String name) {
        activeNamedPlaylist = name;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "audio/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_NAMED_PLAYLIST_ITEMS_REQUEST);
    }

    private void appendNamedPlaylistItems(String name, Intent data) {
        JSONObject all = loadNamedPlaylists();
        JSONArray items = all.optJSONArray(name);
        if (items == null) items = new JSONArray();
        List<Uri> picked = new ArrayList<>();
        ClipData clips = data.getClipData();
        if (clips != null) {
            for (int i = 0; i < clips.getItemCount(); i++) picked.add(clips.getItemAt(i).getUri());
        } else if (data.getData() != null) picked.add(data.getData());
        for (Uri uri : picked) {
            if (uri == null) continue;
            takePermissions(uri);
            String mime = getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            JSONObject entry = new JSONObject();
            try {
                entry.put("uri", uri.toString());
                entry.put("mime", mime);
                entry.put("name", getMediaDisplayName(uri, items.length()));
                items.put(entry);
                addRecentMedia(uri);
            } catch (Exception ignored) {}
        }
        putNamedPlaylistItems(all, name, items);
        saveNamedPlaylists(all);
        Toast.makeText(this, "تمت إضافة " + picked.size() + " عنصر إلى " + name, Toast.LENGTH_SHORT).show();
        showNamedPlaylistDialog(name);
    }

    private void moveNamedPlaylistItem(String name, int index, int delta) {
        JSONObject all = loadNamedPlaylists();
        JSONArray old = all.optJSONArray(name);
        if (old == null) return;
        int target = index + delta;
        if (target < 0 || target >= old.length()) return;
        JSONArray updated = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            if (i == index) updated.put(old.opt(target));
            else if (i == target) updated.put(old.opt(index));
            else updated.put(old.opt(i));
        }
        putNamedPlaylistItems(all, name, updated); saveNamedPlaylists(all); showNamedPlaylistDialog(name);
    }

    private void removeNamedPlaylistItem(String name, int index) {
        JSONObject all = loadNamedPlaylists();
        JSONArray old = all.optJSONArray(name);
        if (old == null) return;
        JSONArray updated = new JSONArray();
        for (int i = 0; i < old.length(); i++) if (i != index) updated.put(old.opt(i));
        putNamedPlaylistItems(all, name, updated); saveNamedPlaylists(all); showNamedPlaylistDialog(name);
    }

    private LinearLayout createMediaQueueRow(Uri uri, String title, Runnable onClick, Runnable onRemove) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(libraryCardBackground(0xFF30343B));
        LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        thumbParams.setMargins(0, 0, dp(10), 0);
        row.addView(thumb, thumbParams);
        try {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(this, uri);
            android.graphics.Bitmap frame = retriever.getFrameAtTime(0);
            if (frame != null) thumb.setImageBitmap(frame);
            retriever.release();
        } catch (Exception ignored) {
            try { thumb.setImageURI(uri); } catch (Exception ignoredAgain) { }
        }
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(Color.WHITE);
        name.setTextSize(13);
        name.setMaxLines(2);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView remove = new TextView(this);
        remove.setText("حذف");
        remove.setTextColor(0xFFFFB4AB);
        remove.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.addView(remove);
        row.setOnClickListener(v -> { if (onClick != null) onClick.run(); });
        remove.setOnClickListener(v -> { if (onRemove != null) onRemove.run(); });
        View divider = new View(this);
        divider.setBackgroundColor(0xFF374151);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(4);
        row.addView(divider, dividerParams);
        return row;
    }

    private void showSlideshowControls() {
        if (slideshowQueue.isEmpty()) {
            Toast.makeText(this, "مفيش صور في عرض الشرائح", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] choices = {"السابق", isSlideshowRunning ? "إيقاف مؤقت" : "استكمال العرض",
                "التالي", "مدة العرض", "إيقاف ومسح عرض الشرائح"};
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("التحكم في عرض الصور")
                .setItems(choices, (d, which) -> {
                    if (which == 0) changeSlide(-1);
                    else if (which == 1) {
                        isSlideshowRunning = !isSlideshowRunning;
                        slideHandler.removeCallbacks(slideRunnable);
                        if (isSlideshowRunning) slideHandler.postDelayed(slideRunnable, slideIntervalMs);
                        showSlideshowControls();
                    } else if (which == 2) changeSlide(1);
                    else if (which == 3) {
                        String[] durations = {"3 ثوانٍ", "5 ثوانٍ", "10 ثوانٍ", "15 ثانية", "30 ثانية"};
                        long[] values = {3000L, 5000L, 10000L, 15000L, 30000L};
                        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                                .setTitle("مدة عرض كل صورة")
                                .setItems(durations, (dialog, index) -> {
                                    slideIntervalMs = values[index];
                                    prefs.edit().putLong("slideshow_interval_ms", slideIntervalMs).apply();
                                    slideHandler.removeCallbacks(slideRunnable);
                                    if (isSlideshowRunning) slideHandler.postDelayed(slideRunnable, slideIntervalMs);
                                    Toast.makeText(this, "تم تغيير مدة العرض", Toast.LENGTH_SHORT).show();
                                }).show();
                    } else {
                        isSlideshowRunning = false;
                        slideHandler.removeCallbacks(slideRunnable);
                        slideshowQueue.clear();
                        currentSlideIndex = 0;
                        persistMediaQueues();
                        sendRemoteCommand("stop");
                    }
                }).setNegativeButton("إغلاق", null).show();
    }

    private void changeSlide(int delta) {
        if (slideshowQueue.isEmpty()) return;
        currentSlideIndex = (currentSlideIndex + delta + slideshowQueue.size()) % slideshowQueue.size();
        setSelectedMediaUri(slideshowQueue.get(currentSlideIndex));
        castLocalMediaToTv(true);
        if (isSlideshowRunning) {
            slideHandler.removeCallbacks(slideRunnable);
            slideHandler.postDelayed(slideRunnable, slideIntervalMs);
        }
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
        String displayName = getSavedReceiverName(ip);
        String displayTarget = displayName.isEmpty() ? ip : displayName;
        tvStatusBadge.setText("● جارٍ التحقق من " + displayTarget + "...");
        AsyncTask.execute(() -> {
            boolean reachable = isReceiverOnline(ip);
            final boolean isReachable = reachable;
            final boolean paired = hasSecureReceiverPairing();
            runOnUiThread(() -> tvStatusBadge.setText(isReachable
                    ? "● اتصال آمن بـ " + displayTarget
                    : paired ? "● محفوظ، تعذر الاتصال الآمن: " + displayTarget
                    : "● الجهاز محفوظ، يحتاج ربط QR: " + displayTarget));
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
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("ربط أبو عزت كاست بالريسيفر")
                .setItems(new String[]{"مسح QR بالكاميرا", "اختيار صورة QR من المعرض", "الربط اليدوي"}, (dialog, which) -> {
                    if (which == 0) startReceiverQrScanner();
                    else if (which == 1) pickReceiverQrImage();
                    else showManualReceiverPairingDialog();
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void startReceiverQrScanner() {
        try {
            com.google.zxing.integration.android.IntentIntegrator scanner =
                    new com.google.zxing.integration.android.IntentIntegrator(this);
            scanner.setDesiredBarcodeFormats(
                    com.google.zxing.integration.android.IntentIntegrator.QR_CODE);
            scanner.setPrompt("وجّه الكاميرا إلى QR الظاهر على شاشة الريسيفر");
            scanner.setBeepEnabled(false);
            scanner.setOrientationLocked(false);
            scanner.setCaptureActivity(ReceiverQrCaptureActivity.class);
            scanner.initiateScan();
        } catch (Exception error) {
            Toast.makeText(this, "تعذر فتح ماسح QR. استخدم الربط اليدوي.", Toast.LENGTH_LONG).show();
        }
    }

    private void pickReceiverQrImage() {
        try {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("image/*");
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(picker, PICK_RECEIVER_QR_IMAGE_REQUEST);
        } catch (Exception error) {
            Toast.makeText(this, "تعذر فتح معرض الصور.", Toast.LENGTH_LONG).show();
        }
    }

    private void decodeReceiverQrFromGallery(Uri imageUri) {
        Toast.makeText(this, "جاري قراءة QR من الصورة…", Toast.LENGTH_SHORT).show();
        AsyncTask.execute(() -> {
            String contents = null;
            Exception failure = null;
            android.graphics.Bitmap bitmap = null;
            try {
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (java.io.InputStream input = getContentResolver().openInputStream(imageUri)) {
                    if (input == null) throw new IOException("Unable to open selected image");
                    android.graphics.BitmapFactory.decodeStream(input, null, bounds);
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw new IOException("Selected file is not a readable image");
                }
                int sample = 1;
                while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) {
                    sample *= 2;
                }
                android.graphics.BitmapFactory.Options decode = new android.graphics.BitmapFactory.Options();
                decode.inSampleSize = sample;
                try (java.io.InputStream input = getContentResolver().openInputStream(imageUri)) {
                    if (input == null) throw new IOException("Unable to reopen selected image");
                    bitmap = android.graphics.BitmapFactory.decodeStream(input, null, decode);
                }
                if (bitmap == null) throw new IOException("Unable to decode selected image");

                java.util.Map<com.google.zxing.DecodeHintType, Object> hints =
                        new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);
                hints.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS,
                        java.util.Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
                hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);

                for (int rotation : new int[]{0, 90, 180, 270}) {
                    android.graphics.Bitmap candidate = bitmap;
                    boolean rotatedCopy = false;
                    if (rotation != 0) {
                        android.graphics.Matrix matrix = new android.graphics.Matrix();
                        matrix.postRotate(rotation);
                        candidate = android.graphics.Bitmap.createBitmap(
                                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        rotatedCopy = candidate != bitmap;
                    }
                    try {
                        int width = candidate.getWidth();
                        int height = candidate.getHeight();
                        int[] pixels = new int[width * height];
                        candidate.getPixels(pixels, 0, width, 0, 0, width, height);
                        com.google.zxing.RGBLuminanceSource source =
                                new com.google.zxing.RGBLuminanceSource(width, height, pixels);
                        com.google.zxing.BinaryBitmap binary =
                                new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source));
                        com.google.zxing.MultiFormatReader reader = new com.google.zxing.MultiFormatReader();
                        reader.setHints(hints);
                        try {
                            contents = reader.decodeWithState(binary).getText();
                            if (contents != null && !contents.trim().isEmpty()) break;
                        } catch (com.google.zxing.ReaderException ignored) {
                            // Try the next image orientation.
                        } finally {
                            reader.reset();
                        }
                    } finally {
                        if (rotatedCopy) candidate.recycle();
                    }
                    if (contents != null && !contents.trim().isEmpty()) break;
                }
                if (contents == null || contents.trim().isEmpty()) {
                    throw new IOException("No QR code found in selected image");
                }
            } catch (Exception error) {
                failure = error;
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
            final String decodedContents = contents;
            final Exception decodeFailure = failure;
            runOnUiThread(() -> {
                if (decodedContents != null && !decodedContents.trim().isEmpty()) {
                    handleReceiverPairingQr(decodedContents);
                } else {
                    android.util.Log.w("AboEzzatCast", "Could not decode receiver QR image", decodeFailure);
                    Toast.makeText(MainActivity.this,
                            "مش قادر أقرأ QR من الصورة. اختار صورة أوضح ويكون الكود ظاهر بالكامل.",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private String getOrCreateReceiverClientId() {
        SharedPreferences clientPrefs = getSharedPreferences("AboEzzatSharePrefs",
                MODE_PRIVATE | MODE_MULTI_PROCESS);
        String clientId = clientPrefs.getString("receiver_client_id", "");
        if (clientId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
            return clientId;
        }
        clientId = UUID.randomUUID().toString();
        if (!clientPrefs.edit().putString("receiver_client_id", clientId).commit()) {
            throw new IllegalStateException("Could not save Cast installation identity");
        }
        return clientId;
    }

    private void handleReceiverPairingQr(String contents) {
        try {
            if (contents == null || contents.length() > 4096) {
                throw new IllegalArgumentException("QR payload size is invalid");
            }
            org.json.JSONObject payload = new org.json.JSONObject(contents);
            if (!"aboezzat_receiver_pair".equals(payload.optString("type"))
                    || payload.optInt("version", -1) != 1) {
                throw new IllegalArgumentException("Unsupported pairing QR");
            }
            String host = payload.optString("host", "").trim();
            int port = payload.optInt("port", -1);
            String expectedHost = pendingPairingReceiverIp == null ? "" : pendingPairingReceiverIp.trim();
            if (!expectedHost.isEmpty() && !expectedHost.equalsIgnoreCase(host)) {
                Toast.makeText(this, "رمز QR ده لجهاز مختلف عن الجهاز اللي اخترته. امسح QR الخاص بالجهاز المحدد.", Toast.LENGTH_LONG).show();
                return;
            }
            String code = payload.optString("code", "");
            String fingerprint = payload.optString("fingerprint", "");
            String receiverName = payload.optString("name", "").trim();
            if (receiverName.length() > 48) receiverName = receiverName.substring(0, 48);
            if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                throw new IllegalArgumentException("Invalid receiver IP");
            }
            java.net.InetAddress address = java.net.InetAddress.getByName(host);
            if (!(address instanceof java.net.Inet4Address) || !address.isSiteLocalAddress()
                    || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("Receiver must be on a private LAN");
            }
            if (port < 1 || port > 65535 || !code.matches("[0-9]{8}")
                    || !isValidReceiverFingerprint(fingerprint)) {
                throw new IllegalArgumentException("Invalid pairing details");
            }

            String endpoint = buildReceiverHttpsEndpoint(host + ":" + port);
            String pairedHost = Uri.parse(endpoint).getHost();
            if (pairedHost == null || !pairedHost.equals(host)) {
                throw new IllegalArgumentException("Receiver address mismatch");
            }
            String existingPin = prefs.getString(PREF_RECEIVER_TLS_PIN + pairedHost, "");
            if (isValidReceiverFingerprint(existingPin) && !existingPin.equals(fingerprint)) {
                Toast.makeText(this,
                        "بصمة QR مختلفة عن البصمة المحفوظة لهذا الريسيفر. لن نغيّرها تلقائيًا؛ احذف الربط القديم بعد التأكد من الجهاز.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            final String suggestedName = receiverName.isEmpty() ? "شاشة " + host : receiverName;
            EditText nameInput = new EditText(this);
            nameInput.setSingleLine(true);
            nameInput.setText(suggestedName);
            nameInput.setHint("اسم الجهاز");
            new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                    .setTitle("اسم الريسيفر")
                    .setMessage("تقدر تعدّل الاسم قبل حفظ الجهاز.")
                    .setView(nameInput)
                    .setPositiveButton("متابعة الربط", (d, w) ->
                            performReceiverPairing(endpoint, pairedHost, fingerprint, code,
                                    nameInput.getText().toString().trim().isEmpty()
                                            ? suggestedName : nameInput.getText().toString().trim()))
                    .setNegativeButton("إلغاء", null).show();
        } catch (Exception invalidQr) {
            android.util.Log.w("AboEzzatCast", "Rejected receiver pairing QR", invalidQr);
            Toast.makeText(this, "QR غير صالح أو لا يحتوي بيانات ريسيفر مدعومة.", Toast.LENGTH_LONG).show();
        }
    }

    private void performReceiverPairing(String endpoint, String pairedHost,
                                        String fingerprint, String code, String receiverName) {
        if (!code.matches("[0-9]{8}") || !isValidReceiverFingerprint(fingerprint)) {
            Toast.makeText(this, "بيانات الربط غير صحيحة", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            OkHttpClient pinnedClient = createPinnedReceiverClient(pairedHost, fingerprint);
            String pairUrl = endpoint + "/pair";
            okhttp3.RequestBody pairingBody = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json; charset=utf-8"),
                    new org.json.JSONObject().put("code", code)
                            .put("client_id", getOrCreateReceiverClientId()).toString());
            Request request = new Request.Builder().url(pairUrl).post(pairingBody).build();
            Toast.makeText(this, "جارٍ التحقق والربط بالريسيفر…", Toast.LENGTH_SHORT).show();
            pinnedClient.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    android.util.Log.e("AboEzzatCast", "Receiver TLS pairing request failed", error);
                    StringBuilder reasonBuilder = new StringBuilder();
                    Throwable cause = error;
                    int causeDepth = 0;
                    while (cause != null && causeDepth < 5) {
                        if (causeDepth > 0) reasonBuilder.append("\\n↳ ");
                        reasonBuilder.append(cause.getClass().getSimpleName());
                        String detail = cause.getMessage();
                        if (detail != null && !detail.trim().isEmpty()) {
                            detail = detail.replace("\\n", " ").replace("\\r", " ");
                            if (detail.length() > 180) detail = detail.substring(0, 180);
                            reasonBuilder.append(": ").append(detail);
                        }
                        cause = cause.getCause();
                        causeDepth++;
                    }
                    final String failureReason = reasonBuilder.toString();
                    runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                            .setTitle("فشل اتصال الريسيفر")
                            .setMessage("تفاصيل خطأ الاتصال المشفر:\\n\\n" + failureReason)
                            .setPositiveButton("حسنًا", null)
                            .show());
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    String responseText = response.body() == null ? "" : response.body().string();
                    boolean successful = response.isSuccessful();
                    response.close();
                    String secret = "";
                    boolean paired = false;
                    boolean validSecret = false;
                    try {
                        if (successful) {
                            org.json.JSONObject pairingResult = new org.json.JSONObject(responseText);
                            paired = pairingResult.optBoolean("paired", false);
                            secret = pairingResult.optString("secret", "");
                            if (secret.matches("[A-Za-z0-9_-]{43}")) {
                                byte[] decodedSecret = android.util.Base64.decode(secret,
                                        android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP
                                                | android.util.Base64.NO_PADDING);
                                validSecret = decodedSecret.length == 32;
                            }
                        }
                    } catch (Exception ignored) { }
                    final String pairedSecret = secret;
                    final boolean validPairingResponse = paired && validSecret;
                    runOnUiThread(() -> {
                        if (successful && validPairingResponse) {
                            String protectedSecret = encryptReceiverSecret(pairedSecret);
                            if (protectedSecret.isEmpty()) {
                                Toast.makeText(MainActivity.this,
                                        "تعذر حفظ مفتاح الربط على الجهاز.",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            int port = Uri.parse(endpoint).getPort();
                            String savedAddress = pairedHost + ":" + (port > 0 ? port : TARGET_PORT);
                            boolean saved = prefs.edit()
                                    .putString(PREF_RECEIVER_SECRET + pairedHost, protectedSecret)
                                    .putString(PREF_RECEIVER_TLS_PIN + pairedHost, fingerprint)
                                    .putString("tv_ip", savedAddress)
                                    .commit();
                            if (!saved) {
                                Toast.makeText(MainActivity.this,
                                        "تعذر حفظ بيانات الربط. أعد المحاولة.",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            List<SavedReceiverDevice> savedDevices = loadSavedReceiverDevices();
                            savedDevices.removeIf(item -> item.ip.equalsIgnoreCase(savedAddress)
                                    || item.ip.equalsIgnoreCase(pairedHost));
                            savedDevices.add(0, new SavedReceiverDevice(
                                    receiverName == null || receiverName.trim().isEmpty()
                                            ? "شاشة " + pairedHost : receiverName.trim(),
                                    savedAddress));
                            saveSavedReceiverDevices(savedDevices);
                            if (etTvIp != null) etTvIp.setText(savedAddress);
                            updateReceiverStatus();
                            pendingPairingReceiverIp = "";
                            Toast.makeText(MainActivity.this,
                                    "✅ تم الربط بالريسيفر والتحقق من بصمته بنجاح.",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "فشل الربط: الكود غير صحيح أو انتهت صلاحيته. اعرض QR أو كودًا جديدًا من الريسيفر.",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        } catch (Exception error) {
            Toast.makeText(this, "تعذر إعداد اتصال TLS المثبت بالبصمة", Toast.LENGTH_LONG).show();
        }
    }

    private void showManualReceiverPairingDialog() {
        String tvIp = prefs.getString("tv_ip", "").trim();
        if (tvIp.isEmpty()) {
            Toast.makeText(this, "احفظ عنوان IP الشاشة أولاً", Toast.LENGTH_LONG).show();
            showManualIpDialog();
            return;
        }

        final String endpoint;
        final String pairedHost;
        final String existingPin;
        try {
            endpoint = buildReceiverHttpsEndpoint(tvIp);
            pairedHost = Uri.parse(endpoint).getHost();
            if (pairedHost == null) throw new IllegalArgumentException("Invalid receiver host");
            String storedPin = prefs.getString(PREF_RECEIVER_TLS_PIN + pairedHost, "");
            existingPin = isValidReceiverFingerprint(storedPin) ? storedPin : "";
        } catch (Exception invalidAddress) {
            Toast.makeText(this, "عنوان الريسيفر غير صالح", Toast.LENGTH_LONG).show();
            return;
        }

        // A first-time manual code-only pairing cannot authenticate the receiver.
        // Use the QR, which carries the receiver certificate pin and is verified
        // before the one-time code is sent over TLS.
        if (existingPin.isEmpty()) {
            new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                    .setTitle("ربط آمن بالريسيفر")
                    .setMessage("لأمان الاتصال، امسح QR الظاهر على شاشة الريسيفر. التطبيق هيتحقق من هوية الريسيفر تلقائيًا، من غير ما تنسخ أو تكتب بصمة الشهادة.")
                    .setPositiveButton("مسح QR", (dialog, which) -> startReceiverQrScanner())
                    .setNegativeButton("إلغاء", null)
                    .show();
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
        instructions.setText("هوية الريسيفر محفوظة ومتحقق منها على هذا الجهاز. أدخل كود الربط الظاهر على شاشة الريسيفر.");
        panel.addView(instructions);
        panel.addView(codeInput);

        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("ربط مع الريسيفر")
                .setView(panel)
                .setPositiveButton("ربط الآن", (dialog, which) -> {
                    String code = codeInput.getText().toString().trim();
                    if (!code.matches("[0-9]{8}")) {
                        Toast.makeText(this, "أدخل كودًا صحيحًا من 8 أرقام", Toast.LENGTH_LONG).show();
                        return;
                    }
                    performReceiverPairing(endpoint, pairedHost, existingPin, code,
                            getSavedReceiverName(pairedHost).isEmpty() ? "الريسيفر" : getSavedReceiverName(pairedHost));
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
                String rawIp = item.optString("ip", "").trim();
                String ip = rawIp.isEmpty() ? "" : normalizeReceiverAddress(rawIp);
                if (!ip.isEmpty() && !containsReceiverIp(devices, ip)) {
                    devices.add(new SavedReceiverDevice(name.isEmpty() ? "شاشة " + (devices.size() + 1) : name, ip));
                }
            }
        } catch (Exception ignored) { }
        String rawSelectedIp = prefs.getString("tv_ip", "").trim();
        String selectedIp = rawSelectedIp.isEmpty() ? "" : normalizeReceiverAddress(rawSelectedIp);
        if (!selectedIp.isEmpty()) {
            if (!rawSelectedIp.equals(selectedIp)) prefs.edit().putString("tv_ip", selectedIp).apply();
            if (!containsReceiverIp(devices, selectedIp)) devices.add(0, new SavedReceiverDevice("الشاشة الحالية", selectedIp));
        }
        saveSavedReceiverDevices(devices);
        return devices;
    }

    private String normalizeReceiverAddress(String address) {
        String endpoint = buildReceiverHttpsEndpoint(address);
        Uri parsed = Uri.parse(endpoint);
        String host = parsed.getHost();
        int port = parsed.getPort() > 0 ? parsed.getPort() : TARGET_PORT;
        return (host != null && host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
    private String receiverAddressKey(String address) {
        try { return Uri.parse(buildReceiverHttpsEndpoint(address)).getHost().toLowerCase(java.util.Locale.ROOT); }
        catch (Exception ignored) { return address == null ? "" : address.trim().toLowerCase(java.util.Locale.ROOT); }
    }
    private String getSavedReceiverName(String ip) {
        if (ip == null || ip.trim().isEmpty()) return "";
        for (SavedReceiverDevice device : loadSavedReceiverDevices()) {
            if (receiverAddressKey(device.ip).equals(receiverAddressKey(ip))) return device.name == null ? "" : device.name.trim();
        }
        return "";
    }

    private boolean containsReceiverIp(List<SavedReceiverDevice> devices, String ip) {
        String key = receiverAddressKey(ip);
        for (SavedReceiverDevice device : devices) {
            if (receiverAddressKey(device.ip).equals(key)) return true;
        }
        return false;
    }

    private void saveSavedReceiverDevices(List<SavedReceiverDevice> devices) {
        org.json.JSONArray array = new org.json.JSONArray();
        List<SavedReceiverDevice> unique = new ArrayList<>();
        try {
            for (SavedReceiverDevice device : devices) {
                if (device == null || device.ip == null || device.ip.trim().isEmpty()) continue;
                String canonical;
                try { canonical = normalizeReceiverAddress(device.ip); } catch (Exception invalid) { continue; }
                String key = receiverAddressKey(canonical);
                boolean duplicate = false;
                for (SavedReceiverDevice saved : unique) if (receiverAddressKey(saved.ip).equals(key)) { duplicate = true; break; }
                if (!duplicate) unique.add(new SavedReceiverDevice(device.name, canonical));
            }
            for (SavedReceiverDevice device : unique) {
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("name", device.name); item.put("ip", device.ip); array.put(item);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString(PREF_SAVED_RECEIVERS, array.toString()).apply();
    }

    private void selectSavedReceiver(SavedReceiverDevice device, AlertDialog dialog) {
        String canonicalIp = normalizeReceiverAddress(device.ip);
        prefs.edit().putString("tv_ip", canonicalIp).apply();
        if (etTvIp != null) etTvIp.setText(canonicalIp);
        updateReceiverStatus();
        if (dialog != null) dialog.dismiss();
        if (hasSecureReceiverPairing()) {
            pendingPairingReceiverIp = "";
            Toast.makeText(this, "تم اختيار " + device.name, Toast.LENGTH_SHORT).show();
        } else {
            requestPairingForCurrentReceiver();
        }
    }

    private boolean isReceiverOnline(String ip) {
        if (ip == null || ip.trim().isEmpty()) return false;
        try {
            String endpoint = buildReceiverHttpsEndpoint(ip);
            String host = Uri.parse(endpoint).getHost();
            if (host == null) return false;
            String pin = prefs.getString(PREF_RECEIVER_TLS_PIN + host, "");
            String encryptedSecret = getSharedPreferences("AboEzzatSharePrefs",
                    MODE_PRIVATE | MODE_MULTI_PROCESS)
                    .getString(PREF_RECEIVER_SECRET + host, "");
            String secret = decryptReceiverSecret(encryptedSecret);
            if (!isValidReceiverFingerprint(pin) || secret.length() < 40) {
                return false;
            }
            OkHttpClient probe = getPinnedReceiverClient(host).newBuilder()
                    .connectTimeout(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .callTimeout(2200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url(endpoint + "/?command=status")
                    .get().build();
            try (Response response = probe.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private AlertDialog savedReceiverDialog;
    private void showSavedReceiverDialog() {
        if (savedReceiverDialog != null && savedReceiverDialog.isShowing()) savedReceiverDialog.dismiss();
        List<SavedReceiverDevice> devices = loadSavedReceiverDevices();
        final String activeIp = prefs.getString("tv_ip", "").trim();
        // Keep the currently selected receiver at the top; all other saved
        // receivers remain below it in their existing saved order.
        devices.sort((a, b) -> {
            boolean aActive = receiverAddressKey(a.ip).equals(receiverAddressKey(activeIp));
            boolean bActive = receiverAddressKey(b.ip).equals(receiverAddressKey(activeIp));
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
            boolean isActiveDevice = receiverAddressKey(device.ip).equals(receiverAddressKey(activeIp));
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
            edit.setOnClickListener(v -> {
                if (dialogRef[0] != null && dialogRef[0].isShowing()) dialogRef[0].dismiss();
                showEditReceiverDialog(device);
            });
            delete.setOnClickListener(v -> {
                if (dialogRef[0] != null && dialogRef[0].isShowing()) dialogRef[0].dismiss();
                new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                    .setTitle("حذف الجهاز؟")
                    .setMessage("سيتم حذف " + device.name + " من القائمة المحفوظة ومسح مفتاح الربط وبصمة الشهادة الخاصة به.")
                    .setNegativeButton("رجوع للقائمة", (d, w) ->
                            new Handler(Looper.getMainLooper()).post(() -> showSavedReceiverDialog()))
                    .setPositiveButton("حذف", (d, w) -> {
                        List<SavedReceiverDevice> latest = loadSavedReceiverDevices();
                        latest.removeIf(item -> receiverAddressKey(item.ip).equals(receiverAddressKey(device.ip)));
                        saveSavedReceiverDevices(latest);
                        String selectedIp = prefs.getString("tv_ip", "").trim();
                        if (!selectedIp.isEmpty() && receiverAddressKey(selectedIp).equals(receiverAddressKey(device.ip))) {
                            prefs.edit().remove("tv_ip").apply();
                            if (etTvIp != null) etTvIp.setText("");
                            updateReceiverStatus();
                        }
                        try {
                            String endpoint = buildReceiverHttpsEndpoint(device.ip);
                            String host = Uri.parse(endpoint).getHost();
                            if (host != null) {
                                prefs.edit().remove(PREF_RECEIVER_SECRET + host)
                                        .remove(PREF_RECEIVER_TLS_PIN + host).commit();
                            }
                        } catch (Exception ignored) { }
                        new Handler(Looper.getMainLooper()).post(() -> showSavedReceiverDialog());
                    }).show();
            });
            list.addView(card, new LinearLayout.LayoutParams(-1, -2));
            View gap = new View(this);
            list.addView(gap, new LinearLayout.LayoutParams(1, 8));
            AsyncTask.execute(() -> {
                boolean online = isReceiverOnline(device.ip);
                runOnUiThread(() -> {
                    if (!isFinishing() && status.getParent() != null) {
                        boolean active = receiverAddressKey(device.ip).equals(receiverAddressKey(prefs.getString("tv_ip", "").trim()));
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
        TextView multi = new TextView(this);
        multi.setText("بث جماعي");
        multi.setTextColor(0xFFFFE082);
        multi.setGravity(Gravity.CENTER);
        multi.setPadding(14, 12, 14, 12);
        GradientDrawable multiBg = new GradientDrawable();
        multiBg.setColor(0xFF3B2F16);
        multiBg.setCornerRadius(14);
        multiBg.setStroke(1, 0xFFFFC857);
        multi.setBackground(multiBg);
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
        footer.addView(multi, new LinearLayout.LayoutParams(-2, -2));
        footer.addView(scan, new LinearLayout.LayoutParams(-2, -2));
        root.addView(footer);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme).setView(root)
                .setNegativeButton("إغلاق", null).create();
        savedReceiverDialog = dialog;
        dialogRef[0] = dialog;
        add.setOnClickListener(v -> {
            if (dialog.isShowing()) dialog.dismiss();
            showAddReceiverMethodDialog();
        });
        scan.setOnClickListener(v -> {
            if (dialog.isShowing()) dialog.dismiss();
            scanForTvDevices();
        });
        multi.setOnClickListener(v -> {
            if (dialog.isShowing()) dialog.dismiss();
            showMultiReceiverSelection();
        });
        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    private Set<String> loadMultiReceiverTargets() {
        Set<String> result = new HashSet<>();
        try {
            org.json.JSONArray array = new org.json.JSONArray(prefs.getString("multi_receiver_targets_json", "[]"));
            for (int i = 0; i < array.length(); i++) {
                String ip = array.optString(i, "").trim();
                if (!ip.isEmpty()) result.add(receiverAddressKey(ip));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void showMultiReceiverSelection() {
        List<SavedReceiverDevice> devices = loadSavedReceiverDevices();
        if (devices.isEmpty()) {
            Toast.makeText(this, "احفظ واربط جهاز ريسيفر واحد على الأقل أولاً", Toast.LENGTH_LONG).show();
            showSavedReceiverDialog();
            return;
        }
        Set<String> selected = loadMultiReceiverTargets();
        String active = prefs.getString("tv_ip", "").trim();
        if (selected.isEmpty() && !active.isEmpty()) selected.add(active);
        boolean[] checked = new boolean[devices.size()];
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            SavedReceiverDevice device = devices.get(i);
            checked[i] = selected.contains(receiverAddressKey(device.ip));
            labels[i] = device.name + " — " + device.ip;
        }
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("اختيار أجهزة البث الجماعي")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("حفظ الاختيار", (dialog, which) -> {
                    org.json.JSONArray array = new org.json.JSONArray();
                    int pairedCount = 0;
                    for (int i = 0; i < devices.size(); i++) {
                        if (!checked[i]) continue;
                        String ip = normalizeReceiverAddress(devices.get(i).ip);
                        array.put(ip);
                        if (isReceiverSecurelyPaired(ip)) pairedCount++;
                    }
                    prefs.edit().putString("multi_receiver_targets_json", array.toString()).apply();
                    Toast.makeText(this, "تم اختيار " + array.length() + " جهاز؛ المربوط منها بأمان: " + pairedCount,
                            Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("إلغاء البث الجماعي", (dialog, which) ->
                        prefs.edit().remove("multi_receiver_targets_json").apply())
                .setNegativeButton("إلغاء", null).show();
    }

    private boolean isReceiverSecurelyPaired(String address) {
        try {
            String endpoint = buildReceiverHttpsEndpoint(address);
            String host = Uri.parse(endpoint).getHost();
            String pin = prefs.getString(PREF_RECEIVER_TLS_PIN + host, "");
            String secret = decryptReceiverSecret(prefs.getString(PREF_RECEIVER_SECRET + host, ""));
            return host != null && isValidReceiverFingerprint(pin) && secret != null && secret.length() >= 40;
        } catch (Exception ignored) { return false; }
    }

    private List<String> getPlaybackReceiverTargets() {
        Set<String> configured = loadMultiReceiverTargets();
        List<String> targets = new ArrayList<>();
        if (configured.isEmpty()) {
            String active = prefs.getString("tv_ip", "").trim();
            if (!active.isEmpty()) targets.add(active);
            return targets;
        }
        for (SavedReceiverDevice device : loadSavedReceiverDevices()) {
            if (configured.contains(receiverAddressKey(device.ip)) && isReceiverSecurelyPaired(device.ip)) {
                String canonical = normalizeReceiverAddress(device.ip);
                if (!targets.contains(canonical)) targets.add(canonical);
            }
        }
        return targets;
    }

    private void showAddReceiverMethodDialog() {
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("إضافة جهاز جديد")
                .setItems(new String[]{"يدوي", "بالباركود (QR)"}, (dialog, which) -> {
                    if (which == 0) {
                        showEditReceiverDialog(null);
                    } else {
                        showReceiverPairingDialog();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
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
                .setNegativeButton("رجوع", (dialog, which) ->
                        new Handler(Looper.getMainLooper()).post(() -> showSavedReceiverDialog()))
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String deviceName = name.getText().toString().trim();
                    String rawDeviceIp = ip.getText().toString().trim();
                    String deviceIp;
                    try { deviceIp = normalizeReceiverAddress(rawDeviceIp); }
                    catch (Exception invalid) { Toast.makeText(this, "عنوان IP أو المنفذ غير صحيح", Toast.LENGTH_LONG).show(); return; }
                    if (deviceIp.isEmpty()) {
                        Toast.makeText(this, "اكتب عنوان IP أولاً", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (deviceName.isEmpty()) deviceName = "شاشة " + (loadSavedReceiverDevices().size() + 1);
                    List<SavedReceiverDevice> latest = loadSavedReceiverDevices();
                    if (existing != null) {
                        for (SavedReceiverDevice item : latest) {
                            if (receiverAddressKey(item.ip).equals(receiverAddressKey(existing.ip))) {
                                item.name = deviceName;
                                item.ip = deviceIp;
                            }
                        }
                        if (receiverAddressKey(prefs.getString("tv_ip", "")).equals(receiverAddressKey(existing.ip))) {
                            prefs.edit().putString("tv_ip", deviceIp).apply();
                            if (etTvIp != null) etTvIp.setText(deviceIp);
                            updateReceiverStatus();
                        }
                    } else {
                        for (SavedReceiverDevice item : latest) {
                            if (receiverAddressKey(item.ip).equals(receiverAddressKey(deviceIp))) {
                                item.name = deviceName;
                                saveSavedReceiverDevices(latest);
                                new Handler(Looper.getMainLooper()).post(() -> showSavedReceiverDialog());
                                return;
                            }
                        }
                        latest.add(new SavedReceiverDevice(deviceName, deviceIp));
                    }
                    saveSavedReceiverDevices(latest);
                    new Handler(Looper.getMainLooper()).post(() -> showSavedReceiverDialog());
                }).show();
    }

    private void scanForTvDevices() {
        tvStatusBadge.setText("🔍 جاري الفحص...");
        Toast.makeText(this, "جاري البحث الآمن عن أجهزة Receiver...", Toast.LENGTH_SHORT).show();

        AsyncTask.execute(() -> {
            final java.util.Set<String> discovered = new java.util.LinkedHashSet<>();
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock multicastLock = null;
            java.net.MulticastSocket socket = null;
            java.net.InetAddress group = null;
            try {
                if (wifi != null) {
                    multicastLock = wifi.createMulticastLock("AboEzzatCastReceiverDiscovery");
                    multicastLock.setReferenceCounted(false);
                    multicastLock.acquire();
                }
                socket = new java.net.MulticastSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(37020));
                socket.setSoTimeout(4000);
                group = java.net.InetAddress.getByName("239.255.42.99");
                socket.joinGroup(group);

                long deadline = android.os.SystemClock.elapsedRealtime() + 4000L;
                byte[] buffer = new byte[512];
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (java.net.SocketTimeoutException timeout) {
                        break;
                    }
                    String message = new String(packet.getData(), packet.getOffset(),
                            packet.getLength(), java.nio.charset.StandardCharsets.UTF_8).trim();
                    String[] parts = message.split("\\|", -1);
                    if (parts.length != 3 || !"ABOEZZAT_RECEIVER".equals(parts[0])) continue;
                    String advertisedIp = parts[1].trim();
                    int advertisedPort;
                    try { advertisedPort = Integer.parseInt(parts[2].trim()); }
                    catch (NumberFormatException invalidPort) { continue; }
                    if (advertisedPort != TARGET_PORT
                            || !advertisedIp.matches("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")) {
                        continue;
                    }
                    java.net.InetAddress address = java.net.InetAddress.getByName(advertisedIp);
                    if (address.isSiteLocalAddress() && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress() && !address.isMulticastAddress()) {
                        discovered.add(advertisedIp);
                    }
                }
            } catch (Exception discoveryError) {
                android.util.Log.w("AboEzzatCast", "Receiver multicast discovery failed", discoveryError);
            } finally {
                if (socket != null) {
                    try { if (group != null) socket.leaveGroup(group); } catch (Exception ignored) {}
                    try { socket.close(); } catch (Exception ignored) {}
                }
                if (multicastLock != null && multicastLock.isHeld()) {
                    try { multicastLock.release(); } catch (Exception ignored) {}
                }
            }

            final List<String> availableDevices = new ArrayList<>(discovered);
            runOnUiThread(() -> {
                if (!availableDevices.isEmpty()) {
                    showDeviceSelectionDialog(availableDevices);
                } else {
                    tvStatusBadge.setText("● لم تكتشف أجهزة");
                    Toast.makeText(MainActivity.this,
                            "لم يصل إعلان اكتشاف من Receiver. تأكد أن الجهازين على نفس الشبكة وأن Receiver مفتوح.",
                            Toast.LENGTH_LONG).show();
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
        if (handleWebVideoCasterCaptureShortcut(intent)) return;
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
        webVideoCasterCaptureLaunch = true;
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
            if (path.matches(".*\\.(mp4|m3u8|webm|mov|mkv|avi|flv|m4v|mpg|mpeg|3gp|ts|mpd|mp3|m4a|aac|ogg|oga|opus|wav|flac|jpg|jpeg|png|webp|gif|avif|heic)$")) return true;
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
            Uri sharedMedia = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (sharedMedia != null) {
                receiveSharedLocalMedia(java.util.Collections.singletonList(sharedMedia), intent.getType());
                return false;
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && intent.getType() != null) {
            ArrayList<Uri> sharedMedia = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (sharedMedia != null && !sharedMedia.isEmpty()) {
                receiveSharedLocalMedia(sharedMedia, intent.getType());
                return false;
            }
        }
        return false;
    }

    private void receiveSharedLocalMedia(List<Uri> mediaUris, String sharedMime) {
        if (mediaUris == null || mediaUris.isEmpty()) return;
        videoQueue.clear();
        slideshowQueue.clear();
        boolean imageShare = sharedMime != null && sharedMime.toLowerCase(java.util.Locale.ROOT).startsWith("image/");
        for (Uri uri : mediaUris) {
            if (uri == null) continue;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // Sharesheet grants are often temporary rather than persistable; the
                // current activity still owns the read grant while the shared task runs.
            }
            if (imageShare) slideshowQueue.add(uri);
            else videoQueue.add(uri);
            addRecentMedia(uri);
        }
        if (imageShare && !slideshowQueue.isEmpty()) {
            currentSlideIndex = 0;
            persistMediaQueues();
            setSelectedMediaUri(slideshowQueue.get(0));
            if (slideshowQueue.size() > 1) {
                isSlideshowRunning = true;
                slideHandler.postDelayed(slideRunnable, 5000);
            }
        } else if (!videoQueue.isEmpty()) {
            currentVideoIndex = 0;
            persistMediaQueues();
            setSelectedMediaUri(videoQueue.get(0));
        } else {
            return;
        }

        // The foreground media server starts asynchronously with MainActivity.
        // Retry briefly until its TLS listener is ready instead of losing a valid share.
        final Uri firstMedia = imageShare ? slideshowQueue.get(0) : videoQueue.get(0);
        final int[] attempts = {0};
        Handler retryHandler = new Handler(Looper.getMainLooper());
        Runnable sendWhenReady = new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                if (CastKeepAliveService.isLocalServerReady()) {
                    castLocalMediaToTv(true);
                } else if (++attempts[0] < 20) {
                    retryHandler.postDelayed(this, 300L);
                } else {
                    Toast.makeText(MainActivity.this,
                            "خادم بث الملفات لم يصبح جاهزًا؛ افتح التطبيق وأعد إرسال الملف.",
                            Toast.LENGTH_LONG).show();
                }
            }
        };
        retryHandler.postDelayed(sendWhenReady, 300L);
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
        sendUrlToAllReceivers(mediaUrl, clipboardShortcutLaunch || webVideoCasterCaptureLaunch);
    }

    private void sendUrlToAllReceivers(String mediaUrl, boolean finishWhenDone) {
        List<String> targets = getPlaybackReceiverTargets();
        if (targets.isEmpty()) {
            Toast.makeText(this, "اختار ريسيفر مربوط بأمان أولاً", Toast.LENGTH_LONG).show();
            if (finishWhenDone) finish();
            return;
        }

        // Probe every selected receiver first, then ask once for the whole group.
        // A timeout is not proof of active playback, so that receiver remains eligible.
        Runnable playBatch = () -> {
            if (isLikelyVideoClipboardUrl(mediaUrl)) {
                sendSingleUrlQueueBatch(targets, mediaUrl, "replace", finishWhenDone);
            } else {
                dispatchUrlBatch(targets, mediaUrl, finishWhenDone);
            }
        };
        java.util.List<String> activeTargets = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger completedProbes = new java.util.concurrent.atomic.AtomicInteger();
        Runnable[] finishProbeBatch = new Runnable[1];
        finishProbeBatch[0] = () -> {
            if (completedProbes.incrementAndGet() != targets.size()) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (finishWhenDone) finish();
                    return;
                }
                if (activeTargets.isEmpty()) {
                    playBatch.run();
                    return;
                }
                new AlertDialog.Builder(MainActivity.this, R.style.ModernDialogTheme)
                        .setTitle("فيه محتوى شغال على أجهزة الاستقبال")
                        .setMessage("تم تأكيد وجود تشغيل فعلي على " + activeTargets.size()
                                + " من " + targets.size()
                                + " أجهزة. هل تريد إيقاف التشغيل الحالي على الأجهزة المحددة وتشغيل المحتوى الجديد؟")
                        .setPositiveButton("تشغيل بدل الحالي", (dialog, which) -> playBatch.run())
                        .setNeutralButton("إضافة بعد الحالي", (dialog, which) ->
                                sendSingleUrlQueueBatch(targets, mediaUrl, "append", finishWhenDone))
                        .setNegativeButton("إلغاء", (dialog, which) -> {
                            if (finishWhenDone) finish();
                        })
                        .setOnCancelListener(dialog -> {
                            if (finishWhenDone) finish();
                        })
                        .show();
            });
        };

        for (String tvIp : targets) {
            try {
                String endpoint = buildReceiverHttpsEndpoint(tvIp);
                String host = Uri.parse(endpoint).getHost();
                if (host == null) throw new IOException("Invalid receiver host");
                OkHttpClient probe = getPinnedReceiverClient(host).newBuilder()
                        .connectTimeout(1800, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(2200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .callTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build();
                Request statusRequest = new Request.Builder()
                        .url(endpoint + "/?command=status").get().build();
                probe.newCall(statusRequest).enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException error) {
                        finishProbeBatch[0].run();
                    }
                    @Override public void onResponse(Call call, Response response) {
                        try {
                            if (response.isSuccessful() && response.body() != null) {
                                JSONObject state = new JSONObject(response.body().string());
                                long updatedAt = state.optLong("mediaStateUpdatedAt", 0L);
                                long age = System.currentTimeMillis() - updatedAt;
                                String type = state.optString("mediaType", "none");
                                boolean active = state.optBoolean("mediaActive", false)
                                        && updatedAt > 0L && age >= -5000L && age <= 60000L
                                        && ("video".equals(type) || "audio".equals(type) || "image".equals(type));
                                if (active) activeTargets.add(tvIp);
                            }
                        } catch (Exception ignored) {
                            // Invalid or stale status is not evidence of active playback.
                        } finally {
                            response.close();
                            finishProbeBatch[0].run();
                        }
                    }
                });
            } catch (Exception error) {
                finishProbeBatch[0].run();
            }
        }
    }

    private void sendSingleUrlQueueBatch(List<String> targets, String mediaUrl,
                                         String mode, boolean finishWhenDone) {
        try {
            JSONObject item = new JSONObject();
            item.put("url", mediaUrl);
            item.put("sourceUrl", mediaUrl);
            item.put("headers", new JSONObject());
            JSONArray items = new JSONArray();
            items.put(item);
            JSONObject payload = new JSONObject();
            payload.put("mode", mode);
            payload.put("items", items);
            sendQueuePayloadToReceivers(targets, payload, finishWhenDone, "تمت إضافة الرابط إلى قائمة التشغيل");
        } catch (Exception error) {
            Toast.makeText(this, "تعذر تجهيز الرابط للإضافة إلى القائمة.", Toast.LENGTH_LONG).show();
            if (finishWhenDone) finish();
        }
    }

    private void sendSingleLocalQueueBatch(List<String> targets, String mediaUrl,
                                           String mediaPin, String mode) {
        try {
            JSONObject item = new JSONObject();
            item.put("url", mediaUrl);
            item.put("mediaPin", mediaPin);
            item.put("headers", new JSONObject());
            JSONArray items = new JSONArray();
            items.put(item);
            JSONObject payload = new JSONObject();
            payload.put("mode", mode);
            payload.put("items", items);
            sendQueuePayloadToReceivers(targets, payload, false, "تمت إضافة الوسيط إلى قائمة التشغيل");
        } catch (Exception error) {
            Toast.makeText(this, "تعذر تجهيز الوسيط للإضافة إلى القائمة.", Toast.LENGTH_LONG).show();
        }
    }

    private void sendQueuePayloadToReceivers(List<String> targets, JSONObject payload,
                                             boolean finishWhenDone, String successMessage) {
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        for (String target : targets) {
            try {
                String endpoint = buildReceiverHttpsEndpoint(target);
                String host = Uri.parse(endpoint).getHost();
                if (host == null) throw new IOException("Invalid receiver host");
                OkHttpClient client = getPinnedReceiverClient(host);
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json; charset=utf-8"), payload.toString());
                Request request = new Request.Builder().url(endpoint + "/queue").post(body).build();
                client.newCall(request).enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException error) {
                        finishQueuePayloadTarget(targets.size(), accepted, finished, finishWhenDone, successMessage);
                    }
                    @Override public void onResponse(Call call, Response response) {
                        try { if (response.isSuccessful()) accepted.incrementAndGet(); }
                        finally {
                            response.close();
                            finishQueuePayloadTarget(targets.size(), accepted, finished, finishWhenDone, successMessage);
                        }
                    }
                });
            } catch (Exception error) {
                finishQueuePayloadTarget(targets.size(), accepted, finished, finishWhenDone, successMessage);
            }
        }
    }

    private void finishQueuePayloadTarget(int total, java.util.concurrent.atomic.AtomicInteger accepted,
                                          java.util.concurrent.atomic.AtomicInteger finished,
                                          boolean finishWhenDone, String successMessage) {
        if (finished.incrementAndGet() == total) runOnUiThread(() -> {
            Toast.makeText(this, successMessage + " إلى " + accepted.get()
                    + " من " + total + " أجهزة", Toast.LENGTH_LONG).show();
            if (finishWhenDone) finish();
        });
    }

    private void dispatchUrlBatch(List<String> targets, String mediaUrl, boolean finishWhenDone) {
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        for (String tvIp : targets) {
            sendUrlToSingleReceiver(tvIp, mediaUrl, finishWhenDone,
                    targets.size(), accepted, finished);
        }
    }

    private String stateTypeForPrompt(String type) {
        if ("audio".equals(type)) return "صوت";
        if ("image".equals(type)) return "صورة";
        return "فيديو";
    }

    private void sendUrlToSingleReceiver(String tvIp, String mediaUrl, boolean finishWhenDone,
            int total, java.util.concurrent.atomic.AtomicInteger accepted,
            java.util.concurrent.atomic.AtomicInteger finished) {
        try {
            String endpoint = buildReceiverHttpsEndpoint(tvIp);
            String host = Uri.parse(endpoint).getHost();
            if (host == null) throw new IOException("Invalid receiver host");
            OkHttpClient pinnedClient = getPinnedReceiverClient(host);
            Request request = new Request.Builder()
                    .url(endpoint + "/?url=" + URLEncoder.encode(mediaUrl, "UTF-8") + "&open=browser")
                    .get().build();
            pinnedClient.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    finishUrlTarget(total, accepted, finished, finishWhenDone);
                }
                @Override public void onResponse(Call call, Response response) {
                    try { if (response.isSuccessful()) accepted.incrementAndGet(); }
                    finally { response.close(); }
                    finishUrlTarget(total, accepted, finished, finishWhenDone);
                }
            });
        } catch (Exception error) {
            finishUrlTarget(total, accepted, finished, finishWhenDone);
        }
    }

    private void finishUrlTarget(int total, java.util.concurrent.atomic.AtomicInteger accepted,
                                 java.util.concurrent.atomic.AtomicInteger finished, boolean finishWhenDone) {
        if (finished.incrementAndGet() == total) runOnUiThread(() -> {
            if (total > 1) Toast.makeText(this, "تم إرسال الرابط إلى " + accepted.get() + " من " + total + " أجهزة", Toast.LENGTH_LONG).show();
            else if (!finishWhenDone) Toast.makeText(this, accepted.get() == 1 ? "تم إرسال الرابط إلى الريسيفر" : "تعذر إرسال الرابط إلى الريسيفر", Toast.LENGTH_SHORT).show();
            if (finishWhenDone) finish();
        });
    }

    private void trackedClientForUrlSend(OkHttpClient pinnedClient, Request request, String mediaUrl) {
        pinnedClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "فشل إرسال الرابط إلى الريسيفر: " + error.getClass().getSimpleName(),
                            Toast.LENGTH_LONG).show();
                    if (clipboardShortcutLaunch || webVideoCasterCaptureLaunch) finish();
                });
            }
            @Override public void onResponse(Call call, Response response) {
                int code = response.code();
                String body = "";
                try { if (response.body() != null) body = response.body().string(); }
                catch (IOException ignored) { }
                finally { response.close(); }
                final String detail = body;
                runOnUiThread(() -> {
                    if (code == 200) {
                        Toast.makeText(MainActivity.this,
                                "✅ استلم الريسيفر الرابط وبدأ معالجة طلب التشغيل",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivity.this,
                                "الريسيفر رفض الرابط (HTTP " + code + ") "
                                        + (detail.isEmpty() ? "" : detail),
                                Toast.LENGTH_LONG).show();
                    }
                    if (clipboardShortcutLaunch || webVideoCasterCaptureLaunch) finish();
                });
            }
        });
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

    private String buildReceiverHttpsEndpoint(String address) {
        String value = address == null ? "" : address.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Empty receiver address");
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        Uri parsed = Uri.parse(value);
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (host == null || host.trim().isEmpty() || parsed.getUserInfo() != null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || (parsed.getPath() != null && !parsed.getPath().isEmpty()
                    && !"/".equals(parsed.getPath()))
                || parsed.getQuery() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("Invalid receiver address");
        }
        int port = parsed.getPort() < 0 ? TARGET_PORT : parsed.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid receiver port");
        String authorityHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return "https://" + authorityHost + ":" + port;
    }

    private boolean isValidReceiverFingerprint(String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            return Base64.decode(fingerprint,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING).length == 32;
        } catch (Exception invalid) {
            return false;
        }
    }

    private boolean matchesReceiverCertificate(X509Certificate certificate, byte[] expectedPin)
            throws java.security.cert.CertificateException {
        if (certificate == null || expectedPin == null || expectedPin.length != 32) return false;
        certificate.checkValidity();
        try {
            byte[] actual = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded());
            return java.security.MessageDigest.isEqual(actual, expectedPin);
        } catch (java.security.cert.CertificateException e) {
            throw e;
        } catch (Exception e) {
            throw new java.security.cert.CertificateException("Could not verify receiver certificate", e);
        }
    }

    private OkHttpClient createPinnedReceiverClient(String expectedHost, String fingerprint) throws Exception {
        if (expectedHost == null || expectedHost.trim().isEmpty()
                || !isValidReceiverFingerprint(fingerprint)) {
            throw new IllegalArgumentException("Invalid receiver TLS pin");
        }
        final byte[] expectedPin = Base64.decode(fingerprint,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        X509TrustManager pinnedTrustManager = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException("Client certificates are not accepted");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                if (chain == null || chain.length == 0
                        || !matchesReceiverCertificate(chain[0], expectedPin)) {
                    throw new java.security.cert.CertificateException("Receiver certificate pin mismatch");
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[]{pinnedTrustManager},
                new java.security.SecureRandom());
        javax.net.ssl.HostnameVerifier pinnedHostnameVerifier = (hostname, session) -> {
            if (hostname == null || !expectedHost.equalsIgnoreCase(hostname)) return false;
            try {
                java.security.cert.Certificate[] peerCertificates = session.getPeerCertificates();
                return peerCertificates.length > 0
                        && peerCertificates[0] instanceof X509Certificate
                        && matchesReceiverCertificate((X509Certificate) peerCertificates[0], expectedPin);
            } catch (Exception invalidPeer) {
                return false;
            }
        };
        return client.newBuilder()
                .sslSocketFactory(sslContext.getSocketFactory(), pinnedTrustManager)
                .hostnameVerifier(pinnedHostnameVerifier)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    private OkHttpClient getPinnedReceiverClient(String host) throws Exception {
        String fingerprint = prefs.getString(PREF_RECEIVER_TLS_PIN + host, "");
        if (!isValidReceiverFingerprint(fingerprint)) {
            throw new IOException("Receiver certificate has not been pinned");
        }
        return createPinnedReceiverClient(host, fingerprint);
    }

    private boolean hasSecureReceiverPairing() {
        String savedIp = prefs.getString("tv_ip", "").trim();
        if (savedIp.isEmpty()) return false;
        try {
            String endpoint = buildReceiverHttpsEndpoint(savedIp);
            String host = Uri.parse(endpoint).getHost();
            String fingerprint = prefs.getString(PREF_RECEIVER_TLS_PIN + host, "");
            return host != null && isValidReceiverFingerprint(fingerprint)
                    && decryptReceiverSecret(prefs.getString(PREF_RECEIVER_SECRET + host, "")).length() >= 40;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showPairingRequiredMessage() {
        requestPairingForCurrentReceiver();
    }

    private void requestPairingForCurrentReceiver() {
        String selectedIp = prefs.getString("tv_ip", "").trim();
        if (selectedIp.isEmpty()) {
            Toast.makeText(this, "اختار جهازًا من قائمة الأجهزة المحفوظة أولاً.", Toast.LENGTH_LONG).show();
            showSavedReceiverDialog();
            return;
        }
        try {
            String endpoint = buildReceiverHttpsEndpoint(selectedIp);
            String host = Uri.parse(endpoint).getHost();
            if (host == null) throw new IllegalArgumentException("Invalid receiver host");
            pendingPairingReceiverIp = host;
            new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                    .setTitle("الجهاز غير مربوط")
                    .setMessage("الجهاز ده لسه مش مربوط بأبو عزت كاست. افتح شاشة الريسيفر وامسح رمز QR منها علشان نتحقق من هويته ونحفظ الربط الآمن. مش هتحتاج تكرر الخطوة دي بعد نجاح الربط.")
                    .setPositiveButton("مسح QR والربط", (dialog, which) -> startReceiverQrScanner())
                    .setNegativeButton("إلغاء", (dialog, which) -> pendingPairingReceiverIp = "")
                    .show();
        } catch (Exception invalidAddress) {
            pendingPairingReceiverIp = "";
            Toast.makeText(this, "عنوان الجهاز المحفوظ غير صالح. عدّله من الأجهزة المحفوظة.", Toast.LENGTH_LONG).show();
        }
    }

    private void sendUrlToTvFromUi(String mediaUrl) {
        sendUrlToAllReceivers(mediaUrl, false);
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
        if (requestCode == PICK_RECEIVER_QR_IMAGE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                decodeReceiverQrFromGallery(data.getData());
            } else {
                Toast.makeText(this, "تم إلغاء اختيار صورة QR.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == com.google.zxing.integration.android.IntentIntegrator.REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null
                    && data.hasExtra(ReceiverQrCaptureActivity.EXTRA_GALLERY_IMAGE_URI)) {
                String selectedUri = data.getStringExtra(ReceiverQrCaptureActivity.EXTRA_GALLERY_IMAGE_URI);
                if (selectedUri != null && !selectedUri.isEmpty()) {
                    decodeReceiverQrFromGallery(Uri.parse(selectedUri));
                }
                return;
            }
            com.google.zxing.integration.android.IntentResult scanResult =
                    com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(
                            requestCode, resultCode, data);
            if (scanResult != null) {
                if (scanResult.getContents() != null) {
                    handleReceiverPairingQr(scanResult.getContents());
                } else {
                    Toast.makeText(this, "تم إلغاء مسح QR", Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }

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
        if (requestCode == PICK_NAMED_PLAYLIST_ITEMS_REQUEST) {
            if (resultCode == RESULT_OK && data != null && activeNamedPlaylist != null) {
                appendNamedPlaylistItems(activeNamedPlaylist, data);
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
                        slideIntervalMs = prefs.getLong("slideshow_interval_ms", 5000L);
                        if (slideIntervalMs < 1000L || slideIntervalMs > 60000L) slideIntervalMs = 5000L;
                        isSlideshowRunning = true;
                        slideHandler.postDelayed(slideRunnable, slideIntervalMs);
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

    private String getLocalMediaRoutePath(Uri uri, String mime) {
        String normalizedMime = mime == null ? "" : mime.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalizedMime.startsWith("image/")) {
            return "/media.jpg";
        }

        String extension = null;
        boolean unknownMime = normalizedMime.isEmpty()
                || "application/octet-stream".equals(normalizedMime);
        if (!unknownMime) {
            extension = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(normalizedMime);
        }

        // SAF and cloud document providers often report application/octet-stream
        // (or no MIME type) even for ordinary JPEG/PNG photos. In that case the
        // display name is the only reliable media-type hint. Always inspect it
        // instead of accidentally routing an image through the MP4 player.
        if (unknownMime || extension == null || extension.trim().isEmpty()) {
            android.database.Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                        null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String name = cursor.getString(index);
                        if (name != null) {
                            int dot = name.lastIndexOf('.');
                            if (dot >= 0 && dot < name.length() - 1) {
                                extension = name.substring(dot + 1);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }
        }

        if (extension != null) {
            extension = extension.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
        }

        // The receiver intentionally uses one stable image route; the HTTP
        // response still carries the original MIME type from ContentResolver.
        String imageExtensions = "|jpg|jpeg|jpe|png|webp|gif|bmp|avif|heic|heif|tif|tiff|";
        if (extension != null && imageExtensions.contains("|" + extension + "|")) {
            return "/media.jpg";
        }

        String supported = "|mp4|m4v|mkv|webm|mov|avi|flv|mpg|mpeg|3gp|ogv|wmv|vob|ts|m2ts|mp3|m4a|aac|ogg|oga|opus|wav|flac|mid|midi|";
        if (extension == null || extension.isEmpty() || !supported.contains("|" + extension + "|")) {
            extension = "mp4";
        }
        return "/media." + extension;
    }

    private void castLocalMediaToTv(boolean showToast) {
        List<String> targets = getPlaybackReceiverTargets();
        if (targets.isEmpty()) {
            if (showToast) Toast.makeText(this, "مفيش أجهزة محددة ومربوطة بأمان للبث. راجع اختيار البث الجماعي.", Toast.LENGTH_LONG).show();
            return;
        }
        String phoneIp = getDeviceIpAddress();
        if (phoneIp.isEmpty() || phoneIp.equals("0.0.0.0")) return;
        if (!CastKeepAliveService.isLocalServerReady()) {
            if (showToast) Toast.makeText(this, "خادم بث الملفات لم يصبح جاهزًا بعد؛ انتظر لحظة ثم أعد الإرسال.", Toast.LENGTH_LONG).show();
            return;
        }
        String mime = selectedMediaUri == null ? null : getContentResolver().getType(selectedMediaUri);
        String mediaToken = CastKeepAliveService.getMediaAccessToken();
        if (mediaToken == null || !mediaToken.matches("[A-Za-z0-9_-]{43}")) {
            if (showToast) Toast.makeText(this, "تعذر تجهيز رابط الوسائط المحمي؛ أعد اختيار الملف.", Toast.LENGTH_LONG).show();
            return;
        }
        String mediaPath = getLocalMediaRoutePath(selectedMediaUri, mime);
        final String mediaTlsPin;
        try { mediaTlsPin = CastTlsIdentity.getCertificateFingerprint(); }
        catch (Exception error) {
            Toast.makeText(this, "تعذر تجهيز شهادة تشفير الوسائط؛ أعد تشغيل التطبيق.", Toast.LENGTH_LONG).show();
            return;
        }
        if (mediaTlsPin == null || !mediaTlsPin.matches("[A-Za-z0-9_-]{43}")) {
            Toast.makeText(this, "بصمة شهادة الوسائط غير صالحة.", Toast.LENGTH_LONG).show();
            return;
        }
        String targetUrl = "https://" + phoneIp + ":8088" + mediaPath + "?token=" + mediaToken;
        probeAllLocalMediaReceivers(targets, targetUrl, mediaTlsPin, showToast);
    }

    // Collect every selected Receiver's authenticated status before showing one
    // group-level decision. A timeout is not evidence of active playback.
    private void probeAllLocalMediaReceivers(List<String> targets, String mediaUrl,
            String mediaPin, boolean showToast) {
        java.util.List<String> activeTargets = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        Runnable[] onProbeFinished = new Runnable[1];
        onProbeFinished[0] = () -> {
            if (completed.incrementAndGet() != targets.size()) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Runnable sendAll = () -> {
                    java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
                    java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
                    for (String target : targets) {
                        sendLocalMediaToReceiver(target, mediaUrl, mediaPin, showToast,
                                targets.size(), accepted, finished);
                    }
                };
                Runnable addAll = () -> sendSingleLocalQueueBatch(targets, mediaUrl, mediaPin, "append");

                if (activeTargets.isEmpty()) {
                    sendAll.run();
                    return;
                }
                new AlertDialog.Builder(MainActivity.this, R.style.ModernDialogTheme)
                        .setTitle("فيه محتوى شغال على أجهزة الاستقبال")
                        .setMessage("تم تأكيد وجود تشغيل فعلي على " + activeTargets.size()
                                + " من " + targets.size()
                                + " أجهزة. هل تريد إيقاف التشغيل الحالي على الأجهزة المحددة وتشغيل الملف الجديد؟")
                        .setPositiveButton("تشغيل بدل الحالي", (dialog, which) -> sendAll.run())
                        .setNeutralButton("إضافة بعد الحالي", (dialog, which) -> addAll.run())
                        .setNegativeButton("إلغاء", null)
                        .show();
            });
        };

        for (String tvIp : targets) {
            try {
                String endpoint = buildReceiverHttpsEndpoint(tvIp);
                String host = Uri.parse(endpoint).getHost();
                if (host == null) throw new IOException("Invalid receiver host");
                OkHttpClient client = getPinnedReceiverClient(host).newBuilder()
                        .connectTimeout(1800, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(2200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .callTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS).build();
                Request status = new Request.Builder().url(endpoint + "/?command=status").get().build();
                client.newCall(status).enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException error) {
                        onProbeFinished[0].run();
                    }
                    @Override public void onResponse(Call call, Response response) {
                        try {
                            if (response.isSuccessful() && response.body() != null) {
                                JSONObject state = new JSONObject(response.body().string());
                                long updated = state.optLong("mediaStateUpdatedAt", 0L);
                                long age = System.currentTimeMillis() - updated;
                                String type = state.optString("mediaType", "none");
                                boolean active = state.optBoolean("mediaActive", false)
                                        && updated > 0L && age >= -5000L && age <= 60000L
                                        && ("video".equals(type) || "audio".equals(type) || "image".equals(type));
                                if (active) activeTargets.add(tvIp);
                            }
                        } catch (Exception ignored) {
                            // Malformed or stale status is not proof of active playback.
                        } finally {
                            response.close();
                            onProbeFinished[0].run();
                        }
                    }
                });
            } catch (Exception error) {
                onProbeFinished[0].run();
            }
        }
    }

    private void probeReceiverBeforeLocalMediaSend(String tvIp, String mediaUrl, String mediaPin,
            boolean showToast, int total, java.util.concurrent.atomic.AtomicInteger accepted,
            java.util.concurrent.atomic.AtomicInteger finished) {
        try {
            String endpoint = buildReceiverHttpsEndpoint(tvIp);
            String host = Uri.parse(endpoint).getHost();
            if (host == null) throw new IOException("Invalid receiver host");
            OkHttpClient client = getPinnedReceiverClient(host).newBuilder()
                    .connectTimeout(1800, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(2200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .callTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS).build();
            Request status = new Request.Builder().url(endpoint + "/?command=status").get().build();
            client.newCall(status).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    sendLocalMediaToReceiver(tvIp, mediaUrl, mediaPin, showToast, total, accepted, finished);
                }
                @Override public void onResponse(Call call, Response response) {
                    boolean active = false;
                    String type = "none";
                    try {
                        if (response.isSuccessful() && response.body() != null) {
                            JSONObject state = new JSONObject(response.body().string());
                            long updated = state.optLong("mediaStateUpdatedAt", 0L);
                            long age = System.currentTimeMillis() - updated;
                            type = state.optString("mediaType", "none");
                            active = state.optBoolean("mediaActive", false)
                                    && updated > 0L && age >= -5000L && age <= 60000L
                                    && ("video".equals(type) || "audio".equals(type) || "image".equals(type));
                        }
                    } catch (Exception ignored) {
                        active = false;
                    } finally {
                        response.close();
                    }
                    if (!active) {
                        sendLocalMediaToReceiver(tvIp, mediaUrl, mediaPin, showToast, total, accepted, finished);
                        return;
                    }
                    final String label = stateTypeForPrompt(type);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            finishLocalMediaTarget(total, accepted, finished, showToast);
                            return;
                        }
                        new AlertDialog.Builder(MainActivity.this, R.style.ModernDialogTheme)
                                .setTitle("فيه محتوى شغال على الريسيفر")
                                .setMessage("الريسيفر أكد وجود تشغيل فعلي (" + label
                                        + "). هل تريد إيقافه وتشغيل الملف الجديد؟")
                                .setPositiveButton("تشغيل بدل الحالي", (dialog, which) ->
                                        sendLocalMediaToReceiver(tvIp, mediaUrl, mediaPin, showToast,
                                                total, accepted, finished))
                                .setNegativeButton("إلغاء", (dialog, which) ->
                                        finishLocalMediaTarget(total, accepted, finished, showToast))
                                .setOnCancelListener(dialog ->
                                        finishLocalMediaTarget(total, accepted, finished, showToast))
                                .show();
                    });
                }
            });
        } catch (Exception error) {
            sendLocalMediaToReceiver(tvIp, mediaUrl, mediaPin, showToast, total, accepted, finished);
        }
    }

    private void sendLocalMediaToReceiver(String tvIp, String mediaUrl, String mediaPin,
            boolean showToast, int total, java.util.concurrent.atomic.AtomicInteger accepted,
            java.util.concurrent.atomic.AtomicInteger finished) {
        try {
            String endpoint = buildReceiverHttpsEndpoint(tvIp);
            String host = Uri.parse(endpoint).getHost();
            if (host == null) throw new IOException("Invalid receiver host");
            OkHttpClient pinnedClient = getPinnedReceiverClient(host);
            String fullUrl = endpoint + "/?url=" + URLEncoder.encode(mediaUrl, "UTF-8")
                    + "&mediaPin=" + mediaPin;
            Request req = new Request.Builder().url(fullUrl).get().build();
            pinnedClient.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    finishLocalMediaTarget(total, accepted, finished, showToast);
                }
                @Override public void onResponse(Call call, Response response) {
                    try { if (response.isSuccessful() && response.code() == 200) accepted.incrementAndGet(); }
                    finally { response.close(); }
                    finishLocalMediaTarget(total, accepted, finished, showToast);
                }
            });
        } catch (Exception error) {
            finishLocalMediaTarget(total, accepted, finished, showToast);
        }
    }

    private void finishLocalMediaTarget(int total, java.util.concurrent.atomic.AtomicInteger accepted,
            java.util.concurrent.atomic.AtomicInteger finished, boolean showToast) {
        if (finished.incrementAndGet() == total && showToast) runOnUiThread(() ->
                Toast.makeText(this, total > 1
                        ? "وصل المحتوى إلى " + accepted.get() + " من " + total + " أجهزة."
                        : (accepted.get() == 0 ? "تعذر إرسال المحتوى إلى الريسيفر" : "تم إرسال المحتوى إلى الريسيفر"),
                        Toast.LENGTH_LONG).show());

    }

    private void sendRemoteCommand(String cmd) {
        List<String> targets = getPlaybackReceiverTargets();
        if (targets.isEmpty()) {
            showPairingRequiredMessage();
            return;
        }
        for (String tvIp : targets) {
            try {
                String endpoint = buildReceiverHttpsEndpoint(tvIp);
                String receiverHost = Uri.parse(endpoint).getHost();
                OkHttpClient pinnedClient = getPinnedReceiverClient(receiverHost);
                Request req = new Request.Builder().url(endpoint + "/?command=" + Uri.encode(cmd)).build();
                pinnedClient.newCall(req).enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException error) { }
                    @Override public void onResponse(Call call, Response response) { response.close(); }
                });
            } catch (Exception ignored) { }
        }
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
