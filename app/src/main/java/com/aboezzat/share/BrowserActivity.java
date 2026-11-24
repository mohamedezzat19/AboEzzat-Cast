package com.aboezzat.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.Response;
import org.json.JSONObject;

public class BrowserActivity extends Activity {

    private static final String TAG = "AboEzzatCastBrowser";

    private final List<WebView> tabList = new ArrayList<>();
    private final List<String> tabTitles = new ArrayList<>();
    private final Map<WebView, Long> navigationStartedAt = new HashMap<>();
    private int currentTabIndex = -1;
    // Volatile snapshots shared with WebView worker / JavaScript bridge threads.
    private volatile WebView activeWebView;
    private volatile String activePageUrl = "";
    // Canonical YouTube watch URL reported by the page itself; WebView navigation callbacks
    // alone miss YouTube SPA transitions from the home feed to an in-page video player.
    private volatile String activeYouTubeWatchUrl = "";
    // The privileged custom-site bridge is available only on the app-owned home document.
    private static final String INTERNAL_HOME_BASE_URL = "https://abo-ezzat-home.invalid/home/";
    private volatile WebView homeBridgeWebView;
    private volatile boolean homePageBridgeActive = false;

    private LinearLayout rootLayout;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private LinearLayout appNavigation;
    private ProgressBar progressBar;
    private FrameLayout webContainer;
    private FrameLayout fullScreenContainer;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 7401;
    private ValueCallback<Uri[]> pendingFileChooserCallback;
    private static final int GEOLOCATION_PERMISSION_REQUEST_CODE = 7402;
    private GeolocationPermissions.Callback pendingGeolocationCallback;
    private String pendingGeolocationOrigin = "";

    private EditText etUrl;
    private Button btnSniff;
    private Button btnCast;
    private TextView btnTabs;
    private View browserMenuButton;

    // Consent bar for external app/store redirects and pop-up requests.
    private LinearLayout redirectBar;
    private TextView redirectSiteLabel;
    private Button redirectOpenButton;
    private String pendingRedirectUrl = "";
    private Runnable hideRedirectBarRunnable;

    private PowerManager.WakeLock wakeLock;

    public static class CapturedMedia {
        public String url;
        public String title;
        public String format;
        public String posterUrl;
        public Bitmap posterBitmap;
        public int mediaType;
        public boolean isAudio;
        // YouTube appears as one playable item; its separately hosted audio
        // rendition stays paired internally, never exposed as a broken standalone item.
        public String pairedAudioUrl;
        public java.util.Map<String, String> requestHeaders = new java.util.LinkedHashMap<>();
        public boolean isSelected = false;

        public CapturedMedia(String url, String title, String format, String posterUrl, int mediaType) {
            this.url = url;
            this.title = title;
            this.format = format;
            this.posterUrl = posterUrl;
            this.mediaType = mediaType;
            this.isAudio = (mediaType == 2);
        }
    }

    private final List<CapturedMedia> capturedList = Collections.synchronizedList(new ArrayList<CapturedMedia>());
    private BaseAdapter capturedMediaAdapter;
    private final Set<String> mp4ProbeRequests = Collections.synchronizedSet(new HashSet<String>());
    private final OkHttpClient client = new OkHttpClient();
    private String tvIp = "";
    private String lastDiscoveredPoster = "";
    private String lastDiscoveredTitle = "";
    // Only capture resources after the user activates a visible video player.
    private volatile String activePlaybackKey = "";
    // Drama Live uses an isolated player frame; remember only the frame the user focused.
    private volatile String activePlayerFrameUrl = "";
    private volatile long activePlayerFrameActivatedAtMs = 0L;
    private final Set<String> dramaLiveProbeRequests = Collections.synchronizedSet(new HashSet<String>());
    // Isolated manual capture window for generic embedded players; never armed by share-sheet routes.
    private volatile long manualSniffUntilMs = 0L;
    // Automatic generic capture is armed only for ordinary web players and TikTok LIVE.
    private volatile long genericAutoSniffUntilMs = 0L;
    // Share-sheet TikTok LIVE links request an automatic cast after a verified live stream is found.
    private volatile boolean tikTokLiveAutoCastPending = false;
    private volatile String youtubeExtractionInProgressFor = "";
    private volatile String youtubeExtractedFor = "";
    private volatile String youtubeCastPendingFor = "";

    private boolean isDramaLivePage(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String host = Uri.parse(url).getHost();
            return host != null && (host.equalsIgnoreCase("dramalive.cc")
                    || host.toLowerCase(Locale.ROOT).endsWith(".dramalive.cc"));
        } catch (Exception ignored) { return false; }
    }

    private boolean isManualSniffActive() {
        return System.currentTimeMillis() <= manualSniffUntilMs;
    }

    private boolean isGenericAutoSniffActive() {
        return System.currentTimeMillis() <= genericAutoSniffUntilMs;
    }

    private boolean isTikTokLivePage(String url) {
        if (url == null || !url.startsWith("http")) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            return (host.equals("tiktok.com") || host.endsWith(".tiktok.com"))
                    && path.matches(".*/live(?:/.*)?$");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isTikTokLiveStreamCandidate(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isEmpty()) return false;
        String lower = mediaUrl.toLowerCase(Locale.ROOT);
        // Match the same extension-independent CDN signatures used by ShareActivity.
        // TikTok LIVE CDNs may not contain "tiktok" in their hostname, so a valid
        // HLS/FLV stream must not be rejected merely because the CDN is branded differently.
        if (lower.contains("pull-") || lower.contains(".m3u8") || lower.contains(".flv")) return true;
        try {
            Uri uri = Uri.parse(mediaUrl);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean tiktokCdn = host.contains("tiktok");
            return tiktokCdn && (lower.matches(".*\\.(m3u8|flv)(?:[?#].*)?$")
                    || (lower.contains("live") && (lower.contains("stream") || lower.contains("video"))));
        } catch (Exception ignored) {
            return false;
        }
    }

    // Threads and Instagram keep their own dedicated capture pipelines.
    // The generic web-player network sniffer must never collect their requests.
    private boolean isThreadsOrInstagramPage(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("instagram.com") || host.endsWith(".instagram.com")
                    || host.equals("threads.net") || host.endsWith(".threads.net")
                    || host.equals("threads.com") || host.endsWith(".threads.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean shouldAutoCaptureGenericPage(String url) {
        if (url == null || !url.startsWith("http")) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
            if (host.equals("youtube.com") || host.endsWith(".youtube.com")
                    || host.equals("youtu.be") || host.equals("youtube-nocookie.com")
                    || host.endsWith(".youtube-nocookie.com")) return false;
            if (host.equals("tiktok.com") || host.endsWith(".tiktok.com")) {
                return path.matches(".*\\/live(?:\\/.*)?$");
            }
            // Keep existing platform-specific capture paths isolated and untouched.
            if (host.equals("facebook.com") || host.endsWith(".facebook.com")
                    || host.equals("fb.watch") || host.equals("instagram.com") || host.endsWith(".instagram.com")
                    || host.equals("threads.net") || host.endsWith(".threads.net")
                    || host.equals("threads.com") || host.endsWith(".threads.com")
                    || host.equals("x.com") || host.endsWith(".x.com")
                    || host.equals("twitter.com") || host.endsWith(".twitter.com")) return false;
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // Network-level ad hosts only. Avoid blocking general tag managers or
    // analytics libraries that some sites also use to initialize their players.
    private final List<String> adDomains = Arrays.asList(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "pagead2.googlesyndication.com",
            "adcolony.com", "criteo.com", "adnxs.com", "outbrain.com",
            "taboola.com", "popads.net", "popcash.net", "propellerads.com",
            "adsterra.com", "exoclick.com", "smartadserver.com",
            "zedo.com", "adroll.com", "pubmatic.com", "rubiconproject.com",
            "trafficjunky.net", "juicyads.com", "hilltopads.net",
            "onclickads.net", "ero-advertising.com"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AboEzzatShare:AudioPlaybackWakeLock");
            }
        } catch (Exception ignored) {}

        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        tvIp = prefs.getString("tv_ip", "").trim();

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#080C27"));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(8, 8, 8, 8);
        topBar.setBackgroundColor(Color.parseColor("#0D1733"));
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        Button btnMenu = new Button(this);
        btnMenu.setText("⋮");
        btnMenu.setAllCaps(false);
        btnMenu.setTextColor(Color.WHITE);
        btnMenu.setBackgroundColor(Color.TRANSPARENT);
        btnMenu.setTextSize(24);
        btnMenu.setMinWidth(0);
        btnMenu.setMinimumWidth(0);
        btnMenu.setPadding(0, 0, 0, 0);
        btnMenu.setContentDescription("قائمة المتصفح");
        btnMenu.setOnClickListener(v -> showBrowserMoreMenu());
        browserMenuButton = btnMenu;
        topBar.addView(btnMenu, new LinearLayout.LayoutParams(dp(44), dp(44)));

        etUrl = new EditText(this);
        etUrl.setHint("اكتب الرابط أو ابحث...");
        etUrl.setHintTextColor(Color.GRAY);
        etUrl.setTextColor(Color.WHITE);
        etUrl.setTextSize(13);
        etUrl.setSingleLine(true);
        etUrl.setTextDirection(View.TEXT_DIRECTION_LTR);
        etUrl.setBackgroundColor(Color.parseColor("#182442"));
        etUrl.setPadding(15, 10, 15, 10);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        urlParams.setMargins(5, 0, 5, 0);
        etUrl.setOnEditorActionListener((v, actionId, event) -> {
            loadTargetUrl(etUrl.getText().toString().trim());
            return true;
        });
        topBar.addView(etUrl, urlParams);

        btnSniff = new Button(this);
        btnSniff.setText("🎯");
        btnSniff.setTextSize(16);
        btnSniff.setTextColor(Color.WHITE);
        btnSniff.setBackgroundColor(Color.parseColor("#D32F2F"));
        btnSniff.setOnClickListener(v -> triggerManualSniff());
        LinearLayout.LayoutParams sniffParams = new LinearLayout.LayoutParams(dp(46), dp(44));
        sniffParams.setMargins(0, 5, 5, 0);
        topBar.addView(btnSniff, sniffParams);

        btnCast = new Button(this);
        btnCast.setText("📺 0");
        btnCast.setTextSize(13);
        btnCast.setTextColor(Color.WHITE);
        btnCast.setBackgroundColor(Color.parseColor("#182442"));
        btnCast.setVisibility(View.GONE);
        btnCast.setOnClickListener(v -> showMediaListDialog());
        topBar.addView(btnCast, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rootLayout.addView(topBar);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6));
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        rootLayout.addView(progressBar);

        FrameLayout baseFrame = new FrameLayout(this);
        webContainer = new FrameLayout(this);
        fullScreenContainer = new FrameLayout(this);
        fullScreenContainer.setVisibility(View.GONE);
        fullScreenContainer.setBackgroundColor(Color.BLACK);

        baseFrame.addView(webContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        baseFrame.addView(fullScreenContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootLayout.addView(baseFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Color.parseColor("#0D1733"));
        bottomBar.setPadding(dp(5), dp(5), dp(5), dp(5));
        bottomBar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bottomBar.setGravity(Gravity.CENTER);

        // Browser bottom controls match Share New order: refresh, back, home, forward, more.
        bottomBar.addView(createBottomButton("↻", v -> {
            WebView currentWeb = getCurrentWebView();
            if (currentWeb != null) currentWeb.reload();
        }));

        bottomBar.addView(createBottomButton("‹", v -> {
            WebView currentWeb = getCurrentWebView();
            if (currentWeb != null && currentWeb.canGoBack()) currentWeb.goBack();
            else Toast.makeText(this, "مفيش صفحة سابقة", Toast.LENGTH_SHORT).show();
        }));

        bottomBar.addView(createBottomButton("⌂", v -> loadHomePage()));

        bottomBar.addView(createBottomButton("›", v -> {
            WebView currentWeb = getCurrentWebView();
            if (currentWeb != null && currentWeb.canGoForward()) currentWeb.goForward();
            else Toast.makeText(this, "مفيش صفحة تالية", Toast.LENGTH_SHORT).show();
        }));

        btnTabs = createBottomButton("▢", v -> showTabsDialog());
        btnTabs.setTextSize(21);
        btnTabs.setContentDescription("عرض علامات التبويب المفتوحة وإضافة تبويب");
        bottomBar.addView(btnTabs);

        rootLayout.addView(bottomBar);

        // Keep Home / Media / Library navigation visible while browsing.
        appNavigation = new LinearLayout(this);
        appNavigation.setOrientation(LinearLayout.HORIZONTAL);
        // Match the fixed left-to-right order used by the home screen:
        // Home on the left, Media in the middle, Library on the right.
        appNavigation.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        appNavigation.setGravity(Gravity.CENTER);
        appNavigation.setPadding(dp(4), dp(3), dp(4), dp(3));
        appNavigation.setBackgroundColor(Color.parseColor("#07132B"));
        String[] navLabels = {"🏠\nالرئيسية", "🎞️\nالوسائط", "📚\nالمكتبة"};
        for (int index = 0; index < navLabels.length; index++) {
            final int destination = index;
            Button navButton = new Button(this);
            navButton.setText(navLabels[index]);
            navButton.setAllCaps(false);
            navButton.setTextSize(12);
            navButton.setTextColor(Color.WHITE);
            navButton.setMinHeight(0);
            navButton.setPadding(dp(2), 0, dp(2), 0);
            android.graphics.drawable.GradientDrawable navBg = new android.graphics.drawable.GradientDrawable();
            navBg.setColor(Color.parseColor("#14284D"));
            navBg.setCornerRadius(dp(8));
            navButton.setBackground(navBg);
            LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            navLp.setMargins(dp(2), 0, dp(2), 0);
            appNavigation.addView(navButton, navLp);
            navButton.setOnClickListener(v -> {
                // Bring the existing MainActivity forward instead of finishing
                // BrowserActivity. This keeps WebView tabs, history, scroll state,
                // and the current browsing session alive underneath it.
                Intent homeIntent = new Intent(BrowserActivity.this, MainActivity.class);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                if (destination == 1) {
                    homeIntent.putExtra("open_media_picker", true);
                } else if (destination == 2) {
                    homeIntent.putExtra("open_library", true);
                }
                startActivity(homeIntent);
            });
        }
        rootLayout.addView(appNavigation);

        setContentView(rootLayout);

        String startUrl = "about:home";
        if (getIntent() != null) {
            if (getIntent().getStringExtra("url") != null) {
                startUrl = getIntent().getStringExtra("url");
            } else if (getIntent().getData() != null) {
                startUrl = getIntent().getData().toString();
            }
        }
        tikTokLiveAutoCastPending = getIntent() != null
                && getIntent().getBooleanExtra("auto_cast_tiktok_live", false);
        addNewTab(startUrl);
        if (getIntent() != null && getIntent().getBooleanExtra("open_fg_code_dialog", false)) {
            getIntent().removeExtra("open_fg_code_dialog");
            showFgCodeDialog();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (intent == null) return;
        if (intent.getBooleanExtra("open_fg_code_dialog", false)) {
            intent.removeExtra("open_fg_code_dialog");
            showFgCodeDialog();
        }
        String targetUrl = intent.getStringExtra("url");
        if ((targetUrl == null || targetUrl.trim().isEmpty()) && intent.getData() != null) {
            targetUrl = intent.getData().toString();
        }

        // Reuse the current browser session/tab when a saved Library item is opened.
        // FLAG_ACTIVITY_REORDER_TO_FRONT brings this activity forward, so onCreate()
        // will not run again; handle the incoming URL here instead.
        if (targetUrl != null && !targetUrl.trim().isEmpty()) {
            final String urlToOpen = targetUrl.trim();
            loadTargetUrl(urlToOpen);
        }
    }

    private void showBrowserMoreMenu() {
        View anchor = browserMenuButton;
        if (anchor == null) return;
        // Keep one flat menu: each browser action appears once and opens its
        // destination directly instead of chaining into another menu.
        android.widget.PopupMenu menu = new android.widget.PopupMenu(new android.view.ContextThemeWrapper(this, R.style.ModernPopupTheme), anchor);
        menu.getMenu().add("مدير التنزيلات").setOnMenuItemClickListener(item -> { showBrowserDownloadsDialog(); return true; });
        menu.getMenu().add("تحديث الصفحة").setOnMenuItemClickListener(item -> { WebView current = getCurrentWebView(); if (current != null) current.reload(); return true; });
        menu.getMenu().add("سجل التصفح").setOnMenuItemClickListener(item -> { showBrowserHistoryDialog(); return true; });
        menu.getMenu().add("المواقع المحفوظة").setOnMenuItemClickListener(item -> { showQuickSitesDialog(); return true; });
        menu.getMenu().add("حفظ الصفحة الحالية").setOnMenuItemClickListener(item -> {
            WebView current = getCurrentWebView();
            if (current != null && current.getUrl() != null
                    && (current.getUrl().startsWith("http://") || current.getUrl().startsWith("https://"))) {
                String siteUrl = current.getUrl();
                String siteName = current.getTitle();
                if (siteName == null || siteName.trim().isEmpty()) siteName = siteUrl;
                saveCustomSite(siteName, siteUrl);
                Toast.makeText(this, "تم حفظ الموقع في المفضلة", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "افتح موقعاً أولاً لحفظه", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        menu.getMenu().add("مسح ذاكرة التخزين المؤقت").setOnMenuItemClickListener(item -> {
            WebView current = getCurrentWebView();
            if (current != null) current.clearCache(true);
            Toast.makeText(this, "تم مسح ذاكرة المتصفح", Toast.LENGTH_SHORT).show();
            return true;
        });
        menu.getMenu().add("مسح الكوكيز وبيانات المواقع").setOnMenuItemClickListener(item -> { clearBrowserSiteData(); return true; });
        menu.getMenu().add("وكيل المستخدم (User-Agent)").setOnMenuItemClickListener(item -> { showUserAgentDialog(); return true; });
        menu.getMenu().add("وكيل مستخدم TikTok LIVE").setOnMenuItemClickListener(item -> { showTikTokLiveUserAgentDialog(); return true; });
        // FG Code must be added to the actual browser overflow menu used by the top-right button.
        menu.show();
    }


    private String getConfiguredUserAgent() {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        String configured = prefs.getString("browser_user_agent", "").trim();
        if (!configured.isEmpty()) return configured;
        return "Mozilla/5.0 (Linux; Android 14; Mobile; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36";
    }

    // TikTok pages must honor the same browser-wide choice as every other
    // ordinary browser page; TikTok LIVE capture uses its separate preference.
    private String getTikTokCompatibleUserAgent() {
        return getConfiguredUserAgent();
    }

    // Apply a newly selected browser identity immediately to the active tab.
    private void applyConfiguredUserAgentToCurrentPage() {
        WebView current = getCurrentWebView();
        if (current == null) return;
        current.getSettings().setUserAgentString(getConfiguredUserAgent());
        String pageUrl = current.getUrl();
        if (pageUrl != null && pageUrl.startsWith("http")) current.reload();
    }

    private void showUserAgentDialog() {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        String[] labels = {
                "الإعدادات الافتراضية للتطبيق (WebView)",
                "Chrome Android", "Chrome Windows", "Chrome macOS", "Safari iPhone",
                "إضافة وكيل مستخدم مخصص"
        };
        String[] values = {
                WebSettings.getDefaultUserAgent(this),
                "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                ""
        };
        String current = getConfiguredUserAgent();
        int selected = 1;
        for (int i = 0; i < values.length - 1; i++) {
            if (values[i].equals(current)) { selected = i; break; }
        }
        if (current.equals(prefs.getString("browser_user_agent_custom", "").trim())) {
            selected = values.length - 1;
        }
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("اختيار وكيل المستخدم")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    if (which == labels.length - 1) {
                        EditText input = new EditText(this);
                        input.setSingleLine(false);
                        input.setMinLines(2);
                        input.setText(prefs.getString("browser_user_agent_custom", ""));
                        input.setHint("الصق User-Agent هنا");
                        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                                .setTitle("وكيل مستخدم مخصص")
                                .setView(input)
                                .setNegativeButton("إلغاء", null)
                                .setPositiveButton("حفظ", (d, w) -> {
                                    String custom = input.getText().toString().trim();
                                    if (custom.length() < 20 || custom.length() > 1024
                                            || custom.contains("\\r") || custom.contains("\\n")) {
                                        Toast.makeText(this, "وكيل المستخدم غير صالح", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    prefs.edit().putString("browser_user_agent_custom", custom)
                                            .putString("browser_user_agent", custom).apply();
                                    applyConfiguredUserAgentToCurrentPage();
                                    Toast.makeText(this, "تم حفظ وكيل المستخدم المخصص", Toast.LENGTH_SHORT).show();
                                }).show();
                    } else {
                        prefs.edit().putString("browser_user_agent", values[which]).apply();
                        applyConfiguredUserAgentToCurrentPage();
                        dialog.dismiss();
                        Toast.makeText(this, "تم حفظ وكيل المستخدم: " + labels[which], Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showTikTokLiveUserAgentDialog() {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        String[] labels = {
                "Android WebView الافتراضي",
                "Chrome Android", "Chrome Windows", "Chrome macOS", "Safari iPhone",
                "إضافة User-Agent مخصص"
        };
        String[] values = {
                WebSettings.getDefaultUserAgent(this).replaceAll(";\\s*wv", "").replace("Version/4.0 ", "").trim(),
                "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                ""
        };
        String current = prefs.getString("tiktok_live_user_agent", "").trim();
        int selected = 0;
        for (int i = 0; i < values.length - 1; i++) {
            if (values[i].equals(current)) { selected = i; break; }
        }
        if (current.equals(prefs.getString("tiktok_live_user_agent_custom", "").trim())) {
            selected = values.length - 1;
        }
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("وكيل مستخدم TikTok LIVE فقط")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    if (which == labels.length - 1) {
                        EditText input = new EditText(this);
                        input.setSingleLine(false);
                        input.setMinLines(2);
                        input.setText(prefs.getString("tiktok_live_user_agent_custom", ""));
                        input.setHint("الصق User-Agent الخاص بـ TikTok LIVE");
                        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                                .setTitle("User-Agent مخصص لـ TikTok LIVE")
                                .setView(input)
                                .setNegativeButton("إلغاء", null)
                                .setPositiveButton("حفظ", (d, w) -> {
                                    String custom = input.getText().toString().trim();
                                    if (custom.length() < 20 || custom.length() > 1024
                                            || custom.indexOf('\r') >= 0 || custom.indexOf('\n') >= 0) {
                                        Toast.makeText(this, "وكيل المستخدم غير صالح", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    prefs.edit().putString("tiktok_live_user_agent_custom", custom)
                                            .putString("tiktok_live_user_agent", custom).apply();
                                    Toast.makeText(this, "تم حفظ User-Agent الخاص بـ TikTok LIVE", Toast.LENGTH_SHORT).show();
                                }).show();
                    } else {
                        prefs.edit().putString("tiktok_live_user_agent", values[which]).apply();
                        dialog.dismiss();
                        Toast.makeText(this, "تم حفظ وكيل TikTok LIVE: " + labels[which], Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showBrowserLinkActions(String title, String url, Runnable deleteAction) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(12));
        TextView fullUrl = new TextView(this);
        fullUrl.setText(url);
        fullUrl.setTextColor(Color.WHITE);
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
                .setView(panel).setNegativeButton("إغلاق", null).create();
        addBrowserLinkAction(actions, dialog, "🗑️", "مسح الرابط", deleteAction, 0xFFB3261E);
        addBrowserLinkAction(actions, dialog, "📋", "نسخ الرابط", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("URL", url));
                Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show();
            }
        }, 0xFF245B8F);
        addBrowserLinkAction(actions, dialog, "📤", "إرسال الرابط", () -> {
            Intent intent = new Intent(BrowserActivity.this, MainActivity.class);
            intent.putExtra("send_saved_url_to_tv", url);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }, 0xFF287A52);
        dialog.show();
    }

    private void addBrowserLinkAction(LinearLayout parent, AlertDialog dialog, String icon, String label,
                                      Runnable action, int backgroundColor) {
        TextView button = new TextView(this);
        button.setText(icon + "\n" + label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(4), dp(12), dp(4), dp(12));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
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

    private void showBrowserHistoryDialog() {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("browser_history", new HashSet<String>());
        List<String> urls = new ArrayList<>(saved == null ? new HashSet<String>() : saved);
        Collections.sort(urls, (a, b) -> Long.compare(
                prefs.getLong("history_time_" + b, 0L), prefs.getLong("history_time_" + a, 0L)));
        if (urls.isEmpty()) {
            new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("سجل التصفح")
                    .setMessage("لا توجد مواقع في سجل التصفح حتى الآن.")
                    .setPositiveButton("إغلاق", null).show();
            return;
        }

        android.widget.ListView list = new android.widget.ListView(this);
        list.setBackgroundColor(Color.rgb(12, 22, 39));
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.rgb(55, 75, 101)));
        list.setDividerHeight(dp(1));
        list.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, urls) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                String url = urls.get(position);
                String title = prefs.getString("history_title_" + url, "");
                if (title == null || title.trim().isEmpty() || title.trim().equals(url)
                        || title.trim().startsWith("http://") || title.trim().startsWith("https://")) {
                    try {
                        String host = Uri.parse(url).getHost();
                        title = host == null || host.trim().isEmpty() ? "موقع محفوظ" : host;
                    } catch (Exception ignored) { title = "موقع محفوظ"; }
                }
                row.setText(title.trim());
                row.setTextColor(Color.WHITE);
                row.setTextSize(15);
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setBackgroundColor(Color.rgb(23, 35, 54));
                row.setPadding(dp(16), dp(14), dp(16), dp(14));
                return row;
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("سجل التصفح • " + urls.size())
                .setView(list).setNegativeButton("إغلاق", null)
                .setNeutralButton("مسح السجل", (d, w) -> {
                    prefs.edit().putStringSet("browser_history", new HashSet<String>())
                            .putLong("history_cleared_at", System.currentTimeMillis()).apply();
                    Toast.makeText(this, "تم مسح سجل التصفح", Toast.LENGTH_SHORT).show();
                }).create();

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < urls.size()) {
                loadTargetUrl(urls.get(position));
                dialog.dismiss();
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= urls.size()) return true;
            String selectedUrl = urls.get(position);
            String savedTitle = prefs.getString("history_title_" + selectedUrl, "سجل التصفح");
            showBrowserLinkActions(savedTitle, selectedUrl, () -> {
                Set<String> updated = new HashSet<>(prefs.getStringSet("browser_history", new HashSet<String>()));
                updated.remove(selectedUrl);
                prefs.edit().putStringSet("browser_history", updated)
                        .remove("history_title_" + selectedUrl)
                        .remove("history_time_" + selectedUrl)
                        .remove("visit_count_" + selectedUrl).apply();
                dialog.dismiss();
                showBrowserHistoryDialog();
            });
            return true;
        });
        dialog.show();
    }

    private void showQuickSitesDialog() {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("custom_sites", new HashSet<String>());
        List<String> entries = new ArrayList<>();
        if (saved != null) {
            for (String entry : saved) {
                if (entry != null) {
                    int split = entry.indexOf('|');
                    if (split > 0 && split < entry.length() - 1) entries.add(entry);
                }
            }
        }
        Collections.sort(entries, (a, b) -> a.substring(0, a.indexOf('|'))
                .compareToIgnoreCase(b.substring(0, b.indexOf('|'))));
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle("المفضلة والمواقع المحفوظة")
                    .setMessage("لم تحفظ أي مواقع بعد. افتح موقعاً ثم اختر المفضلة من قائمة المتصفح.")
                    .setPositiveButton("حسنًا", null).show();
            return;
        }
        android.widget.ListView list = new android.widget.ListView(this);
        list.setBackgroundColor(Color.rgb(16, 26, 44));
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.rgb(43, 59, 80)));
        list.setDividerHeight(dp(1));
        list.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, entries) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                row.setBackgroundColor(Color.rgb(23, 35, 54));
                row.setPadding(dp(14), dp(10), dp(14), dp(10));
                String entry = entries.get(position);
                int split = entry.indexOf('|');
                TextView title = row.findViewById(android.R.id.text1);
                TextView address = row.findViewById(android.R.id.text2);
                title.setText(entry.substring(0, split));
                String fullUrl = entry.substring(split + 1);
                String host;
                try {
                    host = Uri.parse(fullUrl).getHost();
                } catch (Exception ignored) {
                    host = null;
                }
                address.setText(host == null || host.trim().isEmpty() ? "موقع محفوظ" : host);
                address.setSingleLine(true);
                address.setEllipsize(android.text.TextUtils.TruncateAt.END);
                title.setTextColor(Color.WHITE);
                address.setTextColor(Color.LTGRAY);
                return row;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("المفضلة والمواقع المحفوظة • " + entries.size())
                .setView(list).setNegativeButton("إغلاق", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < entries.size()) {
                loadTargetUrl(entries.get(position).substring(entries.get(position).indexOf('|') + 1));
                dialog.dismiss();
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= entries.size()) return true;
            String entry = entries.get(position);
            int split = entry.indexOf('|');
            String siteName = entry.substring(0, split);
            String fullUrl = entry.substring(split + 1);
            showBrowserLinkActions(siteName, fullUrl, () -> {
                Set<String> updated = new HashSet<>(prefs.getStringSet("custom_sites", new HashSet<String>()));
                updated.remove(entry);
                prefs.edit().putStringSet("custom_sites", updated).apply();
                dialog.dismiss();
                showQuickSitesDialog();
                Toast.makeText(this, "تم حذف الموقع من المفضلة", Toast.LENGTH_SHORT).show();
            });
            return true;
        });
        dialog.show();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // Match Share New's compact text-icon controls, focus feedback and spacing.
    private TextView createBottomButton(String text, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor("⋮".equals(text) ? Color.parseColor("#FFD54A") : Color.WHITE);
        btn.setTextSize(18);
        btn.setGravity(Gravity.CENTER);
        btn.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        btn.setIncludeFontPadding(false);
        btn.setContentDescription("⋮".equals(text) ? "المزيد من أدوات المتصفح"
                : "↻".equals(text) ? "إعادة تحميل الصفحة"
                : "‹".equals(text) ? "رجوع للصفحة السابقة"
                : "⌂".equals(text) ? "العودة للرئيسية"
                : "تقدم للصفحة التالية");
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
        normal.setColor(Color.rgb(27, 28, 35)); // Share New PANEL
        normal.setCornerRadius(dp(13));
        normal.setStroke(dp(1), Color.rgb(38, 40, 51)); // Share New PANEL2
        btn.setBackground(normal);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);
        btn.setOnFocusChangeListener((view, focused) -> {
            android.graphics.drawable.GradientDrawable state = new android.graphics.drawable.GradientDrawable();
            state.setColor(focused ? Color.rgb(38, 40, 51) : Color.rgb(27, 28, 35));
            state.setCornerRadius(dp(13));
            state.setStroke(dp(1), focused ? Color.rgb(0, 176, 255) : Color.rgb(38, 40, 51));
            btn.setBackground(state);
            btn.setTextColor(focused ? Color.rgb(255, 215, 0)
                    : ("⋮".equals(text) ? Color.rgb(255, 215, 0) : Color.WHITE));
            btn.setElevation(focused ? dp(3) : 0);
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(40), 1.0f);
        buttonParams.setMargins(dp(2), dp(2), dp(2), dp(2));
        btn.setLayoutParams(buttonParams);
        btn.setOnClickListener(listener);
        return btn;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void addNewTab(String url) {
        final WebView webView = new WebView(this);
        // YouTube's HTML5/MSE video surface needs a hardware-backed WebView layer.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus(View.FOCUS_DOWN);
        webView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_UP:
                    if (!v.hasFocus()) {
                        v.requestFocus();
                    }
                    break;
            }
            return false;
        });
        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult hit = webView.getHitTestResult();
            if (hit != null && (hit.getType() == WebView.HitTestResult.IMAGE_TYPE
                    || hit.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                String imageUrl = hit.getExtra();
                if (imageUrl != null && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
                    downloadMedia(imageUrl, false, "image/jpeg");
                    return true;
                }
            }
            return false;
        });
        webView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
            boolean audio = mimeType != null && mimeType.startsWith("audio/");
            downloadMedia(downloadUrl, audio, mimeType);
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        // The browser does not need local-file or content-provider access.
        // Keep uploads on the explicit Android file-picker path instead.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Keep web popups in the current tab. The popup guard routes normal
        // HTTP(S) player/server links here and rejects known ad destinations.
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // Keep the established UA for existing sites. QFilm receives the current
        // Android WebView Chromium UA (without the WebView marker) for compatibility.
        settings.setUserAgentString(getConfiguredUserAgent());
        settings.setGeolocationEnabled(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new JsBridge(webView), "AboEzzatBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (pendingGeolocationCallback != null) {
                    pendingGeolocationCallback.invoke(pendingGeolocationOrigin, false, false);
                }
                final String requestedOrigin = origin == null ? "" : origin.trim();
                pendingGeolocationCallback = callback;
                pendingGeolocationOrigin = requestedOrigin;

                // Geolocation is only exposed to secure web origins. App-level Android
                // permission alone must never silently authorize an arbitrary website.
                Uri originUri = Uri.parse(requestedOrigin);
                if (!"https".equalsIgnoreCase(originUri.getScheme())
                        || originUri.getHost() == null || originUri.getHost().trim().isEmpty()) {
                    callback.invoke(requestedOrigin, false, false);
                    pendingGeolocationCallback = null;
                    pendingGeolocationOrigin = "";
                    return;
                }

                new AlertDialog.Builder(BrowserActivity.this)
                        .setTitle("السماح بموقعك؟")
                        .setMessage("الموقع التالي يطلب الوصول إلى موقعك الحالي:\n"
                                + originUri.getHost()
                                + "\n\nلن يتم السماح إلا بعد موافقتك.")
                        .setNegativeButton("رفض", (dialog, which) -> {
                            if (pendingGeolocationCallback == callback) {
                                callback.invoke(requestedOrigin, false, false);
                                pendingGeolocationCallback = null;
                                pendingGeolocationOrigin = "";
                            }
                        })
                        .setPositiveButton("سماح", (dialog, which) -> {
                            if (pendingGeolocationCallback != callback) return;
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                                callback.invoke(requestedOrigin, true, false);
                                pendingGeolocationCallback = null;
                                pendingGeolocationOrigin = "";
                                return;
                            }
                            boolean fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                                    == PackageManager.PERMISSION_GRANTED;
                            boolean coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                                    == PackageManager.PERMISSION_GRANTED;
                            if (fineGranted || coarseGranted) {
                                callback.invoke(requestedOrigin, true, false);
                                pendingGeolocationCallback = null;
                                pendingGeolocationOrigin = "";
                            } else {
                                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION}, GEOLOCATION_PERMISSION_REQUEST_CODE);
                            }
                        })
                        .setOnCancelListener(dialog -> {
                            if (pendingGeolocationCallback == callback) {
                                callback.invoke(requestedOrigin, false, false);
                                pendingGeolocationCallback = null;
                                pendingGeolocationOrigin = "";
                            }
                        })
                        .show();
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (pendingFileChooserCallback != null) {
                    pendingFileChooserCallback.onReceiveValue(null);
                    pendingFileChooserCallback = null;
                }
                pendingFileChooserCallback = filePathCallback;
                try {
                    Intent chooserIntent = fileChooserParams.createIntent();
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE);
                    return true;
                } catch (Exception error) {
                    pendingFileChooserCallback = null;
                    filePathCallback.onReceiveValue(null);
                    Toast.makeText(BrowserActivity.this,
                            "تعذر فتح اختيار الملفات على هذا الجهاز", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showHtml5FullScreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                hideHtml5FullScreen();
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (getCurrentWebView() == view) {
                    if (newProgress < 100) {
                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.setProgress(newProgress);
                    } else {
                        progressBar.setProgress(100);
                        progressBar.setVisibility(View.GONE);
                    }
                }
                injectPopupAndOverlayBlocker(view);
                injectAdKiller(view);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // Never open a second WebView/window: ad popunders are silently
                // discarded. Real player/server links are handled by the page's
                // same-tab navigation path (multiple windows are disabled below).
                return false;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Never allow a page to bypass certificate validation, even after a user prompt.
                handler.cancel();
                Log.w(TAG, "Blocked HTTPS navigation with certificate error: "
                        + (error == null ? "unknown" : error.toString()));
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String loadedUrl, boolean isReload) {
                super.doUpdateVisitedHistory(view, loadedUrl, isReload);
                injectAdKiller(view);
                recordVisitedUrl(view, loadedUrl);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // A crashed/evicted renderer cannot be reused. Remove only the affected tab.
                int removedIndex = tabList.indexOf(view);
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                if (removedIndex >= 0) {
                    tabList.remove(removedIndex);
                    tabTitles.remove(removedIndex);
                }
                navigationStartedAt.remove(view);
                if (activeWebView == view) {
                    activeWebView = null;
                    activePageUrl = "";
                    activePlaybackKey = "";
                    clearCapturedMedia();
                }
                view.destroy();
                if (tabList.isEmpty()) {
                    addNewTab("about:home");
                } else {
                    if (removedIndex >= 0 && removedIndex < currentTabIndex) {
                        currentTabIndex--;
                    } else if (removedIndex == currentTabIndex) {
                        currentTabIndex = Math.min(removedIndex, tabList.size() - 1);
                    }
                    switchToTab(Math.max(0, currentTabIndex));
                }
                Toast.makeText(BrowserActivity.this,
                        "تعطلت صفحة ويب وتمت استعادة المتصفح؛ يمكنك إعادة فتح الصفحة.",
                        Toast.LENGTH_LONG).show();
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.isForMainFrame()) {
                    homePageBridgeActive = false;
                    homeBridgeWebView = null;
                }
                String targetUrl = request.getUrl().toString();
                String lower = targetUrl.toLowerCase(Locale.ROOT);

                // TikTok's mobile web feed emits AppsFlyer OneLink URLs when a user
                // taps/swipes a feed item. Those links try to launch the native app
                // (snssdk://) and can end in ERR_CONNECTION_RESET inside Android WebView.
                // Keep the user in TikTok Web and let its own SPA/player handle the item.
                String currentUrl = view == null ? activePageUrl : view.getUrl();
                if (view != null && isTikTokPageUrl(targetUrl)) {
                    // Apply the Android TikTok UA before the first network request.
                    // Returning false here can let WebView start navigation with the
                    // previous browser-wide (possibly desktop) identity.
                    view.getSettings().setUserAgentString(getTikTokCompatibleUserAgent());
                    view.loadUrl(targetUrl);
                    return true;
                }
                if (isTikTokPageUrl(currentUrl) && isTikTokAppRedirectUrl(targetUrl)) {
                    Log.d(TAG, "Blocked TikTok app deep-link; staying in TikTok Web");
                    return true;
                }

                // Stop known ad/redirect destinations before they replace the page.
                if (isBlockedAdUrl(targetUrl)) return true;

                // Play Store / app schemes and other non-web links require consent.
                if (isPlayStoreRedirect(targetUrl)
                        || (!lower.startsWith("http://") && !lower.startsWith("https://"))) {
                    showRedirectConsent(targetUrl, false);
                    return true;
                }
                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String targetUrl) {
                homePageBridgeActive = false;
                homeBridgeWebView = null;
                String lower = targetUrl == null ? "" : targetUrl.toLowerCase(Locale.ROOT);
                String currentUrl = view == null ? activePageUrl : view.getUrl();
                if (view != null && isTikTokPageUrl(targetUrl)) {
                    view.getSettings().setUserAgentString(getTikTokCompatibleUserAgent());
                    view.loadUrl(targetUrl);
                    return true;
                }
                if (isTikTokPageUrl(currentUrl) && isTikTokAppRedirectUrl(targetUrl)) {
                    Log.d(TAG, "Blocked TikTok app deep-link; staying in TikTok Web");
                    return true;
                }
                if (isBlockedAdUrl(targetUrl)) return true;
                if (isPlayStoreRedirect(targetUrl)
                        || (!lower.startsWith("http://") && !lower.startsWith("https://"))) {
                    showRedirectConsent(targetUrl, false);
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();
                String lowerUrl = reqUrl.toLowerCase();

                String requestPageUrl = view == activeWebView ? activePageUrl : "";
                if (isBlockedAdUrl(reqUrl)) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }

                // Threads and Instagram are handled only by their existing dedicated
                // sniffers. Never let the generic network capture path touch their requests.
                boolean dedicatedSocialPage = isThreadsOrInstagramPage(requestPageUrl);

                // TikTok LIVE may play in a cross-origin frame, where JavaScript cannot
                // establish activePlaybackKey. Permit only strong LIVE stream endpoints
                // while the current page is a TikTok LIVE page.
                boolean tikTokLiveNetworkCapture = view == activeWebView
                        && isTikTokLivePage(requestPageUrl) && isGenericAutoSniffActive();

                // TikTok VOD sometimes loads its signed MP4 CDN request from a player
                // frame before the DOM video tracker can arm. Capture only a strong
                // /video/tos/ media URL belonging to the current TikTok page; this
                // does not arm on thumbnails, profile images, or unrelated requests.
                boolean tikTokVodDirectMedia = view == activeWebView
                        && isTikTokPageUrl(requestPageUrl) && !isTikTokLivePage(requestPageUrl)
                        && "GET".equalsIgnoreCase(request.getMethod())
                        && isTikTokCdnVideoUrl(reqUrl)
                        && classifyCapturedMedia(reqUrl) == 1;
                if (tikTokVodDirectMedia && activePlaybackKey.isEmpty()) {
                    synchronized (BrowserActivity.this) {
                        if (activePlaybackKey.isEmpty()) {
                            activePlaybackKey = "tiktok-vod:" + requestPageUrl;
                        }
                    }
                }

                // Ignore every page/list/thumbnail request until a specific
                // player has been activated, except for the narrowly-scoped TikTok LIVE
                // fallback and a verified direct TikTok VOD media request.
                boolean dramaLivePlayerActive = isDramaLivePage(requestPageUrl)
                        && activePlaybackKey.startsWith("dramalive-frame:")
                        && System.currentTimeMillis() - activePlayerFrameActivatedAtMs < 10 * 60 * 1000L;
                if (!dedicatedSocialPage && view == activeWebView
                        && (!activePlaybackKey.isEmpty() || tikTokLiveNetworkCapture || tikTokVodDirectMedia)) {
                    if (tikTokLiveNetworkCapture && activePlaybackKey.isEmpty()
                            && isTikTokLiveStreamCandidate(reqUrl)) {
                        synchronized (BrowserActivity.this) {
                            if (activePlaybackKey.isEmpty()) {
                                activePlaybackKey = "tiktok-live:" + requestPageUrl;
                            }
                        }
                    }
                    boolean isJunk = lowerUrl.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|svg|ts|m4s|js|css)(?:[?#].*)?$")
                            || lowerUrl.contains("preview") || lowerUrl.contains("thumbnail")
                            || lowerUrl.contains("google_ads") || lowerUrl.contains("ad-interrupting");
                    if (!isJunk) {
                        int type = classifyCapturedMedia(reqUrl);
                        if (type != 0) {
                            String cleanMedia = reqUrl.replaceAll("&bytestart=\\d+", "")
                                    .replaceAll("&byteend=\\d+", "");
                            String format = type == 2 ? "🎵 مسار صوت"
                                    : lowerUrl.contains("pull-") ? "📡 بث تيك توك مباشر"
                                    : lowerUrl.contains(".m3u8") ? "📡 بث مباشر - m3u8"
                                    : lowerUrl.contains(".mpd") ? "🎬 فيديو DASH"
                                    : "🎬 فيديو";
                            String title = !lastDiscoveredTitle.isEmpty()
                                    ? lastDiscoveredTitle : "فيديو قيد التشغيل";
                            Map<String, String> playbackHeaders =
                                    capturePlaybackRequestHeaders(request, reqUrl);
                            addCapturedMedia(cleanMedia, title, format, lastDiscoveredPoster, type,
                                    playbackHeaders);

                            // Only a share-sheet request auto-casts. Manual browser visits
                            // still expose the detected stream in the capture list.
                            if (type == 1 && tikTokLiveNetworkCapture && tikTokLiveAutoCastPending
                                    && isTikTokLiveStreamCandidate(cleanMedia)) {
                                boolean shouldAutoCast = false;
                                synchronized (BrowserActivity.this) {
                                    if (tikTokLiveAutoCastPending) {
                                        tikTokLiveAutoCastPending = false;
                                        shouldAutoCast = true;
                                    }
                                }
                                if (shouldAutoCast) {
                                    final String liveUrl = cleanMedia;
                                    final Map<String, String> liveHeaders = playbackHeaders;
                                    runOnUiThread(() -> {
                                        Toast.makeText(BrowserActivity.this,
                                                "تم التقاط بث TikTok LIVE، جاري الإرسال للشاشة…",
                                                Toast.LENGTH_SHORT).show();
                                        castToTv(liveUrl, null, liveHeaders);
                                    });
                                }
                            }
                        } else if (lowerUrl.matches(".*\\.mp4(?:[?#].*)?$")) {
                            probeAndAddMp4(reqUrl, lastDiscoveredTitle, lastDiscoveredPoster);
                        } else if (dramaLivePlayerActive && "GET".equalsIgnoreCase(request.getMethod())
                                && (reqUrl.equals(activePlayerFrameUrl)
                                || lowerUrl.matches(".*(\\.json(?:[?#].*)?|/api/|/get\\.php|/player/|/embed/|/stream/|manifest|playlist|source=|file=).*"))) {
                            Map<String, String> headers = request.getRequestHeaders() == null
                                    ? null : new HashMap<>(request.getRequestHeaders());
                            probeDramaLivePlayerDocument(reqUrl, activePlaybackKey, lastDiscoveredTitle,
                                    lastDiscoveredPoster, headers);
                        } else if (isGenericAutoSniffActive()) {
                            // Generic fallback for embedded players on any movie site:
                            // cross-origin iframe requests are invisible to page JS, so use
                            // WebView's request metadata to identify likely stream endpoints.
                            try {
                                Map<String, String> headers = request.getRequestHeaders();
                                String accept = "";
                                String range = "";
                                if (headers != null) {
                                    for (Map.Entry<String, String> header : headers.entrySet()) {
                                        if (header.getKey() == null) continue;
                                        if ("accept".equalsIgnoreCase(header.getKey())) accept = header.getValue() == null ? "" : header.getValue();
                                        if ("range".equalsIgnoreCase(header.getKey())) range = header.getValue() == null ? "" : header.getValue();
                                    }
                                }
                                String requestHints = lowerUrl;
                                boolean mediaAccept = accept.toLowerCase(Locale.ROOT).contains("video/")
                                        || accept.toLowerCase(Locale.ROOT).contains("application/vnd.apple.mpegurl")
                                        || accept.toLowerCase(Locale.ROOT).contains("application/x-mpegurl")
                                        || accept.toLowerCase(Locale.ROOT).contains("application/dash+xml");
                                boolean mediaRange = !range.isEmpty();
                                boolean playerEndpoint = requestHints.contains("manifest")
                                        || requestHints.contains("playlist") || requestHints.contains("master")
                                        || requestHints.contains("videoplayback") || requestHints.contains("/stream/")
                                        || requestHints.contains("stream_url=") || requestHints.contains("video_url=")
                                        || requestHints.contains("file=") || requestHints.contains("source=")
                                        || requestHints.contains("/embed/") || requestHints.contains("/player/");
                                if ("GET".equalsIgnoreCase(request.getMethod())
                                        && (mediaAccept || mediaRange || playerEndpoint)) {
                                    probeUnknownVideoByContentType(reqUrl, lastDiscoveredTitle, lastDiscoveredPoster);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String loadedUrl, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, loadedUrl, favicon);
                if (view != homeBridgeWebView || loadedUrl == null
                        || !loadedUrl.startsWith(INTERNAL_HOME_BASE_URL)) {
                    homePageBridgeActive = false;
                    homeBridgeWebView = null;
                }
                navigationStartedAt.put(view, System.currentTimeMillis());
                if (view == activeWebView) {
                    activePageUrl = loadedUrl == null ? "" : loadedUrl;
                    String nextYouTubePage = canonicalYouTubeWatchUrl(loadedUrl);
                    activeYouTubeWatchUrl = nextYouTubePage == null ? "" : nextYouTubePage;
                    // A real page load (including reloading the same video) starts a
                    // fresh capture generation. Start extraction only after the old
                    // visible list is cleared below.
                    activePlaybackKey = "";
                    synchronized (BrowserActivity.this) {
                        youtubeExtractionInProgressFor = "";
                        youtubeExtractedFor = "";
                        youtubeCastPendingFor = "";
                    }
                }
                if (loadedUrl != null && isTikTokPageUrl(loadedUrl)) {
                    view.getSettings().setUserAgentString(getTikTokCompatibleUserAgent());
                } else if (loadedUrl != null && isYouTubePageUrl(loadedUrl)) {
                    // Use the actual system WebView Chromium version on YouTube;
                    // the old hard-coded Chrome 126 UA can trigger unsupported-player behavior.
                    view.getSettings().setUserAgentString(getYouTubeCompatibleUserAgent());
                } else if (loadedUrl != null && isQFilmUrl(loadedUrl)) {
                    // QFilm server buttons commonly use target=_blank/window.open for
                    // their player endpoints. Keep those destinations in this WebView.
                    view.getSettings().setSupportMultipleWindows(false);
                    view.getSettings().setUserAgentString(getQFilmCompatibleUserAgent());
                } else if (loadedUrl != null && loadedUrl.startsWith("http")) {
                    // Keep target=_blank and window.open destinations in this tab.
                    view.getSettings().setSupportMultipleWindows(false);
                    view.getSettings().setUserAgentString(getConfiguredUserAgent());
                }
                if (getCurrentWebView() == view && loadedUrl != null
                        && loadedUrl.startsWith("http")) {
                    activePlaybackKey = "";
                    activePlayerFrameUrl = "";
                    activePlayerFrameActivatedAtMs = 0L;
                    dramaLiveProbeRequests.clear();
                    manualSniffUntilMs = 0L;
                    genericAutoSniffUntilMs = shouldAutoCaptureGenericPage(loadedUrl)
                            ? System.currentTimeMillis() + 10 * 60 * 1000L : 0L;
                    clearCapturedMedia();
                    if (!activeYouTubeWatchUrl.isEmpty()) {
                        extractYouTubeForPage(activeYouTubeWatchUrl, false);
                    }
                }
            }

            @Override
            public void onPageFinished(final WebView view, String loadedUrl) {
                super.onPageFinished(view, loadedUrl);
                if (getCurrentWebView() == view) {
                    // WebView may finish an older navigation after YouTube has already
                    // moved to another video inside its SPA. Snapshot the current URL,
                    // not the stale callback argument.
                    String currentUrl = view.getUrl();
                    activePageUrl = currentUrl == null ? "" : currentUrl;
                    String currentYouTubePage = canonicalYouTubeWatchUrl(currentUrl);
                    if (currentYouTubePage != null) {
                        activeYouTubeWatchUrl = currentYouTubePage;
                    } else if (!isYouTubePageUrl(currentUrl)) {
                        activeYouTubeWatchUrl = "";
                    }
                    etUrl.setText(currentUrl == null || currentUrl.equals("about:home") ? "" : currentUrl);
                    tabTitles.set(currentTabIndex, view.getTitle() != null ? view.getTitle() : "علامة تبويب");
                }
                injectPopupAndOverlayBlocker(view);
                injectAdKiller(view);
                injectTikTokLiveSniffer(view);
                if (isTikTokLivePage(view.getUrl())) injectTikTokLiveAutoplay(view);
                injectYouTubePageUrlTracker(view);
                injectTikTokPageUrlTracker(view);
                // YouTube extraction starts only after the player reports actual playback
                // through JsBridge; keep page URL snapshots on the UI thread.
                if (isGenericAutoSniffActive() && shouldAutoCaptureGenericPage(loadedUrl)) {
                    injectGenericAutoCapture(view);
                }
            }
        });

        tabList.add(webView);
        tabTitles.add("علامة تبويب");
        webContainer.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        switchToTab(tabList.size() - 1);
        if (url.equals("about:home")) {
            loadHomePage();
        } else {
            loadTargetUrl(url);
        }
    }

    // Detect TikTok's app-install/deep-link tracking redirects without
    // affecting ordinary external links opened from other websites.
    private boolean isTikTokAppRedirectUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return host.equals("onelink.me") || host.endsWith(".onelink.me")
                    || scheme.startsWith("snssdk") || scheme.equals("tiktok")
                    || (scheme.equals("intent") && value.toLowerCase(Locale.ROOT).contains("snssdk"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isPlayStoreRedirect(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("market://")
                || lower.startsWith("https://play.google.com/store")
                || lower.startsWith("http://play.google.com/store")
                || lower.startsWith("https://play.google.com/apps")
                || (lower.startsWith("intent://") && lower.contains("play.google.com"));
    }

    private void showRedirectConsent(String requestedUrl, boolean popupRequest) {
        if (webContainer == null) return;
        String destination = requestedUrl == null ? "" : requestedUrl.trim();
        pendingRedirectUrl = destination;

        String siteLabel = getRedirectSiteName(destination);
        if (popupRequest && (destination.isEmpty() || destination.equals("about:blank"))) {
            WebView current = getCurrentWebView();
            siteLabel = current == null ? "الموقع الحالي" : getRedirectSiteName(current.getUrl());
        }

        if (redirectBar == null) {
            redirectBar = new LinearLayout(this);
            redirectBar.setOrientation(LinearLayout.HORIZONTAL);
            redirectBar.setGravity(Gravity.CENTER_VERTICAL);
            redirectBar.setPadding(dp(10), dp(5), dp(7), dp(5));
            redirectBar.setBackgroundColor(Color.parseColor("#16284A"));

            redirectSiteLabel = new TextView(this);
            redirectSiteLabel.setTextColor(Color.WHITE);
            redirectSiteLabel.setTextSize(12);
            redirectSiteLabel.setSingleLine(true);
            redirectBar.addView(redirectSiteLabel, new LinearLayout.LayoutParams(0, dp(38), 1f));

            redirectOpenButton = new Button(this);
            redirectOpenButton.setText("فتح");
            redirectOpenButton.setAllCaps(false);
            redirectOpenButton.setTextColor(Color.WHITE);
            redirectOpenButton.setTextSize(13);
            redirectOpenButton.setBackgroundColor(Color.parseColor("#168A55"));
            redirectBar.addView(redirectOpenButton, new LinearLayout.LayoutParams(dp(64), dp(38)));

            redirectOpenButton.setOnClickListener(v -> {
                String urlToOpen = pendingRedirectUrl;
                hideRedirectConsent();
                openRedirectAfterConsent(urlToOpen);
            });

            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            barParams.setMargins(dp(8), 0, dp(8), dp(8));
            webContainer.addView(redirectBar, barParams);
        }

        redirectSiteLabel.setText("الموقع: " + siteLabel);
        redirectBar.setVisibility(View.VISIBLE);
        redirectBar.bringToFront();
        if (hideRedirectBarRunnable != null) redirectBar.removeCallbacks(hideRedirectBarRunnable);
        hideRedirectBarRunnable = this::hideRedirectConsent;
        redirectBar.postDelayed(hideRedirectBarRunnable, 1000);
    }

    private String getRedirectSiteName(String url) {
        if (url == null || url.trim().isEmpty()) return "الموقع";
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null && !host.trim().isEmpty()) return host.replaceFirst("^www\\.", "");
            String scheme = uri.getScheme();
            if (scheme != null) return scheme;
        } catch (Exception ignored) {}
        return "الموقع";
    }

    private void hideRedirectConsent() {
        if (redirectBar != null) redirectBar.setVisibility(View.GONE);
        pendingRedirectUrl = "";
        hideRedirectBarRunnable = null;
    }

    private void openRedirectAfterConsent(String url) {
        if (url == null || url.trim().isEmpty()) return;
        String lower = url.toLowerCase();
        try {
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                if (isPlayStoreRedirect(url)) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
                    else {
                        WebView current = getCurrentWebView();
                        if (current != null) current.loadUrl(url);
                    }
                } else {
                    WebView current = getCurrentWebView();
                    if (current != null) current.loadUrl(url);
                }
                return;
            }

            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            String fallback = intent.getStringExtra("browser_fallback_url");
            if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
            else if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                WebView current = getCurrentWebView();
                if (current != null) current.loadUrl(fallback);
            } else {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                if (viewIntent.resolveActivity(getPackageManager()) != null) startActivity(viewIntent);
            }
        } catch (Exception ignored) {
            Toast.makeText(this, "تعذر فتح الرابط المطلوب", Toast.LENGTH_SHORT).show();
        }
    }

    // TikTok LIVE only: wait for the user to start playback, then watch for the
    // actual live pull/HLS resource. Ordinary video sniffing remains untouched.
    private void injectTikTokLiveSniffer(WebView view) {
        // The shared active-player tracker below also handles TikTok LIVE URLs.
        injectAdKiller(view);
    }

    // TikTok LIVE may load its page while its HTML5 player remains paused.
    // Try browser-permitted muted autoplay and tap only an explicit Play control.
    private void injectTikTokLiveAutoplay(WebView view) {
        if (view == null) return;
        String js = "(function(){try{" +
                "if(window.__aboTikTokLiveAutoplayV1)return;window.__aboTikTokLiveAutoplayV1=true;" +
                "var attempts=0;function start(){try{var vids=document.querySelectorAll('video');" +
                "for(var i=0;i<vids.length;i++){var v=vids[i];try{v.autoplay=true;v.muted=true;v.playsInline=true;v.setAttribute('playsinline','');" +
                "if(v.paused&&!v.ended&&v.readyState>=1){var p=v.play();if(p&&p.catch)p.catch(function(){});}}catch(e){}}" +
                "var bs=document.querySelectorAll('[data-e2e*=\\\"play\\\"],button[aria-label],[role=button][aria-label]');" +
                "for(var j=0;j<bs.length;j++){var b=bs[j],label=((b.getAttribute('aria-label')||'')+' '+(b.innerText||b.textContent||'')).trim();" +
                "if(/^(play|play video|تشغيل|استئناف)(\\\\s|$)/i.test(label)&&!b.disabled){try{b.click();}catch(e){}break;}}" +
                "}catch(e){}}start();var timer=setInterval(function(){start();if(++attempts>=120)clearInterval(timer);},500);" +
                "}catch(e){}})();";
        view.evaluateJavascript(js, null);
    }

    private void injectAdKiller(WebView view) {
        String js = "(function(){try{" +
                "if(!window.__aboYoutubeAdSpeedSkipV4){" +
                "try{if(window.__aboYoutubeSkipLoop)clearInterval(window.__aboYoutubeSkipLoop);}catch(e){}" +
                "window.__aboYoutubeAdSpeedSkipFn=function(){try{" +
                "var h=(location.hostname||'').toLowerCase();var isYT=/(^|\\.)youtube\\.com$/.test(h)||h==='youtu.be'||/(^|\\.)youtube-nocookie\\.com$/.test(h);if(!isYT)return;" +
                "var p=document.getElementById('movie_player');var pc=p?String(p.className||''):'';" +
                "var adActive=/(^|\\s)(ad-showing|ad-interrupting)(\\s|$)/i.test(pc)||!!document.querySelector('#player.ad-showing,#player.ad-interrupting,.ad-showing,.ad-interrupting');" +
                "if(!adActive){var hints=document.querySelectorAll('.ytp-ad-player-overlay,.ytm-ad-player-overlay,.ytp-ad-preview-container,.ytp-ad-skip-button-container,.ytp-ad-text');for(var hi=0;hi<hints.length;hi++){var he=hints[hi],hs=getComputedStyle(he);if(he.getClientRects().length&&hs.display!=='none'&&hs.visibility!=='hidden'&&Number(hs.opacity||1)>0.05){adActive=true;break;}}}" +
                "var vids=document.querySelectorAll('#movie_player video,video.html5-main-video,ytm-player video');" +
                "for(var vi=0;vi<vids.length;vi++){var v=vids[vi];if(adActive){if(typeof v.__aboSavedPlaybackRate==='undefined')v.__aboSavedPlaybackRate=(v.playbackRate&&v.playbackRate>0)?v.playbackRate:1;if(typeof v.__aboSavedMuted==='undefined')v.__aboSavedMuted=!!v.muted;if(typeof v.__aboSavedVolume==='undefined')v.__aboSavedVolume=(typeof v.volume==='number')?v.volume:1;try{v.muted=true;v.volume=0;v.playbackRate=16;v.defaultPlaybackRate=16;}catch(e){}}" +
                "else if(typeof v.__aboSavedPlaybackRate!=='undefined'||typeof v.__aboSavedMuted!=='undefined'){try{if(typeof v.__aboSavedPlaybackRate!=='undefined'){v.playbackRate=v.__aboSavedPlaybackRate;v.defaultPlaybackRate=v.__aboSavedPlaybackRate;}if(typeof v.__aboSavedMuted!=='undefined')v.muted=v.__aboSavedMuted;if(typeof v.__aboSavedVolume!=='undefined')v.volume=v.__aboSavedVolume;}catch(e){}delete v.__aboSavedPlaybackRate;delete v.__aboSavedMuted;delete v.__aboSavedVolume;}}" +
                "if(adActive&&p&&typeof p.setPlaybackRate==='function'){try{if(typeof p.__aboSavedPlayerRate==='undefined'){var rr=p.getPlaybackRate();p.__aboSavedPlayerRate=(rr&&rr>0)?rr:1;}p.setPlaybackRate(16);}catch(e){}}" +
                "else if(!adActive&&p&&typeof p.__aboSavedPlayerRate!=='undefined'){try{p.setPlaybackRate(p.__aboSavedPlayerRate);}catch(e){}delete p.__aboSavedPlayerRate;}" +
                "var nodes=document.querySelectorAll('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button,.videoAdUiSkipButton,.ytm-skip-ad-button,.ytm-ad-skip-button,button[aria-label],[role=button][aria-label]');" +
                "for(var i=0;i<nodes.length;i++){var b=nodes[i];if(!b||b.disabled||b.getAttribute('aria-disabled')==='true')continue;var cl=String(b.className||'');var label=(b.getAttribute('aria-label')||'')+' '+(b.innerText||b.textContent||'');var known=/ytp-ad-skip-button|ytp-skip-ad-button|videoAdUiSkipButton|ytm-skip-ad-button|ytm-ad-skip-button/.test(cl)||!!b.closest('.ytp-ad-skip-button-container');if(!known&&!/skip\\s*(?:ad|ads)|تخطي\\s*(?:الإعلان|الإعلانات)/i.test(label))continue;var st=getComputedStyle(b);if(!b.getClientRects().length||st.visibility==='hidden'||st.display==='none'||Number(st.opacity||1)<=0.05)continue;try{var target=b.matches('button')?b:(b.querySelector('button')||b);target.click();}catch(e){}break;}" +
                "}catch(e){}};" +
                "window.__aboYoutubeAdSpeedSkipV4=true;window.__aboYoutubeSkipLoop=setInterval(window.__aboYoutubeAdSpeedSkipFn,100);" +
                "if(document.documentElement&&!window.__aboYoutubeAdSpeedSkipObserver){window.__aboYoutubeAdSpeedSkipObserver=new MutationObserver(function(){window.__aboYoutubeAdSpeedSkipFn();});window.__aboYoutubeAdSpeedSkipObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','aria-label','disabled']});}window.__aboYoutubeAdSpeedSkipFn();}" +
                "if(window.__aboActiveCaptureInstalled)return;window.__aboActiveCaptureInstalled=true;" +
                "var armed=false,activeKey='',activeSince=0,seenResources={};" +
                // Keep extended preload history only for Threads; preserve Instagram behavior.
                "var isThreadsHost=/(?:threads\\.net|threads\\.com)$/i.test(location.hostname||'');" +
                "var isInstagramHost=/(?:^|\\.)instagram\\.com$/i.test(location.hostname||'');" +
                "function instagramInfo(u){try{var q=new URL(u).searchParams.get('efg');if(!q)return null;q=q.replace(/-/g,'+').replace(/_/g,'/');while(q.length%4)q+='=';var o=JSON.parse(atob(q));return {id:String(o.xpv_asset_id||o.video_id||''),tag:String(o.vencode_tag||'').toLowerCase()};}catch(e){return null;}}" +
                "var isYouTubeHost=/(?:^|\\.)youtube\\.com$/i.test(location.hostname||'')||/(?:^|\\.)youtube-nocookie\\.com$/i.test(location.hostname||'')||/^youtu\\.be$/i.test(location.hostname||'');" +
                "var isTikTokHost=/(?:^|\\.)tiktok\\.com$/i.test(location.hostname||'');" +
                "window.__aboTikTokRouteReset=function(){try{if(isTikTokHost){activeKey='';activeSince=performance.now();seenResources={};armed=true;if(performance.clearResourceTimings)performance.clearResourceTimings();}}catch(e){}};" +
                "var isTikTokLivePage=/(?:^|\\.)tiktok\\.com$/i.test(location.hostname||'')&&/\\/live(?:[/?#]|$)/i.test(location.pathname||'');" +
                "if(isThreadsHost||isTikTokHost||isTikTokLivePage){try{if(performance.setResourceTimingBufferSize)performance.setResourceTimingBufferSize(1500);}catch(e){}}" +
                "if(!isYouTubeHost){var style=document.getElementById('abo-ezzat-adblock');if(!style){style=document.createElement('style');style.id='abo-ezzat-adblock';" +
                "style.innerHTML='.ytp-ad-overlay-container,.ytp-ad-message-container,ytd-promoted-sparkles-web-renderer,ytd-banner-promo-renderer,ytd-ad-slot-renderer,.ad-container,.ad-div,ytd-in-feed-ad-layout-renderer,ytm-promoted-sparkles-web-renderer,.ytp-ad-action-interstitial,[id^=\\\"google_ads_\\\"],iframe[src*=\\\"doubleclick\\\"]{display:none!important;width:0!important;height:0!important}';if(document.head)document.head.appendChild(style);}}" +
                // YouTube: click only the native Skip Ad control once it is visible and enabled.
                // Do not seek/mute/alter the player or interfere with non-skippable ads.
                "if(!window.AboEzzatKillerLoop){window.AboEzzatKillerLoop=setInterval(function(){try{if(!isYouTubeHost){" +
                "var bs=document.querySelectorAll('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.videoAdUiSkipButton,button[aria-label],button');" +
                "for(var si=0;si<bs.length;si++){var b=bs[si],cl=(b.className&&String(b.className))||'',al=(b.getAttribute('aria-label')||''),tx=(b.innerText||b.textContent||'');" +
                "var known=/ytp-ad-skip-button|ytp-skip-ad-button|videoAdUiSkipButton/.test(cl),named=/(skip\\s*ads?|تخطي\\s*(الإعلان|الإعلانات))/i.test(al+' '+tx);" +
                "var st=getComputedStyle(b);if((known||named)&&!b.disabled&&b.getClientRects().length&&st.visibility!=='hidden'&&st.display!=='none'&&Number(st.opacity||1)>0.05){var target=b.matches('button')?b:(b.querySelector('button')||b);target.click();break;}}" +
                "var av=document.querySelectorAll('video');for(var ai=0;ai<av.length;ai++){var x=av[ai];if(document.querySelector('.ad-interrupting,.ad-showing')||x.classList.contains('ad-interrupting')){x.muted=true;if(!isNaN(x.duration)&&x.duration>0)x.currentTime=x.duration+1;}}}" +
                "}catch(e){}},350);}" +
                "function metaValue(prop,name){try{var ms=document.getElementsByTagName('meta');for(var i=0;i<ms.length;i++){var m=ms[i];if((prop&&m.getAttribute('property')===prop)||(name&&m.getAttribute('name')===name)){var c=m.getAttribute('content')||'';if(c.trim())return c.trim();}}}catch(e){}return '';}" +
                "function titleFor(v){try{if(isTikTokLivePage){var t=metaValue('og:title','twitter:title');if(!t){var ns=[].slice.call(document.querySelectorAll('[data-e2e]'));for(var ni=0;ni<ns.length;ni++){var de=(ns[ni].getAttribute('data-e2e')||'').toLowerCase();if(de.indexOf('live-room')>=0||de.indexOf('live-title')>=0||de.indexOf('live-user')>=0){t=(ns[ni].innerText||ns[ni].textContent||'').trim();if(t)break;}}}if(!t)t=document.title||'';t=t.replace(/\\s*[|—-]\\s*TikTok.*$/i,'').replace(/^TikTok\\s*(LIVE)?\\s*[:—-]?\\s*/i,'').trim();if(t&&!/^live$|^tiktok$/i.test(t))return t;return '';}" +
                "var a=v.closest('article')||v.parentElement,owner='',caption='';if(a){var links=[].slice.call(a.querySelectorAll('a[href]'));for(var i=0;i<links.length;i++){var h=links[i].getAttribute('href')||'';var m=h.match(/\\/@([A-Za-z0-9._-]{2,})/);if(m){owner='@'+m[1];break;}var al=links[i].getAttribute('aria-label')||links[i].innerText||'';if(!owner&&/^@?[A-Za-z0-9._-]{2,}$/.test(al.trim()))owner=al.trim().charAt(0)==='@'?al.trim():'@'+al.trim();}var c=a.querySelector('[data-e2e*=\\\"video-desc\\\"],[data-testid*=\\\"text\\\"],[dir=\\\"auto\\\"]');caption=c?(c.innerText||c.textContent||'').trim():(a.innerText||'').trim();caption=caption.replace(/\\s+/g,' ').substring(0,95);}var t=(owner?owner+' — ':'')+caption;return t||document.title||'فيديو قيد التشغيل';}catch(e){}return isTikTokLivePage?'':(document.title||'فيديو قيد التشغيل');}" +
                "function posterFor(v){try{if(v.poster&&/^https?:/i.test(v.poster))return v.poster;if(isTikTokLivePage){var p=metaValue('og:image','twitter:image');if(/^https?:/i.test(p))return p;var all=[].slice.call(document.getElementsByTagName('img'));all.sort(function(x,y){return (y.naturalWidth*y.naturalHeight)-(x.naturalWidth*x.naturalHeight);});for(var k=0;k<all.length;k++){var im=all[k],u=im.currentSrc||im.src||im.getAttribute('data-src')||'';if(/^https?:/i.test(u)&&im.naturalWidth>=120&&im.naturalHeight>=90&&!/(avatar|profile|emoji|icon)/i.test(u))return u;}return '';}var a=v.closest('article')||v.parentElement;if(a){var imgs=[].slice.call(a.querySelectorAll('img[src],img[data-src]'));imgs.sort(function(x,y){var r=x.getBoundingClientRect(),q=y.getBoundingClientRect();return (q.width*q.height)-(r.width*r.height);});for(var i=0;i<imgs.length;i++){var u=imgs[i].currentSrc||imgs[i].src||imgs[i].getAttribute('data-src')||'';if(/^https?:/i.test(u)&&!/(avatar|profile|emoji|icon)/i.test(u))return u;}}}catch(e){}return '';}" +
                "function area(v){try{var r=v.getBoundingClientRect();var w=Math.max(0,Math.min(innerWidth,r.right)-Math.max(0,r.left));" +
                "var h=Math.max(0,Math.min(innerHeight,r.bottom)-Math.max(0,r.top));return w*h;}catch(e){return 0;}}" +
                "function visible(v){return area(v)>Math.max(12000,(innerWidth*innerHeight)*0.025);}" +
                "function bridgeStart(v,key){var t=titleFor(v),p=posterFor(v);" +
                "if(isYouTubeHost){try{var yu=new URL(location.href),yi=yu.searchParams.get('v')||'';if(!yi){var yp=yu.pathname.match(/\\/(?:live|shorts|embed|v)\\/([A-Za-z0-9_-]{11})/);if(yp)yi=yp[1];if(yu.hostname==='youtu.be')yi=yu.pathname.split('/').filter(Boolean)[0]||'';}if(/^[A-Za-z0-9_-]{11}$/.test(yi))key='youtube:'+yi;}catch(e){}}" +
                "if(key!==activeKey){activeKey=key;activeSince=performance.now()-((isThreadsHost||isInstagramHost)?4000:1800);if(!isYouTubeHost)seenResources={};window.AboEzzatBridge.onActiveVideoChanged(key,t,p);}else if(isTikTokLivePage){window.AboEzzatBridge.onActiveVideoChanged(key,t,p);}" +
                "var src=v.currentSrc||v.src||'';if(!isInstagramHost&&/^https?:/i.test(src))window.AboEzzatBridge.onActiveVideoTrack(src,t,p);" +
                "}" +
                "function scanResources(v){try{var entries=performance.getEntriesByType('resource'),now=performance.now();" +
                "var videoTracks=[],bestAudio=null,audioTracks=[];" +
                "var activeInstagramInfo=isInstagramHost?instagramInfo(v.currentSrc||v.src||''):null;" +
                "for(var i=0;i<entries.length;i++){var e=entries[i],u=e.name||'',l=u.toLowerCase();if(!/^https?:/i.test(u))continue;" +
                "if(l.indexOf('doubleclick.net')!==-1||l.indexOf('googlesyndication.com')!==-1||l.indexOf('googleadservices.com')!==-1||l.indexOf('adservice.google.com')!==-1||l.indexOf('/ddm/trackimp/')!==-1||l.indexOf('/ddm/trackclk/')!==-1)continue;" +
                "if(l.indexOf('googlevideo.com')!==-1&&l.indexOf('/videoplayback')!==-1)continue;" +
                // For Threads, only consider resources requested during the currently playing visible post.
                "if((isThreadsHost||isTikTokHost||isInstagramHost)&&(e.startTime<activeSince||e.startTime>performance.now()+100))continue;" +
                "if(isThreadsHost&&seenResources[u])continue;" +
                // Threads may preload DASH audio early; Instagram and other sites keep the original 10s window.
                "if(!(isThreadsHost||isTikTokLivePage)&&now-e.startTime>10000)continue;" +
                "if(/\\.(jpg|jpeg|png|webp|gif|bmp|svg|ts|m4s|js|css)(?:[?#]|$)/i.test(l))continue;" +
                "var ig= isInstagramHost?instagramInfo(u):null;if(isInstagramHost&&(l.indexOf('/video/unified_cvc/')!==-1||!ig||!ig.id||!ig.tag))continue;" +
                "var audio=/mime(?:_|-)?type=audio|mime=audio|type=audio|content[_-]?type=audio|dash_ln_heaac|audio_dash|\\.(aac|m4a|mp3|opus|ogg)(?:[?#]|$)/i.test(l)||(!!ig&&ig.tag.indexOf('_audio')>=0);" +
                "var mp4Candidate=/\\.mp4(?:[?#]|$)/i.test(l);" +
                "var video=/mime(?:_|-)?type=video|mime=video|type=video|content[_-]?type=video|video\\/tos|pull-|videoplayback|\\.(m4v|m3u8|mpd|webm|flv|mov)(?:[?#]|$)/i.test(l)||(!!ig&&mp4Candidate&&!!ig.id&&ig.tag.indexOf('_audio')<0);" +
                "if(!audio&&!video&&!mp4Candidate)continue;" +
                "var score=(e.initiatorType==='video'||e.initiatorType==='media'?40:0)+(now-e.startTime<8000?20:0)+" +
                "(/\\.(m3u8|mpd)(?:[?#]|$)/i.test(l)?25:0);" +
                "var item={url:u,score:score,time:e.startTime,assetId:ig?ig.id:''};if(isThreadsHost)seenResources[u]=true;" +
                "if(audio){if(isInstagramHost){if(item.assetId)audioTracks.push(item);}else if(!bestAudio||item.score>bestAudio.score||(item.score===bestAudio.score&&item.time>bestAudio.time))bestAudio=item;}" +
                "else if(video&&!videoTracks.some(function(x){return x.url===u;})){videoTracks.push(item);}" +
                "else if(mp4Candidate&&!isInstagramHost)window.AboEzzatBridge.onCapturedCandidateTrack(u,titleFor(v),posterFor(v));" +
                "}" +
                "if(isInstagramHost){var chosenId=activeInstagramInfo&&activeInstagramInfo.id?activeInstagramInfo.id:'';" +
                "if(!chosenId){var ids={};for(var gi=0;gi<videoTracks.length;gi++){if(videoTracks[gi].assetId)ids[videoTracks[gi].assetId]=true;}var only=Object.keys(ids);if(only.length===1)chosenId=only[0];}" +
                "if(chosenId){videoTracks=videoTracks.filter(function(x){return x.assetId===chosenId;});audioTracks=audioTracks.filter(function(x){return x.assetId===chosenId;});}else{videoTracks=[];audioTracks=[];}" +
                "if(audioTracks.length){audioTracks.sort(function(a,b){return b.score-a.score||b.time-a.time;});bestAudio=audioTracks[0];}}" +
                "videoTracks.sort(function(a,b){return b.score-a.score||b.time-a.time;});" +
                "for(var j=0;j<Math.min(videoTracks.length,isInstagramHost?1:2);j++)window.AboEzzatBridge.onCapturedVideoTrack(videoTracks[j].url,titleFor(v),posterFor(v));" +
                "if(bestAudio)window.AboEzzatBridge.onCapturedAudioTrack(bestAudio.url,titleFor(v),posterFor(v));" +
                "}catch(e){}}" +
                "function pick(){try{var vids=[].slice.call(document.querySelectorAll('video'));" +
                "if(isThreadsHost&&!armed){var candidates=vids.filter(function(v){return !v.paused&&!v.ended&&v.readyState>=2&&visible(v);});" +
                "if(candidates.length){armed=true;activeSince=performance.now()-4000;seenResources={};}}" +
                "if(!armed)return;" +
                "var active=vids.filter(function(v){return !v.paused&&!v.ended&&v.readyState>=2&&visible(v);});" +
                "active.sort(function(a,b){return area(b)-area(a);});" +
                "if(!active.length){if(activeKey){var old=activeKey;activeKey='';window.AboEzzatBridge.onActivePlaybackStopped(old);}return;}" +
                "var v=active[0],idx=vids.indexOf(v),key=(v.currentSrc||v.src||'')+'|'+idx;if(isInstagramHost){var ai=instagramInfo(v.currentSrc||v.src||'');var ar=v.closest('article')||v.parentElement;var ah=ar?ar.querySelector('a[href*=\\\"/reel/\\\"],a[href*=\\\"/p/\\\"]'):null;key='instagram:'+(ai&&ai.id?ai.id:(ah?(ah.getAttribute('href')||''):key));}" +
                "if(!key||key==='|'+idx)return;bridgeStart(v,key);scanResources(v);" +
                "}catch(e){}}" +
                "function armFromTap(e){try{var x=e.clientX,y=e.clientY;if(e.changedTouches&&e.changedTouches.length){x=e.changedTouches[0].clientX;y=e.changedTouches[0].clientY;}" +
                "var vids=[].slice.call(document.querySelectorAll('video')),v=null,best=0;for(var i=0;i<vids.length;i++){var r=vids[i].getBoundingClientRect();" +
                "if(x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom&&area(vids[i])>best){v=vids[i];best=area(vids[i]);}}" +
                "if(v){armed=true;setTimeout(pick,80);setTimeout(pick,500);}}catch(e){}}" +
                "window.__aboArmActiveCapture=function(){armed=true;pick();};" +
                "document.addEventListener('click',armFromTap,true);document.addEventListener('touchend',armFromTap,true);" +
                "document.addEventListener('playing',function(e){if(e.target&&e.target.tagName==='VIDEO'&&visible(e.target)){armed=true;activeSince=performance.now()-1800;seenResources={};}" +
                "if(armed)setTimeout(pick,80);},true);" +
                "document.addEventListener('pause',function(){setTimeout(pick,250);},true);" +
                "window.addEventListener('scroll',function(){if(isThreadsHost||isInstagramHost||armed){if(isInstagramHost)armed=true;setTimeout(pick,250);}},true);" +
                "if(isInstagramHost){armed=true;setTimeout(pick,300);}setInterval(function(){if(armed)pick();},900);" +
                "}catch(e){}})()";
        view.evaluateJavascript(js, null);
    }

    // YouTube is a single-page app: navigating from the home feed to a video often
    // changes history/location without a WebView onPageStarted callback. Report only the
    // actual visible YouTube watch URL so temporary googlevideo requests are never used
    // as the source page for extraction or casting.
    private boolean isTikTokPageUrl(String value) {
        if (value == null) return false;
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            return host != null && (host.equalsIgnoreCase("tiktok.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".tiktok.com"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void injectTikTokPageUrlTracker(WebView view) {
        if (view == null) return;
        String js = "(function(){try{" +
                "var h=(location.hostname||'').toLowerCase();if(h!=='tiktok.com'&&!h.endsWith('.tiktok.com'))return;" +
                "function report(){try{if(document.visibilityState==='hidden')return;var u=String(location.href||'');" +
                "if(u!==window.__aboLastReportedTikTokUrl){window.__aboLastReportedTikTokUrl=u;" +
                "try{if(window.__aboTikTokRouteReset)window.__aboTikTokRouteReset();}catch(e){}" +
                "var tm=document.querySelector('meta[property=\"og:title\"],meta[name=\"twitter:title\"]');" +
                "var pm=document.querySelector('meta[property=\"og:image\"],meta[name=\"twitter:image\"]');" +
                "var t=(tm&&tm.content)||document.title||'';var p=(pm&&pm.content)||'';" +
                "if(window.AboEzzatBridge&&window.AboEzzatBridge.onTikTokPageChanged)window.AboEzzatBridge.onTikTokPageChanged(u,t,p);}}" +
                "catch(e){}}" +
                "if(!window.__aboTikTokUrlTracker){window.__aboTikTokUrlTracker=true;var ps=history.pushState,rs=history.replaceState;" +
                "history.pushState=function(){var x=ps.apply(this,arguments);setTimeout(report,0);return x;};" +
                "history.replaceState=function(){var x=rs.apply(this,arguments);setTimeout(report,0);return x;};" +
                "window.addEventListener('popstate',report,true);window.addEventListener('hashchange',report,true);" +
                "setInterval(report,300);}" +
                "report();}catch(e){}})();";
        view.evaluateJavascript(js, null);
    }

    private void injectYouTubePageUrlTracker(WebView view) {
        if (view == null) return;
        String js = "(function(){try{" +
                "var h=(location.hostname||'').toLowerCase();if(!/(^|\\.)youtube\\.com$/.test(h)&&h!=='youtu.be'&&!/(^|\\.)youtube-nocookie\\.com$/.test(h))return;" +
                "function report(){try{if(document.visibilityState==='hidden')return;var u=String(location.href||'');if(u!==window.__aboLastReportedYouTubeUrl){window.__aboLastReportedYouTubeUrl=u;if(window.AboEzzatBridge&&window.AboEzzatBridge.onYouTubePageChanged)window.AboEzzatBridge.onYouTubePageChanged(u);}}catch(e){}}" +
                "if(!window.__aboYouTubeUrlTracker){window.__aboYouTubeUrlTracker=true;var p=history.pushState,r=history.replaceState;" +
                "history.pushState=function(){var x=p.apply(this,arguments);setTimeout(report,0);return x;};" +
                "history.replaceState=function(){var x=r.apply(this,arguments);setTimeout(report,0);return x;};" +
                "window.addEventListener('popstate',report,true);window.addEventListener('hashchange',report,true);" +
                "['yt-navigate-finish','yt-page-data-updated','yt-player-updated'].forEach(function(n){document.addEventListener(n,report,true);});" +
                "setInterval(report,250);}" +
                "report();}catch(e){}})();";
        view.evaluateJavascript(js, null);
    }

    // Automatic generic player monitor. It is installed only on non-social web-player
    // pages (plus TikTok LIVE), so existing platform-specific sniffers remain isolated.
    private void injectGenericAutoCapture(WebView view) {
        if (view == null) return;
        // Capture only the currently playing, visible HTML5 player. Never sweep
        // page-wide preload/source lists, which may belong to another video.
        String js = "javascript:(function(){try{" +
                "if(window.__aboGenericAutoCaptureV3)return;window.__aboGenericAutoCaptureV3=true;" +
                "function area(v){try{var r=v.getBoundingClientRect();var w=Math.max(0,Math.min(innerWidth,r.right)-Math.max(0,r.left));var h=Math.max(0,Math.min(innerHeight,r.bottom)-Math.max(0,r.top));return w*h;}catch(e){return 0;}}" +
                "function visible(v){return area(v)>Math.max(12000,(innerWidth*innerHeight)*0.025);}" +
                "function audio(l){return /mime(?:_|-)?type=audio|mime=audio|type=audio|content[_-]?type=audio|dash_ln_heaac|audio_dash|\\.(aac|m4a|mp3|opus|ogg)(?:[?#]|$)/i.test(l);}" +
                "function video(l){return /mime(?:_|-)?type=video|mime=video|type=video|content[_-]?type=video|application\\/(?:vnd\\.apple\\.mpegurl|x-mpegurl|dash\\+xml)|format=(?:m3u8|mpd)|playlist\\.m3u8|chunklist|video\\/|pull-|\\.(m3u8|mpd|mp4|m4v|webm|flv|mov)(?:[?#]|$)/i.test(l);}" +
                "function send(u,title,poster){try{if(!u||!/^https?:/i.test(u))return;var l=u.toLowerCase();" +
                "if(/\\.(jpg|jpeg|png|webp|gif|bmp|svg|ts|m4s|js|css|woff2?)(?:[?#]|$)/i.test(l))return;" +
                "if(audio(l))window.AboEzzatBridge.onCapturedAudioTrack(u,title,poster||'');" +
                "else if(video(l)){if(/\\.mp4(?:[?#]|$)/i.test(l))window.AboEzzatBridge.onCapturedCandidateTrack(u,title,poster||'');else window.AboEzzatBridge.onCapturedVideoTrack(u,title,poster||'');}" +
                "}catch(e){}}" +
                "function scan(w,seen,isTop){try{if(!w||seen.indexOf(w)!==-1)return false;seen.push(w);var d=w.document;if(!d)return false;" +
                "var vs=[].slice.call(d.querySelectorAll('video'));" +
                "var active=vs.filter(function(v){return !v.paused&&!v.ended&&v.readyState>=2&&visible(v);});" +
                "active.sort(function(a,b){return area(b)-area(a);});" +
                "if(active.length){var v=active[0],u=v.currentSrc||v.src||v.getAttribute('src')||'';if(/^https?:/i.test(u)){" +
                "var title=d.title||document.title||'فيديو قيد التشغيل',poster=v.poster||'';var state=w.__aboGenericActiveState;" +
                "var key=isTop?(u+'|'+vs.indexOf(v)):((w.location.href||'')+'|'+u+'|'+vs.indexOf(v));" +
                "if(!state||state.key!==key){state={key:key,since:(w.performance&&w.performance.now?w.performance.now():0)-1800,seen:{}};w.__aboGenericActiveState=state;" +
                "window.AboEzzatBridge.onActiveVideoChanged(key,title,poster);}" +
                "window.AboEzzatBridge.onMetaFound(title,poster);" +
                "send(u,title,poster);" +
                "var es=w.performance&&w.performance.getEntriesByType?w.performance.getEntriesByType('resource'):[];var now=w.performance&&w.performance.now?w.performance.now():0;" +
                "for(var i=0;i<es.length;i++){var e=es[i],ru=e.name||'',lo=ru.toLowerCase();if(!/^https?:/i.test(ru)||e.startTime<state.since||e.startTime>now+100||now-e.startTime>12000||state.seen[ru])continue;" +
                "if(!audio(lo)&&!video(lo))continue;state.seen[ru]=true;send(ru,title,poster);}" +
                "return true;}}" +
                "var fs=d.querySelectorAll('iframe,frame');for(var k=0;k<fs.length;k++){try{if(fs[k].contentWindow&&scan(fs[k].contentWindow,seen,false))return true;}catch(e){}}" +
                "if(w.__aboGenericActiveState){window.AboEzzatBridge.onActivePlaybackStopped(w.__aboGenericActiveState.key);w.__aboGenericActiveState=null;}" +
                "return false;" +
                "}catch(e){return false;}}" +
                "var isDramaLiveHost=/(^|\\.)dramalive\\.cc$/i.test(location.hostname||'');" +
                "var dramaFrameKey='';" +
                "function reportDramaLiveFrame(){try{if(!isDramaLiveHost)return;var f=document.activeElement;if(!f||!f.tagName||String(f.tagName).toLowerCase()!=='iframe')return;var r=f.getBoundingClientRect(),w=Math.max(0,Math.min(innerWidth,r.right)-Math.max(0,r.left)),h=Math.max(0,Math.min(innerHeight,r.bottom)-Math.max(0,r.top));if(w*h<12000)return;var u=f.src||f.getAttribute('src')||'';if(!/^https?:/i.test(u)||u==='about:blank')return;var k=u.split('#')[0];if(k!==dramaFrameKey){dramaFrameKey=k;var t=document.title||'Drama Live',p='';var m=document.querySelector('meta[property=\"og:image\"],meta[name=\"twitter:image\"]');if(m)p=m.content||'';if(window.AboEzzatBridge&&window.AboEzzatBridge.onActivePlayerFrameChanged)window.AboEzzatBridge.onActivePlayerFrameChanged(u,t,p);}}catch(e){}}" +
                "if(isDramaLiveHost){document.addEventListener('focusin',function(e){if(e.target&&String(e.target.tagName||'').toLowerCase()==='iframe')setTimeout(reportDramaLiveFrame,120);},true);document.addEventListener('pointerdown',function(e){if(e.target&&String(e.target.tagName||'').toLowerCase()==='iframe')setTimeout(reportDramaLiveFrame,120);},true);window.addEventListener('blur',function(){setTimeout(reportDramaLiveFrame,80);},true);setInterval(reportDramaLiveFrame,500);}" +
                "function tick(){scan(window,[],true);reportDramaLiveFrame();}" +
                "document.addEventListener('playing',tick,true);document.addEventListener('play',tick,true);" +
                "document.addEventListener('loadedmetadata',tick,true);document.addEventListener('canplay',tick,true);" +
                "document.addEventListener('click',function(){setTimeout(tick,350);setTimeout(tick,900);},true);" +
                "window.addEventListener('load',tick,true);" +
                "try{new MutationObserver(function(){tick();}).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});}catch(e){}" +
                "tick();setInterval(tick,900);" +
                "}catch(e){}})()";
        view.evaluateJavascript(js, null);
    }

    private boolean isValidCapturedHttpUrl(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 4096) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().trim().isEmpty()
                    && uri.getUserInfo() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private class JsBridge {
        private final WebView bridgeWebView;

        JsBridge(WebView owner) {
            bridgeWebView = owner;
        }

        @JavascriptInterface
        public void onMetaFound(String title, String posterUrl) {
            if (title != null && !title.isEmpty()) {
                String cleanTitle = title.replace("Instagram: ", "").replace("on Instagram", "").trim();
                lastDiscoveredTitle = cleanTitle.length() > 160 ? cleanTitle.substring(0, 160) : cleanTitle;
            }
            if (isValidCapturedHttpUrl(posterUrl)) {
                lastDiscoveredPoster = posterUrl.length() > 2048 ? posterUrl.substring(0, 2048) : posterUrl;
            }
        }

        @JavascriptInterface
        public void onTikTokPageChanged(String pageUrl, String pageTitle, String posterUrl) {
            if (pageUrl == null || !pageUrl.startsWith("http")) return;
            final WebView source = activeWebView;
            if (source == null || !isTikTokPageUrl(pageUrl)) return;
            final String reportedUrl = pageUrl.trim();
            runOnUiThread(() -> {
                if (source != getCurrentWebView()) return;
                String currentUrl = source.getUrl();
                if (currentUrl == null || !currentUrl.equals(reportedUrl)) return;
                if (reportedUrl.equals(activePageUrl)) return;

                // TikTok changes videos with History API calls, without a full WebView
                // navigation. Treat each video route as a new capture generation.
                activePageUrl = reportedUrl;
                activePlaybackKey = "";
                mp4ProbeRequests.clear();
                lastDiscoveredTitle = pageTitle == null ? "" : pageTitle.trim();
                lastDiscoveredPoster = posterUrl == null ? "" : posterUrl.trim();
                clearCapturedMedia();
                if (etUrl != null) etUrl.setText(reportedUrl);
                if (currentTabIndex >= 0 && currentTabIndex < tabTitles.size()) {
                    String title = source.getTitle();
                    tabTitles.set(currentTabIndex, title == null || title.trim().isEmpty()
                            ? reportedUrl : title.trim());
                }
                navigationStartedAt.put(source, System.currentTimeMillis());
                recordVisitedUrl(source, reportedUrl);
            });
        }

        @JavascriptInterface
        public void onYouTubePageChanged(String pageUrl) {
            // Ignore callbacks from hidden tabs or pages that are no longer YouTube.
            if (!isYouTubePageUrl(activePageUrl)) return;
            String canonical = canonicalYouTubeWatchUrl(pageUrl);
            if (canonical == null || canonical.equals(activeYouTubeWatchUrl)) return;

            // A YouTube SPA can switch videos without a WebView navigation callback.
            // Invalidate the old capture immediately and start extraction for the new ID.
            activeYouTubeWatchUrl = canonical;
            activePlaybackKey = "";
            synchronized (BrowserActivity.this) {
                youtubeExtractionInProgressFor = "";
                youtubeExtractedFor = "";
                youtubeCastPendingFor = "";
            }
            clearCapturedMedia();
            extractYouTubeForPage(canonical, false);
        }

        @JavascriptInterface
        public void onActivePlayerFrameChanged(String frameUrl, String title, String posterUrl) {
            if (!isDramaLivePage(activePageUrl) || frameUrl == null || !frameUrl.startsWith("http")) return;
            final String cleanFrameUrl = frameUrl.trim();
            final String key = "dramalive-frame:" + cleanFrameUrl;
            boolean changed = false;
            synchronized (BrowserActivity.this) {
                if (!key.equals(activePlaybackKey)) {
                    activePlaybackKey = key;
                    activePlayerFrameUrl = cleanFrameUrl;
                    activePlayerFrameActivatedAtMs = System.currentTimeMillis();
                    genericAutoSniffUntilMs = activePlayerFrameActivatedAtMs + 10 * 60 * 1000L;
                    dramaLiveProbeRequests.clear();
                    mp4ProbeRequests.clear();
                    clearCapturedMedia();
                    changed = true;
                }
                if (title != null && !title.trim().isEmpty()) lastDiscoveredTitle = title.trim();
                if (posterUrl != null && !posterUrl.trim().isEmpty()) lastDiscoveredPoster = posterUrl.trim();
            }
            if (changed) probeDramaLivePlayerDocument(cleanFrameUrl, key, lastDiscoveredTitle,
                    lastDiscoveredPoster, null);
        }

        @JavascriptInterface
        public void onActiveVideoChanged(String key, String title, String posterUrl) {
            if (key == null || key.isEmpty() || key.length() > 256) return;
            if (title != null && title.length() > 160) title = title.substring(0, 160);
            if (posterUrl != null && !isValidCapturedHttpUrl(posterUrl)) posterUrl = "";
            String youtubePage = getCurrentYouTubePageUrl();
            synchronized (BrowserActivity.this) {
                String previousPlaybackKey = activePlaybackKey;
                if (youtubePage != null) {
                    // YouTube changes its internal media source while a LIVE playlist refreshes.
                    // Keep one page-scoped playback identity so those refreshes never clear the list.
                    activePlaybackKey = "youtube:" + youtubePage;
                } else {
                    boolean keepTikTokLiveCapture = isTikTokLivePage(activePageUrl)
                            && activePlaybackKey.startsWith("tiktok-live:");
                    if (!keepTikTokLiveCapture && !key.equals(activePlaybackKey)) {
                        activePlaybackKey = key;
                        clearCapturedMedia();
                    }
                }
                if (!previousPlaybackKey.equals(activePlaybackKey)) {
                    synchronized (mp4ProbeRequests) { mp4ProbeRequests.clear(); }
                    synchronized (dramaLiveProbeRequests) { dramaLiveProbeRequests.clear(); }
                }
                if (title != null && !title.trim().isEmpty()) lastDiscoveredTitle = title.trim();
                if (posterUrl != null && !posterUrl.trim().isEmpty()) lastDiscoveredPoster = posterUrl;
            }
            if (youtubePage != null) extractYouTubeForPage(youtubePage, false);
        }

        @JavascriptInterface
        public void onActivePlaybackStopped(String key) {
            // YouTube LIVE can briefly pause while its HLS/DASH playlist refreshes.
            // Keep the last verified stream visible until the watch page itself changes.
            if (getCurrentYouTubePageUrl() != null) return;
            synchronized (BrowserActivity.this) {
                if (key != null && key.equals(activePlaybackKey)) {
                    activePlaybackKey = "";
                    clearCapturedMedia();
                }
            }
        }

        @JavascriptInterface
        public void onActiveVideoTrack(String mediaUrl, String title, String posterUrl) {
            if (activePlaybackKey.isEmpty()
                    || mediaUrl == null || !mediaUrl.startsWith("http") || isBlockedAdUrl(mediaUrl)) return;
            String clean = mediaUrl.replaceAll("&bytestart=\\d+", "")
                    .replaceAll("&byteend=\\d+", "");
            String cleanTitle = title == null || title.trim().isEmpty() ? "فيديو قيد التشغيل" : title.trim();
            // A URL used by an HTML <video> element is not proof that the
            // URL contains video. Social DASH players can expose audio-only MP4.
            int detectedType = classifyCapturedMedia(clean);
            if (detectedType == 2) {
                addCapturedMedia(clean, cleanTitle, "🎵 مسار صوت (تم التحقق من الرابط)", posterUrl, 2);
            } else if (detectedType == 1) {
                addCapturedMedia(clean, cleanTitle, "🎬 مسار الفيديو النشط", posterUrl, 1);
            } else if (clean.toLowerCase().matches(".*\\.mp4(?:[?#].*)?$")) {
                probeAndAddMp4(clean, cleanTitle, posterUrl);
            }
        }

        @JavascriptInterface
        public void onCapturedVideoTrack(String mediaUrl, String title, String posterUrl) {
            if (activePlaybackKey.isEmpty()
                    || mediaUrl == null || !mediaUrl.startsWith("http") || isBlockedAdUrl(mediaUrl)) return;
            String lower = mediaUrl.toLowerCase(Locale.ROOT);
            // YouTube SABR videoplayback URLs are transport requests, not verified standalone tracks.
            // They must never enter the generic captured-video list or be paired as audio/video.
            if (lower.contains("googlevideo.com") && lower.contains("/videoplayback")) return;
            String clean = mediaUrl.replaceAll("&bytestart=\\d+", "")
                    .replaceAll("&byteend=\\d+", "");
            String cleanTitle = title == null || title.trim().isEmpty() ? "فيديو قيد التشغيل" : title.trim();
            addCapturedMedia(clean, cleanTitle, "🎬 مسار فيديو", posterUrl, 1);
        }

        @JavascriptInterface
        public void onCapturedCandidateTrack(String mediaUrl, String title, String posterUrl) {
            if (activePlaybackKey.isEmpty()
                    || mediaUrl == null || !mediaUrl.startsWith("http") || isBlockedAdUrl(mediaUrl)) return;
            probeAndAddMp4(mediaUrl, title, posterUrl);
        }

        @JavascriptInterface
        public void onCapturedUnknownVideoTrack(String mediaUrl, String title, String posterUrl) {
            if (activePlaybackKey.isEmpty()
                    || mediaUrl == null || !mediaUrl.startsWith("http") || isBlockedAdUrl(mediaUrl)) return;
            probeUnknownVideoByContentType(mediaUrl, title, posterUrl);
        }

        @JavascriptInterface
        public void onCapturedAudioTrack(String mediaUrl, String title, String posterUrl) {
            if (activePlaybackKey.isEmpty() || mediaUrl == null || !mediaUrl.startsWith("http")) return;
            String lower = mediaUrl.toLowerCase(Locale.ROOT);
            if (isBlockedAdUrl(mediaUrl)
                    || (lower.contains("googlevideo.com") && lower.contains("/videoplayback"))) return;
            String clean = mediaUrl.replaceAll("&bytestart=\\d+", "")
                    .replaceAll("&byteend=\\d+", "");
            String cleanTitle = title == null || title.trim().isEmpty() ? "فيديو قيد التشغيل" : title.trim();
            addCapturedMedia(clean, cleanTitle, "🎵 مسار صوت منفصل", posterUrl, 2);
        }

        @JavascriptInterface
        public void onVideoPlayingWithPoster(String mediaUrl, String title, String posterUrl) {
            if (activePlaybackKey.isEmpty() || mediaUrl == null || mediaUrl.isEmpty()
                    || !mediaUrl.startsWith("http")) return;
            String cleanMedia = mediaUrl.replaceAll("&bytestart=\\d+", "")
                    .replaceAll("&byteend=\\d+", "");
            int type = classifyCapturedMedia(cleanMedia);
            if (type == 0) return;

            String lower = cleanMedia.toLowerCase();
            String format = type == 2 ? "🎵 مسار صوت"
                    : lower.contains("pull-") ? "📡 بث تيك توك مباشر"
                    : lower.contains(".m3u8") ? "📡 بث مباشر - m3u8"
                    : lower.contains(".mpd") ? "🎬 فيديو DASH"
                    : "🎬 فيديو";
            String realTitle = title != null && !title.trim().isEmpty()
                    ? title.trim() : (!lastDiscoveredTitle.isEmpty() ? lastDiscoveredTitle : "فيديو قيد التشغيل");
            String realPoster = posterUrl != null && !posterUrl.isEmpty() ? posterUrl : lastDiscoveredPoster;
            addCapturedMedia(cleanMedia, realTitle, format, realPoster, type);
        }

        @JavascriptInterface
        public void onNewCustomSite(String name, String siteUrl) {
            final WebView source = bridgeWebView;
            runOnUiThread(() -> {
                if (!isInternalHomeBridgeAllowed(source)) {
                    Log.w(TAG, "Rejected custom-site bridge call outside the app home page");
                    return;
                }
                saveCustomSite(name, siteUrl);
                loadHomePage();
            });
        }
    }

    // Bound per-playback deduplication state so hostile pages cannot grow these sets forever.
    private boolean addBoundedProbeRequest(Set<String> requests, String key) {
        if (key == null || key.isEmpty() || key.length() > 8192) return false;
        synchronized (requests) {
            if (requests.contains(key) || requests.size() >= 256) return false;
            return requests.add(key);
        }
    }

    private void clearCapturedMedia() {
        synchronized (capturedList) {
            capturedList.clear();
        }
        lastDiscoveredPoster = "";
        lastDiscoveredTitle = "";
        runOnUiThread(() -> {
            if (btnCast != null) {
                btnCast.clearAnimation();
                btnCast.setText("📺 0");
                btnCast.setVisibility(View.GONE);
            }
        });
    }

    private boolean isTikTokCdnVideoUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            // Standard TikTok VOD CDN URLs use /video/tos/. LIVE stream
            // variants use both /stage/stream-* and /game/stream-* paths.
            boolean standardTikTokVideo = (host.equals("tiktok.com") || host.endsWith(".tiktok.com"))
                    && path.contains("/video/tos/");
            boolean liveStreamPath = path.contains("/stage/stream-") || path.contains("/game/stream-");
            boolean tiktokLiveCdn = (host.equals("tiktokcdn.com") || host.endsWith(".tiktokcdn.com"))
                    && host.startsWith("pull-")
                    && liveStreamPath
                    && (path.endsWith(".flv") || path.endsWith(".m3u8"));
            return standardTikTokVideo || tiktokLiveCdn;
        } catch (Exception ignored) {
            return false;
        }
    }

    private java.util.Map<String, String> capturePlaybackRequestHeaders(WebResourceRequest request, String mediaUrl) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        if (request == null || !isTikTokCdnVideoUrl(mediaUrl)) return result;
        try {
            java.util.Map<String, String> source = request.getRequestHeaders();
            if (source != null) {
                for (java.util.Map.Entry<String, String> entry : source.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (key == null || value == null || value.isEmpty()) continue;
                    String lower = key.toLowerCase(Locale.ROOT);
                    if (("user-agent".equals(lower) || "referer".equals(lower) || "origin".equals(lower)
                            || "accept".equals(lower) || "accept-language".equals(lower)
                            || "sec-fetch-site".equals(lower) || "sec-fetch-mode".equals(lower)
                            || "sec-fetch-dest".equals(lower) || "sec-ch-ua".equals(lower)
                            || "sec-ch-ua-mobile".equals(lower) || "sec-ch-ua-platform".equals(lower)
                            || "priority".equals(lower))
                            && value.length() <= 2048 && !value.contains("\r") && !value.contains("\n")) {
                        result.put(lower, value);
                    }
                }
            }
            // If WebView omits User-Agent from the request snapshot, preserve the
            // actual identity configured on the active WebView.
            if (!result.containsKey("user-agent") && activeWebView != null) {
                String actualUserAgent = activeWebView.getSettings().getUserAgentString();
                if (actualUserAgent != null && !actualUserAgent.trim().isEmpty()) {
                    result.put("user-agent", actualUserAgent);
                }
            }
            // WebView does not expose Cookie in every request-header snapshot.
            // Ask its cookie store for the exact CDN host's cookies as a fallback.
            String cookie = CookieManager.getInstance().getCookie(mediaUrl);
            if (cookie != null && !cookie.isEmpty() && cookie.length() <= 8192
                    && !cookie.contains("\\r") && !cookie.contains("\\n")) {
                result.put("cookie", cookie);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private int classifyCapturedMedia(String mediaUrl) {
        if (mediaUrl == null) return 0;
        String lower = mediaUrl.toLowerCase();
        // YouTube googlevideo videoplayback requests can be SABR/UMP transport, not a standalone MP4.
        // Do not label them as video from the endpoint name alone; NewPipe extracts a fresh matched pair
        // from the canonical watch URL instead.
        if (isBlockedAdUrl(mediaUrl)) return 0;
        if (lower.contains("googlevideo.com") && lower.contains("/videoplayback")) return 0;
        // Never offer image resources as castable video, including extension-less
        // animated GIF/WebP endpoints whose MIME type is carried in the query.
        if (lower.matches(".*\\.(jpg|jpeg|png|webp|gif|gifv|apng|avif|bmp|svg|ts|m4s|js|css)(?:[?#].*)?$")
                || lower.contains("mime_type=image") || lower.contains("mime-type=image")
                || lower.contains("mime=image") || lower.contains("content_type=image")
                || lower.contains("contenttype=image") || lower.contains("image/gif")
                || lower.contains("image/webp") || lower.contains("image/apng")
                || lower.contains("format=gif") || lower.contains("format=webp")) return 0;

        // Instagram/Threads frequently expose DASH tracks as extension-less CDN
        // URLs whose media type is carried in mime_type rather than mime.
        boolean audio = lower.contains("mime_type=audio") || lower.contains("mime-type=audio")
                || lower.contains("mime=audio") || lower.contains("type=audio")
                || lower.contains("contenttype=audio") || lower.contains("content_type=audio")
                || lower.contains("dash_ln_heaac") || lower.contains("audio_dash")
                || lower.matches(".*\\.(aac|m4a|mp3|opus|ogg)(?:[?#].*)?$");
        if (audio) return 2;

        // TikTok LIVE pull URLs are not guaranteed to have a file extension
        // or an explicit video MIME marker. Keep recognizing the stream endpoint.
        boolean tiktokLiveStream = lower.contains("pull-")
                || (lower.contains("live") && (lower.contains("video") || lower.contains("stream")));
        boolean video = lower.matches(".*\\.(m4v|m3u8|mpd|webm|flv|mov)(?:[?#].*)?$")
                || lower.contains("mime_type=video") || lower.contains("mime-type=video")
                || lower.contains("mime=video") || lower.contains("type=video")
                || lower.contains("contenttype=video") || lower.contains("content_type=video")
                || lower.contains("application/vnd.apple.mpegurl")
                || lower.contains("application/x-mpegurl") || lower.contains("application/dash+xml")
                || lower.contains("format=m3u8") || lower.contains("format=mpd")
                || lower.contains("playlist.m3u8") || lower.contains("chunklist")
                || lower.contains("videoplayback") || lower.contains("video/tos")
                || lower.contains("/video/tos/") || tiktokLiveStream;
        return video ? 1 : 0;
    }

    private int capturedUrlConfidence(String url, int mediaType) {
        if (url == null) return 0;
        String lower = url.toLowerCase();
        if (mediaType == 2) {
            if (lower.contains("mime_type=audio") || lower.contains("mime-type=audio")
                    || lower.contains("mime=audio") || lower.contains("content_type=audio")) return 100;
            if (lower.contains("dash_ln_heaac") || lower.contains("audio_dash")
                    || lower.matches(".*\\.(aac|m4a|mp3|opus|ogg)(?:[?#].*)?$")) return 85;
        } else if (mediaType == 1) {
            if (lower.contains("mime_type=video") || lower.contains("mime-type=video")
                    || lower.contains("mime=video") || lower.contains("content_type=video")) return 100;
            if (lower.contains("video/tos") || lower.contains("/video/tos/")
                    || lower.contains("videoplayback")) return 90;
            if (lower.matches(".*\\.(mp4|m4v|m3u8|mpd|webm|flv|mov)(?:[?#].*)?$")
                    || lower.contains("pull-")) return 85;
        }
        return 10;
    }

    // Estimate rendition quality from explicit resolution/bitrate hints in CDN URLs.
    private int capturedVideoQualityScore(String url) {
        if (url == null) return 0;
        String lower = url.toLowerCase(Locale.ROOT);
        int width = 0, height = 0, bitrate = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:width|w)[=_-](\\d{3,4})").matcher(lower);
        if (m.find()) try { width = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        m = java.util.regex.Pattern.compile("(?:height|h)[=_-](\\d{3,4})").matcher(lower);
        if (m.find()) try { height = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        m = java.util.regex.Pattern.compile("(?:bitrate|bit_rate|br)[=_-](\\d{4,8})").matcher(lower);
        if (m.find()) try { bitrate = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        m = java.util.regex.Pattern.compile("(\\d{3,4})x(\\d{3,4})").matcher(lower);
        if (m.find()) try {
            width = Math.max(width, Integer.parseInt(m.group(1)));
            height = Math.max(height, Integer.parseInt(m.group(2)));
        } catch (Exception ignored) {}
        int score = width * height + Math.min(bitrate, 20000000) / 10;
        if (lower.matches(".*(1080p|2160p|uhd|high[_-]?quality|p1080x1080|p1440x1440).*")) score += 3000000;
        if (lower.matches(".*(720p|hd).*")) score += 1200000;
        if (lower.matches(".*(480p|sd).*")) score += 400000;
        return score;
    }

    private synchronized void addCapturedMedia(String url, String title, String format, String posterUrl, int mediaType) {
        addCapturedMedia(url, title, format, posterUrl, mediaType, null);
    }

    private synchronized void addCapturedMedia(String url, String title, String format, String posterUrl,
                                               int mediaType, java.util.Map<String, String> requestHeaders) {
        if (!isValidCapturedHttpUrl(url) || isBlockedAdUrl(url)) return;
        if ((mediaType != 1 && mediaType != 2) || activePlaybackKey.isEmpty()) return;
        if (posterUrl != null && !posterUrl.isEmpty() && !isValidCapturedHttpUrl(posterUrl)) posterUrl = "";
        if (posterUrl != null && posterUrl.length() > 2048) posterUrl = posterUrl.substring(0, 2048);
        boolean added = false;
        synchronized (capturedList) {
            for (CapturedMedia item : capturedList) {
                if (item.url.equalsIgnoreCase(url)) {
                    if (requestHeaders != null && !requestHeaders.isEmpty()) item.requestHeaders.putAll(requestHeaders);
                    // An active <video> element is authoritative: promote a URL
                    // previously guessed as audio to a video track when necessary.
                    if (mediaType == 1 && item.mediaType != 1) {
                        item.mediaType = 1;
                        item.isAudio = false;
                        item.format = format;
                        item.isSelected = true;
                    } else if (mediaType == 2 && item.mediaType != 2) {
                        item.mediaType = 2;
                        item.isAudio = true;
                        item.format = format;
                        item.isSelected = true;
                    }
                    if ((item.posterUrl == null || item.posterUrl.isEmpty()) && posterUrl != null && !posterUrl.isEmpty()) {
                        item.posterUrl = posterUrl;
                        loadThumbnailAsync(item);
                    }
                    return;
                }
            }

            // Audio is a separate DASH rendition: retain one explicitly typed audio track.
            if (mediaType == 2) {
                for (CapturedMedia item : capturedList) {
                    if (item.mediaType == 2) {
                        if (capturedUrlConfidence(url, 2) > capturedUrlConfidence(item.url, 2)) {
                            item.url = url;
                            item.format = format;
                            item.isSelected = true;
                        }
                        return;
                    }
                }
            } else {
                int videoCount = 0;
                for (CapturedMedia item : capturedList) if (item.mediaType == 1) videoCount++;
                // Keep a few renditions; discard the lowest quality if more variants arrive.
                if (videoCount >= 2) {
                    CapturedMedia lowest = null;
                    for (CapturedMedia item : capturedList) {
                        if (item.mediaType == 1 && (lowest == null
                                || capturedVideoQualityScore(item.url) < capturedVideoQualityScore(lowest.url))) lowest = item;
                    }
                    if (lowest != null && capturedVideoQualityScore(url) <= capturedVideoQualityScore(lowest.url)) return;
                    if (lowest != null) capturedList.remove(lowest);
                }
            }

            String cleanTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "فيديو قيد التشغيل";
            if (cleanTitle.length() > 70) cleanTitle = cleanTitle.substring(0, 70) + "...";
            CapturedMedia newMedia = new CapturedMedia(url, cleanTitle, format, posterUrl, mediaType);
            // Select both video renditions and the separate audio track by default.
            // The merge action independently chooses the highest-quality video.
            newMedia.isSelected = true;
            if (requestHeaders != null) newMedia.requestHeaders.putAll(requestHeaders);
            capturedList.add(newMedia);
            added = true;
            if (posterUrl != null && !posterUrl.isEmpty()) loadThumbnailAsync(newMedia);
        }

        if (added) {
            runOnUiThread(() -> {
                btnCast.setVisibility(View.VISIBLE);
                btnCast.setText("📺 " + capturedList.size());
                Animation blink = new AlphaAnimation(0.3f, 1.0f);
                blink.setDuration(350);
                blink.setRepeatMode(Animation.REVERSE);
                blink.setRepeatCount(2);
                btnCast.startAnimation(blink);
            });
        }
    }

    private void autoSelectTracks() {
        boolean hasSelectedVideo = false;
        boolean hasSelectedAudio = false;

        for (CapturedMedia item : capturedList) {
            if (item.isSelected) {
                if (item.mediaType == 1) hasSelectedVideo = true;
                if (item.mediaType == 2) hasSelectedAudio = true;
            }
        }

        for (CapturedMedia item : capturedList) {
            if (!hasSelectedVideo && item.mediaType == 1) {
                item.isSelected = true;
                hasSelectedVideo = true;
            } else if (!hasSelectedAudio && item.mediaType == 2) {
                item.isSelected = true;
                hasSelectedAudio = true;
            }
        }
    }

    private boolean isSafePublicHttpUrl(String value) {
        if (value == null || value.length() > 4096) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || host == null || host.trim().isEmpty() || uri.getUserInfo() != null) return false;
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost")
                    || lowerHost.endsWith(".local") || lowerHost.endsWith(".lan")
                    || lowerHost.endsWith(".internal") || lowerHost.endsWith(".home")
                    || lowerHost.endsWith(".invalid")) return false;

            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses == null || addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) return false;
                byte[] bytes = address.getAddress();
                if (bytes.length == 4) {
                    int a = bytes[0] & 255, b = bytes[1] & 255;
                    if (a == 0 || a == 10 || a == 127 || a >= 224
                            || (a == 100 && b >= 64 && b <= 127)
                            || (a == 169 && b == 254)
                            || (a == 172 && b >= 16 && b <= 31)
                            || (a == 192 && (b == 0 || b == 168))
                            || (a == 198 && (b == 18 || b == 19))) return false;
                } else if (bytes.length == 16) {
                    // Reject IPv6 unique-local and link-local ranges, including mapped private IPv4.
                    if ((bytes[0] & 0xfe) == 0xfc
                            || ((bytes[0] & 255) == 0xfe && (bytes[1] & 0xc0) == 0x80)) return false;
                    boolean mappedIpv4 = true;
                    for (int i = 0; i < 10; i++) if (bytes[i] != 0) mappedIpv4 = false;
                    mappedIpv4 = mappedIpv4 && (bytes[10] & 255) == 255 && (bytes[11] & 255) == 255;
                    if (mappedIpv4) {
                        int a = bytes[12] & 255, b = bytes[13] & 255;
                        if (a == 0 || a == 10 || a == 127 || a >= 224
                                || (a == 100 && b >= 64 && b <= 127)
                                || (a == 169 && b == 254)
                                || (a == 172 && b >= 16 && b <= 31)
                                || (a == 192 && (b == 0 || b == 168))
                                || (a == 198 && (b == 18 || b == 19))) return false;
                    }
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void probeAndAddMp4(final String mediaUrl, final String title, final String posterUrl) {
        if (mediaUrl == null || isBlockedAdUrl(mediaUrl) || activePlaybackKey.isEmpty()
                || !addBoundedProbeRequest(mp4ProbeRequests, mediaUrl)) return;
        final String captureKey = activePlaybackKey;
        AsyncTask.execute(() -> {
            if (!isSafePublicHttpUrl(mediaUrl)) {
                Log.w(TAG, "Blocked media probe to a non-public host");
                return;
            }
            int type = Mp4TrackInspector.inspect(mediaUrl);
            if (type == 0 || !captureKey.equals(activePlaybackKey)) return;
            String clean = mediaUrl.replaceAll("&bytestart=\\d+", "")
                    .replaceAll("&byteend=\\d+", "");
            String label = type == 2 ? "🎵 مسار صوت (تم فحص MP4)" : "🎬 مسار فيديو (تم فحص MP4)";
            addCapturedMedia(clean, title, label, posterUrl, type);
        });
    }

    // Drama Live's isolated player may expose only an iframe page or a JSON endpoint,
    // not a normal <video src> in the top document. Inspect only the focused player frame
    // and its stream/player endpoints, then extract real HLS/DASH/MP4 URLs from the response.
    private void probeDramaLivePlayerDocument(final String endpointUrl, final String captureKey,
                                               final String title, final String posterUrl,
                                               final Map<String, String> requestHeaders) {
        if (endpointUrl == null || !endpointUrl.startsWith("http") || captureKey == null
                || !captureKey.equals(activePlaybackKey) || !isDramaLivePage(activePageUrl)) return;
        final String dedupe = captureKey + "|" + endpointUrl;
        if (!addBoundedProbeRequest(dramaLiveProbeRequests, dedupe)) return;
        final Map<String, String> headers = requestHeaders == null
                ? new HashMap<>() : new HashMap<>(requestHeaders);
        AsyncTask.execute(() -> {
            if (!isSafePublicHttpUrl(endpointUrl)) {
                Log.w(TAG, "Blocked player endpoint probe to a non-public host");
                return;
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpointUrl).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,application/dash+xml,application/json,text/plain,text/html,*/*");
                connection.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(BrowserActivity.this));
                String cookie = CookieManager.getInstance().getCookie(endpointUrl);
                if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                String referer = activePlayerFrameUrl == null || activePlayerFrameUrl.isEmpty()
                        ? activePageUrl : activePlayerFrameUrl;
                if (referer != null && !referer.isEmpty()) connection.setRequestProperty("Referer", referer);
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    String name = h.getKey(), value = h.getValue();
                    if (name == null || value == null || name.equalsIgnoreCase("Host")
                            || name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Cookie")
                            || name.equalsIgnoreCase("Accept-Encoding") || name.equalsIgnoreCase("Connection")
                            || name.equalsIgnoreCase("Referer") || name.equalsIgnoreCase("User-Agent")) continue;
                    try { connection.setRequestProperty(name, value); } catch (Exception ignored) {}
                }
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300 || !captureKey.equals(activePlaybackKey)) return;
                InputStream stream = connection.getInputStream();
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0, count;
                while ((count = stream.read(buffer)) != -1 && total < 768 * 1024) {
                    int take = Math.min(count, 768 * 1024 - total);
                    output.write(buffer, 0, take);
                    total += take;
                }
                stream.close();
                String body = new String(output.toByteArray(), "UTF-8");
                String trimmed = body.trim();
                String realTitle = title == null || title.trim().isEmpty() ? "Drama Live" : title.trim();
                String realPoster = posterUrl == null ? "" : posterUrl;
                if (trimmed.startsWith("#EXTM3U") || trimmed.contains("\n#EXTM3U")) {
                    if (captureKey.equals(activePlaybackKey)) {
                        addCapturedMedia(endpointUrl, realTitle, "📡 بث HLS من مشغل Drama Live",
                                realPoster, 1);
                    }
                    return;
                }
                String normalized = body.replace("\\/", "/").replace("\\u0026", "&")
                        .replace("&amp;", "&").replace("\\u003a", ":");
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("https?://[^\\s\\\"'<>\\\\]+", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(normalized);
                while (matcher.find()) {
                    if (!captureKey.equals(activePlaybackKey)) return;
                    String candidate = matcher.group().replaceAll("[),;]+$", "");
                    int type = classifyCapturedMedia(candidate);
                    if (type == 1 || type == 2) {
                        addCapturedMedia(candidate, realTitle,
                                type == 2 ? "🎵 صوت القناة من مشغل Drama Live"
                                        : candidate.toLowerCase(Locale.ROOT).contains(".m3u8")
                                        ? "📡 بث HLS من مشغل Drama Live" : "🎬 فيديو من مشغل Drama Live",
                                realPoster, type);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Drama Live player endpoint probe failed: " + endpointUrl, e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    // Active HTML5 video elements sometimes use extension-less CDN URLs.
    // Confirm the response MIME type before offering such a URL to the user.
    private void probeUnknownVideoByContentType(final String mediaUrl, final String title, final String posterUrl) {
        if (mediaUrl == null || isBlockedAdUrl(mediaUrl) || !mediaUrl.startsWith("http")
                || activePlaybackKey.isEmpty()
                || !addBoundedProbeRequest(mp4ProbeRequests, "mime:" + mediaUrl)) return;
        final String captureKey = activePlaybackKey;
        String lower = mediaUrl.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|svg|ts|m4s|js|css)(?:[?#].*)?$")) return;
        AsyncTask.execute(() -> {
            if (!isSafePublicHttpUrl(mediaUrl)) {
                Log.w(TAG, "Blocked media verification request to a non-public host");
                return;
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(mediaUrl).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Range", "bytes=0-1");
                connection.setRequestProperty("Accept", "video/*,application/vnd.apple.mpegurl,application/x-mpegURL,application/dash+xml,*/*;q=0.5");
                connection.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(BrowserActivity.this));
                String cookie = CookieManager.getInstance().getCookie(mediaUrl);
                if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                if (activePageUrl != null && !activePageUrl.isEmpty()) connection.setRequestProperty("Referer", activePageUrl);
                int status = connection.getResponseCode();
                String mime = connection.getContentType();
                if (status >= 200 && status < 300 && mime != null) {
                    String type = mime.toLowerCase(Locale.ROOT).split(";")[0].trim();
                    int mediaType = type.startsWith("video/") || type.equals("application/vnd.apple.mpegurl")
                            || type.equals("application/x-mpegurl") || type.equals("application/dash+xml") ? 1 : 0;
                    if (mediaType == 1 && captureKey.equals(activePlaybackKey)) {
                        String clean = mediaUrl.replaceAll("&bytestart=\\d+", "").replaceAll("&byteend=\\d+", "");
                        addCapturedMedia(clean, title == null || title.trim().isEmpty() ? "فيديو قيد التشغيل" : title.trim(),
                                type.contains("mpegurl") ? "📡 بث مباشر - m3u8" : type.contains("dash") ? "🎬 فيديو DASH" : "🎬 فيديو (تم فحص نوع الاستجابة)",
                                posterUrl, mediaType);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Could not verify extension-less video URL", e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private String compactCapturedUrl(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.length() <= 92) return clean;
        return clean.substring(0, 48) + " … " + clean.substring(clean.length() - 38);
    }

    private void showCapturedUrlDialog(CapturedMedia item) {
        if (item == null || item.url == null) return;
        TextView value = new TextView(this);
        value.setText(item.url);
        value.setTextIsSelectable(true);
        value.setTextDirection(View.TEXT_DIRECTION_LTR);
        value.setTextColor(Color.WHITE);
        value.setPadding(dp(18), dp(14), dp(18), dp(14));
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle(item.mediaType == 2 ? "رابط مسار الصوت" : item.mediaType == 3
                        ? "رابط صفحة الموقع" : "رابط الفيديو الملتقط")
                .setView(value)
                .setPositiveButton("نسخ الرابط", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Captured media URL", item.url));
                        Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private String getYouTubeThumbnailForCurrentPage() {
        String page = getCurrentYouTubePageUrl();
        if (page == null) return "";
        try {
            Uri uri = Uri.parse(page);
            String id = uri.getQueryParameter("v");
            if (id == null || id.isEmpty()) {
                List<String> parts = uri.getPathSegments();
                if (parts != null && !parts.isEmpty()) id = parts.get(parts.size() - 1);
            }
            if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return "";
            return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        } catch (Exception ignored) { return ""; }
    }

    private void loadThumbnailAsync(final CapturedMedia media) {
        if (media == null || media.posterUrl == null || media.posterUrl.trim().isEmpty()) return;
        AsyncTask.execute(() -> {
            if (!isSafePublicHttpUrl(media.posterUrl)) {
                Log.w(TAG, "Blocked thumbnail request to a non-public host");
                return;
            }
            HttpURLConnection conn = null;
            InputStream input = null;
            try {
                URL u = new URL(media.posterUrl);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(BrowserActivity.this));
                conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                String cookie = CookieManager.getInstance().getCookie(media.posterUrl);
                if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
                if (activePageUrl != null && !activePageUrl.isEmpty()) conn.setRequestProperty("Referer", activePageUrl);
                input = conn.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap != null) {
                    media.posterBitmap = bitmap;
                    runOnUiThread(() -> {
                        if (capturedMediaAdapter != null) capturedMediaAdapter.notifyDataSetChanged();
                    });
                }
            } catch (Exception e) {
                Log.d(TAG, "Thumbnail load failed: " + media.posterUrl, e);
                String youtubeFallback = getYouTubeThumbnailForCurrentPage();
                if (!youtubeFallback.isEmpty() && !youtubeFallback.equals(media.posterUrl)) {
                    media.posterUrl = youtubeFallback;
                    loadThumbnailAsync(media);
                }
            } finally {
                try { if (input != null) input.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void addCapturedPageLink(String pageUrl, String title) {
        if (pageUrl == null || pageUrl.trim().isEmpty()) return;
        synchronized (capturedList) {
            for (CapturedMedia existing : capturedList) {
                if (pageUrl.equals(existing.url)) return;
            }
            CapturedMedia page = new CapturedMedia(pageUrl,
                    title == null || title.trim().isEmpty() ? "صفحة YouTube" : title.trim(),
                    "🔗 رابط صفحة فقط - ليس رابط فيديو مباشر", getYouTubeThumbnailForCurrentPage(), 3);
            page.isSelected = false;
            capturedList.add(page);
            if (page.posterUrl != null && !page.posterUrl.isEmpty()) loadThumbnailAsync(page);
        }
        runOnUiThread(() -> {
            if (btnCast != null) {
                btnCast.setVisibility(View.VISIBLE);
                btnCast.setText("📺 " + capturedList.size());
            }
            if (capturedMediaAdapter != null) capturedMediaAdapter.notifyDataSetChanged();
        });
    }

    private WebView getCurrentWebView() {
        if (currentTabIndex >= 0 && currentTabIndex < tabList.size()) {
            return tabList.get(currentTabIndex);
        }
        return null;
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= tabList.size()) return;
        if (currentTabIndex != index) {
            activePlaybackKey = "";
            clearCapturedMedia();
        }
        currentTabIndex = index;
        activeWebView = tabList.get(index);
        String activeUrl = activeWebView.getUrl();
        activePageUrl = activeUrl == null ? "" : activeUrl;
        activeYouTubeWatchUrl = canonicalYouTubeWatchUrl(activeUrl);
        for (int i = 0; i < tabList.size(); i++) {
            tabList.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        WebView current = getCurrentWebView();
        if (current != null) {
            String u = current.getUrl();
            etUrl.setText((u == null || u.equals("about:home")) ? "" : u);
        }
        btnTabs.setContentDescription("علامات التبويب المفتوحة: " + tabList.size());
    }

    private boolean isBlockedAdUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String lowerValue = value.toLowerCase(Locale.ROOT);
        if (lowerValue.contains("/ddm/trackimp/") || lowerValue.contains("/ddm/trackclk/")
                || lowerValue.contains("/pagead/") || lowerValue.contains("/gampad/ads")) return true;
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            for (String domain : adDomains) {
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressLint("JavascriptInterface")
    private void injectPopupAndOverlayBlocker(WebView view) {
        if (view == null) return;
        // YouTube owns a highly dynamic player DOM. The generic overlay cleaner
        // can mistake native ytp player/ad nodes for third-party ad containers
        // and hide elements required by the HTML5 video surface. Keep all
        // generic popup/DOM cleanup away from YouTube; its native skip button
        // handling remains isolated in injectAdKiller().
        String pageUrl = view.getUrl();
        if (isYouTubePageUrl(pageUrl) || isYouTubePageUrl(activePageUrl)) return;
        String js = "(function(){try{" +
                "if(window.__aboPopupGuardInstalled)return;window.__aboPopupGuardInstalled=true;" +
                "var adHosts=" + new org.json.JSONArray(adDomains).toString() + ";" +
                "function isAd(u){try{var h=new URL(u,location.href).hostname.toLowerCase().replace(/^www\\./,'');" +
                "for(var i=0;i<adHosts.length;i++)if(h===adHosts[i]||h.endsWith('.'+adHosts[i]))return true;" +
                "return /(^|[.-])(popunder|popads|popcash|onclickads|trafficjunky)([.-]|$)/i.test(h);" +
                "}catch(e){return false;}}" +
                "var nativeOpen=window.open;window.open=function(u,n,f){" +
                "if(!u||isAd(String(u)))return null;" +
                "try{var dest=new URL(String(u),location.href);if(dest.protocol==='http:'||dest.protocol==='https:'){location.href=dest.href;return window;}}catch(e){}" +
                "return null;};" +
                "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[target]'):null;" +
                "if(!a)return;var target=(a.getAttribute('target')||'').toLowerCase();if(target==='_blank'){" +
                "var href=a.href||a.getAttribute('href')||'';if(isAd(href)){e.preventDefault();e.stopImmediatePropagation();return;}" +
                "if(/^https?:/i.test(href)){e.preventDefault();e.stopImmediatePropagation();location.href=href;}}},true);" +
                // Some legitimate player/server selectors submit target=_blank forms.
                // Route those user-triggered HTTP(S) forms into this tab while still
                // rejecting known ad destinations; native pop-up windows stay blocked.
                "document.addEventListener('submit',function(e){var f=e.target;if(!f||!f.getAttribute)return;" +
                "var t=(f.getAttribute('target')||'').toLowerCase();if(t!=='_blank')return;" +
                "if((f.method||'get').toLowerCase()!=='get')return;" +
                "var raw=f.getAttribute('action')||location.href;var dest;try{dest=new URL(raw,location.href);}catch(x){return;}" +
                "if(dest.protocol!=='http:'&&dest.protocol!=='https:')return;" +
                "var data;try{data=new FormData(f);data.forEach(function(v,k){if(typeof v==='string')dest.searchParams.append(k,v);});}catch(x){}" +
                "e.preventDefault();e.stopImmediatePropagation();if(isAd(dest.href))return;" +
                "location.href=dest.href;},true);" +
                "function clean(){try{var nodes=document.querySelectorAll('[class],[id]');for(var i=0;i<nodes.length;i++){" +
                "var n=nodes[i],s=((n.id||'')+' '+(typeof n.className==='string'?n.className:'')).toLowerCase();" +
                "if(/(^|[-_ ])(ad|ads|advert|advertisement|banner-ad|popup-ad|popunder)([-_ ]|$)/.test(s)){" +
                "if(n.tagName==='IFRAME'||n.tagName==='INS'||n.tagName==='DIV'||n.tagName==='ASIDE')n.style.setProperty('display','none','important');}}}catch(e){}}" +
                "clean();if(!window.__aboAdCleanTimer)window.__aboAdCleanTimer=setInterval(clean,1800);" +
                "}catch(e){}})();";
        view.evaluateJavascript(js, null);
    }

    private boolean isQFilmUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("qfilm.tv") || host.endsWith(".qfilm.tv");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String getQFilmCompatibleUserAgent() {
        String ua = WebSettings.getDefaultUserAgent(this);
        // The Android WebView UA can expose an explicit "; wv" marker that some
        // player sites reject. Keep the installed Chromium version, not a stale
        // hard-coded Chrome version, and remove only the WebView marker.
        return ua.replace("; wv", "").replace(" Version/4.0", "");
    }

    private boolean isFkLivePageUrl(String value) {
        if (value == null) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String path = uri.getPath();
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && path != null
                    && path.toLowerCase(Locale.ROOT).matches("^/[^/]+/[^/]+/index\\.html$");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void loadTargetUrl(String target) {
        WebView current = getCurrentWebView();
        if (current == null || target.isEmpty()) return;

        homePageBridgeActive = false;
        homeBridgeWebView = null;
        capturedList.clear();
        lastDiscoveredPoster = "";
        lastDiscoveredTitle = "";
        btnCast.setText("📺 0");
        btnCast.setVisibility(View.GONE);

        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            try {
                target = "https://www.google.com.eg/search?q=" + URLEncoder.encode(target, "UTF-8") + "&gl=eg&hl=ar";
            } catch (Exception e) {
                target = "https://www.google.com.eg/search?q=" + target + "&gl=eg&hl=ar";
            }
        }
        // Recognize live playlist pages with a three-segment /<type>/<id>/index.html path, regardless of provider hostname.
        // These URLs must be sent to Receiver/VLC instead of being loaded as ordinary web pages.
        if (isFkLivePageUrl(target)) {
            Toast.makeText(this, "تم التعرف على رابط البث، جاري إرساله إلى VLC…", Toast.LENGTH_SHORT).show();
            castToTv(target);
            return;
        }

        etUrl.setText(target);
        // Set TikTok's Android identity before its first document request.
        // All other sites retain their configured User-Agent behavior.
        if (isTikTokPageUrl(target)) {
            current.getSettings().setSupportMultipleWindows(false);
            current.getSettings().setUserAgentString(getTikTokCompatibleUserAgent());
        } else if (isQFilmUrl(target)) {
            current.getSettings().setSupportMultipleWindows(false);
            current.getSettings().setUserAgentString(getQFilmCompatibleUserAgent());
        } else if (isYouTubePageUrl(target)) {
            // Set the real device WebView Chromium UA before the first request.
            // Changing it only in onPageStarted is too late for YouTube's player bootstrap.
            current.getSettings().setSupportMultipleWindows(false);
            current.getSettings().setUserAgentString(getYouTubeCompatibleUserAgent());
        } else {
            current.getSettings().setSupportMultipleWindows(false);
            current.getSettings().setUserAgentString(getConfiguredUserAgent());
        }

        Map<String, String> extraHeaders = new HashMap<>();
        extraHeaders.put("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8");

        current.loadUrl(target, extraHeaders);
    }

    private void loadHomePage() {
        WebView current = getCurrentWebView();
        if (current == null) return;

        // Stop any webpage audio/video before returning to the browser home screen.
        // This prevents Facebook/YouTube/TikTok playback from continuing underneath
        // the social-site launcher page.
        try {
            current.evaluateJavascript(
                    "(function(){try{"
                    + "document.querySelectorAll('video,audio').forEach(function(m){"
                    + "try{m.pause();}catch(e){}"
                    + "try{m.currentTime=0;}catch(e){}"
                    + "try{m.removeAttribute('src');m.load();}catch(e){}"
                    + "});"
                    + "try{if(window.__aboTikTokLiveSniffer){window.__aboTikTokLiveSniffer=false;}}catch(e){}"
                    + "}catch(e){}})()", null);
        } catch (Exception ignored) {}

        capturedList.clear();
        btnCast.setText("📺 0");
        btnCast.setVisibility(View.GONE);
        etUrl.setText("");

        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        Set<String> customSites = prefs.getStringSet("custom_sites", new HashSet<>());

        StringBuilder customCards = new StringBuilder();
        if (customSites != null) {
            SharedPreferences sitePrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
            for (String entry : customSites) {
                String[] parts = entry.split("\\|", 2);
                if (parts.length == 2) {
                    String siteName = parts[0];
                    String siteUrl = parts[1];
                    String fallbackIcon = "https://www.google.com/s2/favicons?domain=" +
                            Uri.encode(Uri.parse(siteUrl).getHost() == null ? siteUrl : Uri.parse(siteUrl).getHost()) + "&sz=128";
                    String siteImage = sitePrefs.getString("custom_site_image_" + siteUrl, fallbackIcon);
                    customCards.append("<a href='").append(escapeHtml(siteUrl)).append("' class='card'>")
                               .append("<img src='").append(escapeHtml(siteImage)).append("' onerror=\"this.onerror=null;this.src='")
                               .append(escapeHtml(fallbackIcon)).append("'\" alt='")
                               .append(escapeHtml(siteName)).append("'>")
                               .append("<span>").append(escapeHtml(siteName)).append("</span>")
                               .append("</a>");
                }
            }
        }

        // شعارات SVG مدمجة ومباشرة لا تتأثر بالنت ولا تنكسر أبداً
        String homeHtml = "<html dir='rtl'><head><meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>" +
                "body{background:#121212;color:white;font-family:sans-serif;text-align:center;padding:15px;margin:0;}" +
                ".grid{display:grid;grid-template-columns:repeat(3, 1fr);gap:14px;margin-top:20px;}" +
                ".card{background:#1E1E1E;padding:12px 6px;border-radius:12px;text-decoration:none;color:white;font-size:12px;display:flex;flex-direction:column;align-items:center;justify-content:center;border:1px solid #2A2A2A;}" +
                ".card:active{transform:scale(0.95);background:#252525;}" +
                ".card img, .card svg{width:48px;height:48px;border-radius:10px;margin-bottom:8px;object-fit:contain;background:#fff;padding:3px;box-sizing:border-box;}" +
                ".card span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;width:100%;font-weight:bold;}" +
                ".icon-circle{width:42px;height:42px;border-radius:10px;display:flex;align-items:center;justify-content:center;font-size:22px;margin-bottom:8px;}" +
                ".add-btn{background:#00897B;padding:12px;border-radius:10px;color:white;display:block;margin-top:22px;text-decoration:none;font-weight:bold;cursor:pointer;border:1px solid #00A896;}" +
                ".search-box{width:92%;padding:12px;border-radius:25px;border:1px solid #333;margin-top:20px;font-size:14px;text-align:center;background:#2A2A2A;color:white;outline:none;}" +
                "</style></head><body>" +
                "<h3 style='color:#00B0FF;margin-top:15px;font-size:20px;'>Abo Ezzat Browser</h3>" +
                "<form action='https://www.google.com.eg/search' method='get'>" +
                "<input type='hidden' name='gl' value='eg'>" +
                "<input type='hidden' name='hl' value='ar'>" +
                "<input type='text' name='q' class='search-box' placeholder='ابحث في جوجل مصر...'>" +
                "</form>" +
                "<div class='grid'>" +

                // Social shortcuts use the websites' real favicon image files (not hand-drawn SVGs).
                "<a href='https://www.tiktok.com' class='card'>" +
                "<img src='https://www.google.com/s2/favicons?domain=www.tiktok.com&sz=128' alt='TikTok'>" +
                "<span>تيك توك</span></a>" +

                "<a href='https://www.facebook.com' class='card'>" +
                "<img src='https://www.google.com/s2/favicons?domain=www.facebook.com&sz=128' alt='Facebook'>" +
                "<span>فيسبوك</span></a>" +

                "<a href='https://www.instagram.com' class='card'>" +
                "<img src='https://www.google.com/s2/favicons?domain=www.instagram.com&sz=128' alt='Instagram'>" +
                "<span>إنستجرام</span></a>" +

                "<a href='https://m.youtube.com' class='card'>" +
                "<img src='https://www.google.com/s2/favicons?domain=www.youtube.com&sz=128' alt='YouTube'>" +
                "<span>يوتيوب</span></a>" +

                "<a href='https://www.threads.net' class='card'>" +
                "<img src='https://www.google.com/s2/favicons?domain=www.threads.net&sz=128' alt='Threads'>" +
                "<span>ثريدز</span></a>" +

                "<a href='https://x.com' class='card'>" +
                "<img src='https://www.google.com/s2/favicons?domain=x.com&sz=128' alt='X'>" +
                "<span>تويتر / X</span></a>" +

                customCards.toString() +
                "</div>" +

                "<div class='add-btn' onclick='promptAddSite()'>➕ إضافة موقع جديد للرئيسية</div>" +
                "<script>" +
                "function promptAddSite() {" +
                "  var n = prompt('اكتب اسم الموقع:');" +
                "  if(n) {" +
                "    var u = prompt('اكتب رابط الموقع:', 'https://');" +
                "    if(u) { window.AboEzzatBridge.onNewCustomSite(n, u); }" +
                "  }" +
                "}" +
                "</script></body></html>";

        homeBridgeWebView = current;
        homePageBridgeActive = true;
        current.loadDataWithBaseURL(INTERNAL_HOME_BASE_URL, homeHtml, "text/html", "UTF-8", null);
    }

    private boolean isInternalHomeBridgeAllowed(WebView source) {
        if (!homePageBridgeActive || source == null || source != homeBridgeWebView
                || source != getCurrentWebView()) return false;
        String currentUrl = source.getUrl();
        if (currentUrl == null || !currentUrl.startsWith(INTERNAL_HOME_BASE_URL)) return false;
        Uri uri = Uri.parse(currentUrl);
        return "https".equalsIgnoreCase(uri.getScheme())
                && "abo-ezzat-home.invalid".equalsIgnoreCase(uri.getHost())
                && "/home/".equals(uri.getPath());
    }

    private void saveCustomSite(String name, String url) {
        if (name == null || url == null) return;
        name = name.trim();
        url = url.trim();
        if (name.isEmpty() || name.length() > 80 || name.indexOf('|') >= 0
                || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0
                || url.isEmpty() || url.length() > 2048) return;
        Uri parsed;
        try { parsed = Uri.parse(url); } catch (Exception ignored) { return; }
        String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || parsed.getHost() == null || parsed.getHost().trim().isEmpty()
                || parsed.getUserInfo() != null) return;
        final String siteName = name;
        final String siteUrl = url;
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        Set<String> set = new HashSet<>(prefs.getStringSet("custom_sites", new HashSet<>()));
        set.removeIf(entry -> {
            String[] parts = entry.split("\\|", 2);
            return parts.length == 2 && parts[1].equalsIgnoreCase(siteUrl);
        });
        if (set.size() >= 100 && !set.contains(siteName + "|" + siteUrl)) {
            Toast.makeText(this, "وصلت للحد الأقصى للمواقع المحفوظة (100)", Toast.LENGTH_SHORT).show();
            return;
        }
        set.add(siteName + "|" + siteUrl);
        prefs.edit().putStringSet("custom_sites", set).apply();
        fetchCustomSitePreview(siteName, siteUrl);
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("'", "&#39;")
                .replace("\\", "&#92;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void fetchCustomSitePreview(final String siteName, final String siteUrl) {
        AsyncTask.execute(() -> {
            // Preview fetching is a background native request: never let a saved URL
            // redirect this request into localhost, a private LAN, or device services.
            if (!isSafePublicHttpUrl(siteUrl)) {
                Log.w(TAG, "Skipped custom-site preview for a non-public host");
                return;
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(siteUrl).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36");
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
                connection.connect();
                String contentType = connection.getContentType();
                if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("html")) return;
                InputStream input = connection.getInputStream();
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1 && total < 512 * 1024) {
                    int take = Math.min(count, 512 * 1024 - total);
                    output.write(buffer, 0, take);
                    total += take;
                }
                input.close();
                String html = output.toString("UTF-8");
                String image = extractSiteMeta(html, "og:image");
                if (image.isEmpty()) image = extractSiteMeta(html, "twitter:image");
                if (image.isEmpty()) image = extractSiteMeta(html, "twitter:image:src");
                if (image.isEmpty()) {
                    java.util.regex.Matcher poster = java.util.regex.Pattern.compile(
                            "(?is)<video[^>]+poster\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]").matcher(html);
                    if (poster.find()) image = poster.group(1);
                }
                if (image.isEmpty()) {
                    java.util.regex.Matcher icon = java.util.regex.Pattern.compile(
                            "(?is)<link[^>]+rel\\s*=\\s*['\\\"][^'\\\"]*icon[^'\\\"]*['\\\"][^>]+href\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]").matcher(html);
                    if (icon.find()) image = icon.group(1);
                }
                if (!image.isEmpty()) image = new URL(new URL(siteUrl), image).toString();
                else {
                    String host = new URL(siteUrl).getHost();
                    image = "https://www.google.com/s2/favicons?domain=" + Uri.encode(host) + "&sz=128";
                }
                SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
                prefs.edit().putString("custom_site_image_" + siteUrl, image)
                        .putString("custom_site_title_" + siteUrl, siteName).apply();
                runOnUiThread(() -> {
                    WebView current = getCurrentWebView();
                    if (current != null && "about:home".equals(current.getUrl())) loadHomePage();
                });
            } catch (Exception ignored) {
                try {
                    String host = new URL(siteUrl).getHost();
                    String fallback = "https://www.google.com/s2/favicons?domain=" + Uri.encode(host) + "&sz=128";
                    getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE).edit()
                            .putString("custom_site_image_" + siteUrl, fallback).apply();
                } catch (Exception ignoredAgain) {}
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private String extractSiteMeta(String html, String key) {
        if (html == null || key == null) return "";
        String escaped = java.util.regex.Pattern.quote(key);
        java.util.regex.Pattern first = java.util.regex.Pattern.compile(
                "(?is)<meta[^>]*(?:property|name)\\s*=\\s*['\\\"]" + escaped +
                "['\\\"][^>]*content\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]*>");
        java.util.regex.Matcher match = first.matcher(html);
        if (match.find()) return match.group(1).replace("&amp;", "&").trim();
        java.util.regex.Pattern second = java.util.regex.Pattern.compile(
                "(?is)<meta[^>]*content\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]*(?:property|name)\\s*=\\s*['\\\"]" + escaped + "['\\\"][^>]*>");
        match = second.matcher(html);
        return match.find() ? match.group(1).replace("&amp;", "&").trim() : "";
    }

    private void triggerManualSniff() {
        final WebView current = getCurrentWebView();
        if (current == null) return;

        // YouTube must be handled through its canonical watch page and NewPipe,
        // never by collecting its temporary SABR googlevideo transport requests.
        String youtubePage = getCurrentYouTubePageUrl();
        if (youtubePage != null) {
            extractYouTubeForPage(youtubePage, false);
            Toast.makeText(this, "جاري استخراج الفيديو من صفحة YouTube الأصلية...", Toast.LENGTH_SHORT).show();
            return;
        }

        // Arm only the generic manual-capture path for 45 seconds. This does not
        // modify share-sheet dispatch or the platform-specific automatic sniffers.
        manualSniffUntilMs = System.currentTimeMillis() + 45000L;
        clearCapturedMedia();
        Toast.makeText(this, "جاري فحص مشغل الموقع والتقاط مسارات الفيديو والصوت...", Toast.LENGTH_LONG).show();

        String js = "(function(){try{if(window.__aboArmActiveCapture)window.__aboArmActiveCapture();}" +
                "catch(e){}})()";
        current.evaluateJavascript(js, null);
    }

    private void showMediaListDialog() {
        if (capturedList.isEmpty()) {
            String youtubePage = getCurrentYouTubePageUrl();
            if (youtubePage != null) {
                extractYouTubeForPage(youtubePage, false);
                Toast.makeText(this, "جاري استخراج رابط الفيديو المباشر من YouTube...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "لا توجد فيديوهات مكتشفة، شغل الفيديو أولاً ثم اضغط 🎯", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        final Dialog dialog = new Dialog(this, R.style.ModernDialogTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(buildMediaListView(dialog));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private View buildMediaListView(final Dialog dialog) {
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(Color.parseColor("#181818"));
        main.setPadding(20, 20, 20, 20);

        TextView header = new TextView(this);
        header.setText("المسارات المكتشفة (" + capturedList.size() + ") - محددة تلقائياً");
        header.setTextColor(Color.WHITE);
        header.setTextSize(16);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, 10, 0, 15);
        main.addView(header);

        ListView listView = new ListView(this);
        listView.setDivider(new ColorDrawable(Color.parseColor("#2A2A2A")));
        listView.setDividerHeight(2);

        capturedMediaAdapter = new BaseAdapter() {
            @Override
            public int getCount() { return capturedList.size(); }

            @Override
            public Object getItem(int position) { return capturedList.get(position); }

            @Override
            public long getItemId(int position) { return position; }

            @Override
            public View getView(final int position, View convertView, ViewGroup parent) {
                final CapturedMedia item = capturedList.get(position);

                LinearLayout row = new LinearLayout(BrowserActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(10, 14, 10, 14);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundColor(Color.parseColor("#1F1F1F"));

                CheckBox cb = new CheckBox(BrowserActivity.this);
                cb.setChecked(item.isSelected);
                cb.setOnCheckedChangeListener((buttonView, isChecked) -> item.isSelected = isChecked);
                row.addView(cb);

                LinearLayout infoLayout = new LinearLayout(BrowserActivity.this);
                infoLayout.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                infoParams.setMargins(8, 0, 10, 0);
                infoLayout.setLayoutParams(infoParams);

                TextView tvTitle = new TextView(BrowserActivity.this);
                tvTitle.setText(item.title);
                tvTitle.setTextColor(Color.WHITE);
                tvTitle.setTextSize(13);
                tvTitle.setMaxLines(2);
                tvTitle.setEllipsize(TextUtils.TruncateAt.END);
                infoLayout.addView(tvTitle);

                TextView tvFormat = new TextView(BrowserActivity.this);
                tvFormat.setText(item.format);
                int color = Color.GRAY;
                if (item.mediaType == 1) color = Color.parseColor("#4CAF50");
                else if (item.mediaType == 2) color = Color.parseColor("#FF9800");
                tvFormat.setTextColor(color);
                tvFormat.setTextSize(12);
                tvFormat.setPadding(0, 4, 0, 0);
                infoLayout.addView(tvFormat);

                TextView tvUrl = new TextView(BrowserActivity.this);
                tvUrl.setText((item.mediaType == 2 ? "رابط مسار الصوت: " : item.mediaType == 3
                        ? "رابط صفحة فقط: " : "رابط الفيديو: ") + compactCapturedUrl(item.url));
                tvUrl.setTextColor(Color.parseColor("#90CAF9"));
                tvUrl.setTextSize(10);
                tvUrl.setMaxLines(2);
                tvUrl.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                tvUrl.setTextDirection(View.TEXT_DIRECTION_LTR);
                tvUrl.setOnClickListener(v -> showCapturedUrlDialog(item));
                infoLayout.addView(tvUrl);

                row.addView(infoLayout);

                ImageView thumb = new ImageView(BrowserActivity.this);
                LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(120, 100);
                thumb.setLayoutParams(thumbParams);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);

                if (item.posterBitmap != null) {
                    thumb.setImageBitmap(item.posterBitmap);
                } else {
                    thumb.setBackgroundColor(Color.parseColor("#2C2C2C"));
                    thumb.setImageResource(android.R.drawable.ic_menu_report_image);
                    if ((item.posterUrl == null || item.posterUrl.isEmpty()) && item.mediaType != 2) {
                        String fallbackPoster = getYouTubeThumbnailForCurrentPage();
                        if (!fallbackPoster.isEmpty()) {
                            item.posterUrl = fallbackPoster;
                            loadThumbnailAsync(item);
                        }
                    }
                }
                row.addView(thumb);

                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    showMediaOptionsDialog(item);
                });

                return row;
            }
        };
        listView.setAdapter(capturedMediaAdapter);

        main.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 650));

        Button btnCastNow = new Button(this);
        btnCastNow.setText("📺 بث فوري على الشاشة (دمج وتشغيل)");
        btnCastNow.setTextColor(Color.BLACK);
        btnCastNow.setBackgroundColor(Color.parseColor("#FFD700"));
        btnCastNow.setTextSize(14);
        btnCastNow.setOnClickListener(v -> {
            dialog.dismiss();
            castSelectedTracksManually();
        });
        LinearLayout.LayoutParams btnCastParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnCastParams.setMargins(0, 10, 0, 5);
        main.addView(btnCastNow, btnCastParams);

        LinearLayout dlRow = new LinearLayout(this);
        dlRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dlRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlRowParams.setMargins(0, 5, 0, 5);
        dlRow.setLayoutParams(dlRowParams);

        Button btnDlVideo = new Button(this);
        btnDlVideo.setText("🎬 تحميل الفيديو");
        btnDlVideo.setTextColor(Color.WHITE);
        btnDlVideo.setBackgroundColor(Color.parseColor("#2E7D32"));
        btnDlVideo.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        btnDlVideo.setOnClickListener(v -> {
            dialog.dismiss();
            downloadSelectedMedia(false);
        });
        dlRow.addView(btnDlVideo);

        View spacer = new View(this);
        dlRow.addView(spacer, new LinearLayout.LayoutParams(10, ViewGroup.LayoutParams.MATCH_PARENT));

        Button btnDlAudio = new Button(this);
        btnDlAudio.setText("🎵 تحميل الصوت");
        btnDlAudio.setTextColor(Color.WHITE);
        btnDlAudio.setBackgroundColor(Color.parseColor("#E65100"));
        btnDlAudio.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        btnDlAudio.setOnClickListener(v -> {
            dialog.dismiss();
            downloadSelectedMedia(true);
        });
        dlRow.addView(btnDlAudio);

        main.addView(dlRow);

        Button btnMergeDownload = new Button(this);
        btnMergeDownload.setText("🎬🎵 تنزيل الفيديو مع الصوت ودمجهما");
        btnMergeDownload.setAllCaps(false);
        btnMergeDownload.setTextColor(Color.WHITE);
        btnMergeDownload.setBackgroundColor(Color.parseColor("#1565C0"));
        btnMergeDownload.setOnClickListener(v -> {
            dialog.dismiss();
            mergeSelectedMediaForDownload();
        });
        LinearLayout.LayoutParams mergeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mergeParams.setMargins(0, dp(5), 0, dp(5));
        main.addView(btnMergeDownload, mergeParams);

        Button btnClear = new Button(this);
        btnClear.setText("مسح القائمة");
        btnClear.setTextColor(Color.LTGRAY);
        btnClear.setBackgroundColor(Color.parseColor("#2A2A2A"));
        btnClear.setOnClickListener(v -> {
            capturedList.clear();
            btnCast.setText("📺 0");
            btnCast.setVisibility(View.GONE);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams btnClearParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnClearParams.setMargins(0, 5, 0, 0);
        main.addView(btnClear, btnClearParams);

        return main;
    }

    private boolean isCapturedAudioTrack(CapturedMedia item) {
        if (item == null) return false;
        String label = item.format == null ? "" : item.format.toLowerCase(java.util.Locale.ROOT);
        return item.mediaType == 2 || item.isAudio || label.contains("audio")
                || label.contains("مسار صوت") || label.contains("صوت فقط");
    }

    private void downloadSelectedMedia(boolean audioOnly) {
        CapturedMedia bestVideo = null;
        CapturedMedia selectedAudio = null;
        int audioTrackCount = 0;
        CapturedMedia onlyAudioTrack = null;

        synchronized (capturedList) {
            for (CapturedMedia item : capturedList) {
                if (isCapturedAudioTrack(item)) {
                    audioTrackCount++;
                    onlyAudioTrack = item;
                }
                if (!item.isSelected) continue;
                if (isCapturedAudioTrack(item)) {
                    if (selectedAudio == null || capturedUrlConfidence(item.url, 2)
                            > capturedUrlConfidence(selectedAudio.url, 2)) selectedAudio = item;
                } else if (item.mediaType == 1 && (bestVideo == null
                        || capturedVideoQualityScore(item.url) > capturedVideoQualityScore(bestVideo.url))) {
                    bestVideo = item;
                }
            }
        }

        if (audioOnly) {
            if (selectedAudio == null && audioTrackCount == 1) selectedAudio = onlyAudioTrack;
            if (selectedAudio != null) {
                downloadMedia(selectedAudio.url, true, "audio/mp4");
                return;
            }
            if (bestVideo != null && bestVideo.pairedAudioUrl != null) {
                downloadMedia(bestVideo.pairedAudioUrl, true, "audio/mp4");
                return;
            }
            Toast.makeText(this, "لا يوجد مسار صوت منفصل محدد", Toast.LENGTH_LONG).show();
            return;
        }

        // YouTube's highest-quality rendition is often video-only; combine it
        // with the separately extracted audio instead of saving a silent MP4.
        if (bestVideo == null) {
            Toast.makeText(this, "حدد مسار الفيديو فقط من القائمة أولاً", Toast.LENGTH_LONG).show();
            return;
        }
        String pairedAudio = selectedAudio != null ? selectedAudio.url : bestVideo.pairedAudioUrl;
        if (getCurrentYouTubePageUrl() != null && pairedAudio != null) {
            WebView current = getCurrentWebView();
            String referer = current == null || current.getUrl() == null
                    ? "https://www.youtube.com/" : current.getUrl();
            BrowserFileDownloader.startMux(this, bestVideo.url, pairedAudio,
                    referer, bestVideo.title, lastDiscoveredPoster);
            return;
        }
        downloadMedia(bestVideo.url, false, "video/mp4");
    }

    private void mergeSelectedMediaForDownload() {
        CapturedMedia bestVideo = null;
        CapturedMedia bestAudio = null;
        synchronized (capturedList) {
            for (CapturedMedia item : capturedList) {
                if (!item.isSelected) continue;
                if (isCapturedAudioTrack(item)) {
                    if (bestAudio == null || capturedUrlConfidence(item.url, 2)
                            > capturedUrlConfidence(bestAudio.url, 2)) bestAudio = item;
                } else if (item.mediaType == 1 && (bestVideo == null
                        || capturedVideoQualityScore(item.url) > capturedVideoQualityScore(bestVideo.url))) {
                    bestVideo = item;
                }
            }
        }
        if (bestVideo == null || bestAudio == null) {
            Toast.makeText(this, "حدد مسار فيديو ومسار صوت منفصلين لعملية الدمج", Toast.LENGTH_LONG).show();
            return;
        }
        WebView current = getCurrentWebView();
        String referer = current == null || current.getUrl() == null ? "" : current.getUrl();
        BrowserFileDownloader.startMux(this, bestVideo.url, bestAudio.url, referer, bestVideo.title, lastDiscoveredPoster);
    }

    private void castSelectedTracksManually() {
        mergeAndCastToTv();
    }

    private void showMediaOptionsDialog(final CapturedMedia media) {
        String[] options = {
                "🔗 دمج وبث بصوت وصورة (Merge & Cast)",
                "📺 بث هذا المسار منفرداً (Cast Single Track)",
                "🎬 تحميل الفيديو (MP4)",
                "🎵 تحميل الصوت فقط (Clean Audio)"
        };

        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle(media.title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        mergeAndCastToTv();
                    } else if (which == 1) {
                        castToTv(media.url, media.pairedAudioUrl, media.requestHeaders);
                    } else if (which == 2) {
                        // This row action must download this exact media URL.
                        // The bulk-download button below is the only action that
                        // uses the multi-selection/highest-quality chooser.
                        if (media.mediaType == 1 && !isCapturedAudioTrack(media)) {
                            downloadMedia(media.url, false, "video/mp4");
                        } else {
                            Toast.makeText(this, "العنصر المحدد ليس مسار فيديو", Toast.LENGTH_LONG).show();
                        }
                    } else if (which == 3) {
                        if (isCapturedAudioTrack(media)) {
                            downloadMedia(media.url, true, "audio/mp4");
                        } else {
                            downloadSelectedMedia(true);
                        }
                    }
                })
                .show();
    }

    private Request buildReceiverPlayRequest(String endpoint, String videoUrl, String audioUrl) throws Exception {
        return buildReceiverPlayRequest(endpoint, videoUrl, audioUrl, null);
    }

    private Request buildReceiverPlayRequest(String endpoint, String videoUrl, String audioUrl,
                                             java.util.Map<String, String> capturedHeaders) throws Exception {
        JSONObject payload = new JSONObject();
        if (isBlockedAdUrl(videoUrl) || isBlockedAdUrl(audioUrl)) {
            throw new IllegalArgumentException("تم استبعاد رابط إعلاني");
        }
        if (videoUrl != null && !videoUrl.trim().isEmpty()) payload.put("url", videoUrl);
        if (audioUrl != null && !audioUrl.trim().isEmpty()) payload.put("audio", audioUrl);
        JSONObject headers = new JSONObject();
        String youtubePage = getCurrentYouTubePageUrl();
        // Preserve the original TikTok watch page with its signed CDN URL so the
        // Receiver can resolve a fresh stream instead of replaying a detached Akamai URL.
        String tiktokSourcePage = null;
        if (youtubePage == null && isTikTokCdnVideoUrl(videoUrl) && isTikTokPageUrl(activePageUrl)) {
            tiktokSourcePage = activePageUrl;
        }
        // The extracted googlevideo URLs were resolved with NewPipe's Firefox
        // fallback UA. The Receiver must use the same UA for both video and
        // companion audio requests; sending the unrelated WebView UA can yield
        // HTTP 403 even though the extracted URLs are valid.
        headers.put("user-agent", youtubePage != null
                ? YoutubeDirectExtractor.getStreamUserAgent()
                : WebSettings.getDefaultUserAgent(this));
        headers.put("accept-language", "ar-EG,ar;q=0.9,en-US;q=0.8");
        // TikTok signed CDN URLs must be replayed with the browser request context
        // that fetched the resource, not a newly invented generic Referer.
        if (isTikTokCdnVideoUrl(videoUrl) && capturedHeaders != null) {
            for (java.util.Map.Entry<String, String> entry : capturedHeaders.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null || value.isEmpty()) continue;
                String lower = key.toLowerCase(Locale.ROOT);
                if (("user-agent".equals(lower) || "referer".equals(lower) || "origin".equals(lower)
                        || "accept".equals(lower) || "accept-language".equals(lower) || "cookie".equals(lower)
                        || "sec-fetch-site".equals(lower) || "sec-fetch-mode".equals(lower)
                        || "sec-fetch-dest".equals(lower) || "sec-ch-ua".equals(lower)
                        || "sec-ch-ua-mobile".equals(lower) || "sec-ch-ua-platform".equals(lower)
                        || "priority".equals(lower))
                        && value.length() <= ("cookie".equals(lower) ? 8192 : 2048)
                        && !value.contains("\r") && !value.contains("\n")) {
                    headers.put(lower, value);
                }
            }
        }
        WebView current = getCurrentWebView();
        String referer = youtubePage;
        if (referer == null && current != null) referer = current.getUrl();
        if (referer != null && (referer.startsWith("http://") || referer.startsWith("https://"))) {
            // Preserve the exact TikTok media-request Referer/Origin captured from WebView.
            // The current page URL is only a fallback for sources without captured headers.
            if (!isTikTokCdnVideoUrl(videoUrl) || !headers.has("referer")) {
                headers.put("referer", referer);
            }
            if (!isTikTokCdnVideoUrl(videoUrl) || !headers.has("origin")) {
                try {
                    Uri refUri = Uri.parse(referer);
                    headers.put("origin", refUri.getScheme() + "://" + refUri.getHost());
                } catch (Exception ignored) {}
            }
        }
        // TikTok's page-scoped session cookie may not be attached to the CDN host.
        // Forward the WebView page cookie as a local Receiver request header, without logging it.
        if (tiktokSourcePage != null && !headers.has("cookie")) {
            try {
                String pageCookie = CookieManager.getInstance().getCookie(tiktokSourcePage);
                if (pageCookie != null && !pageCookie.isEmpty() && pageCookie.length() <= 8192
                        && !pageCookie.contains("\r") && !pageCookie.contains("\n")) {
                    headers.put("cookie", pageCookie);
                }
            } catch (Exception ignored) {}
        }
        if (youtubePage != null) payload.put("sourceUrl", youtubePage);
        else if (tiktokSourcePage != null) payload.put("sourceUrl", tiktokSourcePage);
        payload.put("headers", headers);
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"), payload.toString());
        return new Request.Builder().url(endpoint + "/play").post(body).build();
    }

    private void mergeAndCastToTv() {
        refreshSelectedReceiverIp();
        String youtubePage = getCurrentYouTubePageUrl();
        // YouTube's browser resource list can contain ad/SABR transport requests.
        // Always use NewPipe's verified video+audio pair for the current watch page;
        // never merge arbitrary checked rows from the same page.
        if (youtubePage != null) {
            extractYouTubeForPage(youtubePage, true);
            return;
        }
        String videoTrackUrl = "";
        String audioTrackUrl = "";
        CapturedMedia bestVideo = null;
        CapturedMedia bestAudio = null;

        // Only checked rows participate. Previously an unchecked audio row could
        // still be selected as bestAudio, causing the main Cast button to send an
        // unintended pair and fail while Cast Single Track worked.
        synchronized (capturedList) {
            for (CapturedMedia item : capturedList) {
                if (!item.isSelected || isBlockedAdUrl(item.url)) continue;
                if (item.mediaType == 1 && (bestVideo == null
                        || capturedVideoQualityScore(item.url) > capturedVideoQualityScore(bestVideo.url))) {
                    bestVideo = item;
                } else if (item.mediaType == 2 && (bestAudio == null
                        || capturedUrlConfidence(item.url, 2) > capturedUrlConfidence(bestAudio.url, 2))) {
                    bestAudio = item;
                }
            }
        }

        if (bestVideo != null) videoTrackUrl = bestVideo.url;
        if (bestAudio != null) audioTrackUrl = bestAudio.url;

        // If the current YouTube page has not produced a valid captured row yet,
        // invoke NewPipe. Otherwise send the already verified selected stream.
        if (videoTrackUrl.isEmpty() && youtubePage != null) {
            extractYouTubeForPage(youtubePage, true);
            return;
        }
        if (videoTrackUrl.isEmpty()) {
            Toast.makeText(this, "حدد مسار فيديو صالحاً للبث أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        // NewPipe exposes its matched audio as a companion field on the video row,
        // not as a second checkbox. Keep that verified pair together.
        if (audioTrackUrl.isEmpty() && bestVideo != null
                && bestVideo.pairedAudioUrl != null && !bestVideo.pairedAudioUrl.trim().isEmpty()
                && !isBlockedAdUrl(bestVideo.pairedAudioUrl)) {
            audioTrackUrl = bestVideo.pairedAudioUrl;
        }

        if (audioTrackUrl.isEmpty()) {
            castToTv(videoTrackUrl, null, bestVideo == null ? null : bestVideo.requestHeaders);
            return;
        }

        if (tvIp.isEmpty()) {
            Toast.makeText(this, "يرجى تعيين IP الشاشة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        

        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                endpoint = "http://" + endpoint;
            }
            if (!endpoint.contains(":8081")) {
                endpoint = endpoint + ":8081";
            }

            Request req = buildReceiverPlayRequest(endpoint, videoTrackUrl, audioTrackUrl,
                    bestVideo == null ? null : bestVideo.requestHeaders);
            final String receiverEndpoint = endpoint;
            AtomicBoolean requestHeadersSent = new AtomicBoolean(false);
            OkHttpClient trackedClient = client.newBuilder()
                    .eventListener(new okhttp3.EventListener() {
                        @Override
                        public void requestHeadersEnd(Call call, Request request) {
                            requestHeadersSent.set(true);
                        }
                    }).build();

            trackedClient.newCall(req).enqueue(new Callback() {
                private void finishSend() {
                    runOnUiThread(() -> {
                        if (requestHeadersSent.get()) {
                            Toast.makeText(BrowserActivity.this, "✅ تم الإرسال", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onFailure(Call call, IOException error) {
                    finishSend();
                }

                @Override
                public void onResponse(Call call, Response response) {
                    response.close();
                    finishSend();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "خطأ في الرابط", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isYouTubePageUrl(String page) {
        if (page == null || page.trim().isEmpty()) return false;
        try {
            String host = Uri.parse(page.trim()).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("youtu.be") || host.endsWith(".youtu.be")
                    || host.equals("youtube.com") || host.endsWith(".youtube.com")
                    || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com");
        } catch (Exception ignored) { return false; }
    }

    private String getYouTubeCompatibleUserAgent() {
        String ua = WebSettings.getDefaultUserAgent(this);
        if (ua == null) return "";
        return ua.replace("; wv)", ")").replace(" Version/4.0", "");
    }

    private String canonicalYouTubeWatchUrl(String page) {
        if (!isYouTubePageUrl(page)) return null;
        try {
            Uri uri = Uri.parse(page.trim());
            String videoId = uri.getQueryParameter("v");
            if (videoId == null || videoId.isEmpty()) {
                List<String> segments = uri.getPathSegments();
                if (segments != null && !segments.isEmpty()) {
                    String last = segments.get(segments.size() - 1);
                    String path = uri.getPath() == null ? "" : uri.getPath();
                    if (path.matches("(?i).*/(?:shorts|live|embed|v)/[^/]+/?$")
                            || "youtu.be".equalsIgnoreCase(uri.getHost())) videoId = last;
                }
            }
            if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return null;
            return "https://www.youtube.com/watch?v=" + videoId;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getCurrentYouTubePageUrl() {
        String canonical = activeYouTubeWatchUrl;
        if (canonical != null && !canonical.isEmpty()) return canonical;
        return canonicalYouTubeWatchUrl(activePageUrl);
    }

    private void extractYouTubeForPage(String pageUrl, boolean castAfterExtraction) {
        if (!isYouTubePageUrl(pageUrl)) return;
        synchronized (this) {
            if (castAfterExtraction) {
                youtubeCastPendingFor = pageUrl;
                // YouTube media URLs are signed and short-lived; refresh them on every cast request.
                if (pageUrl.equals(youtubeExtractedFor)) youtubeExtractedFor = "";
            }
            if (pageUrl.equals(youtubeExtractedFor)) {
                if (castAfterExtraction) {
                    String video = null, audio = null;
                    synchronized (capturedList) {
                        for (CapturedMedia item : capturedList) {
                            if (item.mediaType == 1) {
                                video = item.url;
                                audio = item.pairedAudioUrl;
                            } else if (item.mediaType == 2 && audio == null) {
                                // Compatibility with older captures.
                                audio = item.url;
                            }
                        }
                    }
                    if (video != null) castToTv(video, audio);
                }
                return;
            }
            if (pageUrl.equals(youtubeExtractionInProgressFor)) return;
            youtubeExtractionInProgressFor = pageUrl;
        }
        YoutubeDirectExtractor.extract(pageUrl, new YoutubeDirectExtractor.Callback() {
            @Override public void onSuccess(String videoUrl, String audioUrl, String title, String quality) {
                boolean shouldCast;
                synchronized (BrowserActivity.this) {
                    // Ignore a slow result if the user has already opened another video.
                    if (!pageUrl.equals(activeYouTubeWatchUrl)) {
                        if (pageUrl.equals(youtubeExtractionInProgressFor)) youtubeExtractionInProgressFor = "";
                        return;
                    }
                    if (pageUrl.equals(youtubeExtractionInProgressFor)) youtubeExtractionInProgressFor = "";
                    youtubeExtractedFor = pageUrl;
                    shouldCast = pageUrl.equals(youtubeCastPendingFor);
                    if (shouldCast) youtubeCastPendingFor = "";
                    activePlaybackKey = "youtube:" + pageUrl;
                }
                String poster = lastDiscoveredPoster;
                clearCapturedMedia();
                String safeTitle = title == null || title.trim().isEmpty() ? "YouTube video" : title.trim();
                // One visible YouTube capture; the selected Arabic/original audio
                // URL remains paired with the video for casting and muxed download.
                addCapturedMedia(videoUrl, safeTitle,
                        "🎬 رابط YouTube مباشر " + quality
                                + (audioUrl != null && !audioUrl.trim().isEmpty()
                                ? " • الصوت المفضل مرفق" : ""), poster, 1);
                synchronized (capturedList) {
                    for (CapturedMedia item : capturedList) {
                        if (item.mediaType == 1 && videoUrl.equals(item.url)) {
                            item.pairedAudioUrl = (audioUrl == null || audioUrl.trim().isEmpty())
                                    ? null : audioUrl;
                            break;
                        }
                    }
                }
                if (shouldCast) castToTv(videoUrl, audioUrl);
                runOnUiThread(() -> Toast.makeText(BrowserActivity.this,
                        "تم استخراج رابط YouTube المباشر " + quality, Toast.LENGTH_SHORT).show());
            }

            @Override public void onError(String reason) {
                boolean shouldReport;
                synchronized (BrowserActivity.this) {
                    // A failure for an older page must never replace/cast over the new video.
                    if (!pageUrl.equals(activeYouTubeWatchUrl)) {
                        if (pageUrl.equals(youtubeExtractionInProgressFor)) youtubeExtractionInProgressFor = "";
                        if (pageUrl.equals(youtubeCastPendingFor)) youtubeCastPendingFor = "";
                        return;
                    }
                    if (pageUrl.equals(youtubeExtractionInProgressFor)) youtubeExtractionInProgressFor = "";
                    shouldReport = castAfterExtraction || pageUrl.equals(youtubeCastPendingFor);
                    if (pageUrl.equals(youtubeCastPendingFor)) youtubeCastPendingFor = "";
                }
                if (!shouldReport) return;

                // Do not send potentially stale/mismatched captured googlevideo tracks.
                // If sender-side direct extraction fails, send the original YouTube page
                // to Receiver; its YouTube-only path opens SmartTube without an app chooser.
                runOnUiThread(() -> {
                    Toast.makeText(BrowserActivity.this,
                            "تعذر استخراج رابط فيديو أو بث مباشر قابل للتشغيل (" + reason + "). لم يتم إرسال صفحة YouTube إلى الشاشة.",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void refreshSelectedReceiverIp() {
        SharedPreferences selectedPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        tvIp = selectedPrefs.getString("tv_ip", "").trim();
    }

    private void castToTv(String mediaUrl) { castToTv(mediaUrl, null, null); }

    private void castToTv(String mediaUrl, String audioUrl) { castToTv(mediaUrl, audioUrl, null); }

    private void castToTv(String mediaUrl, String audioUrl, java.util.Map<String, String> capturedHeaders) {
        // BrowserActivity may stay alive while the user changes the selected receiver
        // from the home screen. Refresh the saved target for every actual cast.
        refreshSelectedReceiverIp();
        if (isBlockedAdUrl(mediaUrl) || isBlockedAdUrl(audioUrl)) {
            Toast.makeText(this, "تم استبعاد رابط إعلاني ولا يمكن تشغيله", Toast.LENGTH_SHORT).show();
            return;
        }
        if (tvIp.isEmpty()) {
            Toast.makeText(this, "يرجى تعيين IP الشاشة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                endpoint = "http://" + endpoint;
            }
            if (!endpoint.contains(":8081")) {
                endpoint = endpoint + ":8081";
            }

            Request req = buildReceiverPlayRequest(endpoint, mediaUrl, audioUrl, capturedHeaders);
            final String receiverEndpoint = endpoint;
            AtomicBoolean requestHeadersSent = new AtomicBoolean(false);
            OkHttpClient trackedClient = client.newBuilder()
                    .eventListener(new okhttp3.EventListener() {
                        @Override
                        public void requestHeadersEnd(Call call, Request request) {
                            requestHeadersSent.set(true);
                        }
                    }).build();

            trackedClient.newCall(req).enqueue(new Callback() {
                private void finishSend() {
                    runOnUiThread(() -> {
                        if (requestHeadersSent.get()) {
                            Toast.makeText(BrowserActivity.this, "✅ تم الإرسال", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                @Override
                public void onFailure(Call call, IOException error) {
                    finishSend();
                }

                @Override
                public void onResponse(Call call, Response response) {
                    response.close();
                    finishSend();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "خطأ في الرابط", Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadMedia(String mediaUrl, boolean audioOnly) {
        downloadMedia(mediaUrl, audioOnly, null);
    }

    private void downloadMedia(String mediaUrl, boolean audioOnly, String hintedMime) {
        if (mediaUrl == null || mediaUrl.trim().isEmpty() || isBlockedAdUrl(mediaUrl)) {
            Toast.makeText(this, "رابط التنزيل غير صالح", Toast.LENGTH_SHORT).show();
            return;
        }
        String cleanUrl = mediaUrl;
        if (cleanUrl.contains("&bytestart=")) {
            cleanUrl = cleanUrl.replaceAll("&bytestart=\\d+", "").replaceAll("&byteend=\\d+", "");
        }
        String lower = cleanUrl.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains(".mpd")
                || lower.matches(".*\\.(ts|m4s)(?:[?#].*)?$")
                || lower.contains("manifest(format=m3u8") || lower.contains("manifest(format=mpd")) {
            Toast.makeText(this,
                    "ده مسار بث متكيف أو مقطع بث، مش ملف فيديو كامل. مش هنحفظه كـ MP4 ناقص أو تالف.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String path = Uri.parse(cleanUrl).getPath();
        String ext = "";
        if (path != null && path.lastIndexOf('.') >= 0) {
            ext = path.substring(path.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT);
            if (!ext.matches("\\.[a-z0-9]{2,7}")) ext = "";
        }

        String type;
        if ((hintedMime != null && hintedMime.toLowerCase(java.util.Locale.ROOT).startsWith("image/"))
                || ext.matches("\\.(jpg|jpeg|png|webp|gif|bmp|avif)")) {
            type = "image";
        } else if (audioOnly || (hintedMime != null && hintedMime.toLowerCase(java.util.Locale.ROOT).startsWith("audio/"))
                || ext.matches("\\.(mp3|m4a|aac|ogg|opus|wav|flac)")) {
            type = "audio";
        } else {
            type = "video";
        }

        WebView current = getCurrentWebView();
        String referer = current == null || current.getUrl() == null ? "" : current.getUrl();
        String downloadTitle = lastDiscoveredTitle == null ? "" : lastDiscoveredTitle.trim();
        if (downloadTitle.isEmpty() && current != null) downloadTitle = current.getTitle();
        String downloadPoster = lastDiscoveredPoster == null ? "" : lastDiscoveredPoster.trim();
        if ("image".equals(type) && downloadPoster.isEmpty()) downloadPoster = cleanUrl;
        BrowserFileDownloader.start(this, cleanUrl, type, hintedMime, referer, downloadTitle, downloadPoster);
    }

    private void clearBrowserSiteData() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        android.webkit.WebStorage.getInstance().deleteAllData();
        for (WebView webView : new ArrayList<>(tabList)) {
            webView.clearCache(true);
            webView.clearFormData();
            webView.clearHistory();
        }
        Toast.makeText(this, "تم مسح الكوكيز وبيانات المواقع وذاكرة المتصفح", Toast.LENGTH_SHORT).show();
    }

    private void showDirectIptvDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("الصق رابط بث M3U8 أو FLV");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("تشغيل رابط IPTV")
                .setView(input)
                .setPositiveButton("بث إلى الشاشة", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.startsWith("http://") || url.startsWith("https://")) castToTv(url);
                    else Toast.makeText(this, "أدخل رابط بث صحيحاً", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // FG Code is an M3U playlist shortcut. This feature is independent from
    // social-site capture and never changes Instagram/Threads sniffers.
    private void showFgCodeDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("أدخل FG Code أو رابط fgcode.org");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setText(getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE)
                .getString("last_fg_code", ""));
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("📺 تشغيل FG Code")
                .setMessage("أدخل الكود الخاص بك لتحميل قائمة القنوات بصيغة M3U.")
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تحميل القنوات", (d, w) -> resolveFgCode(input.getText().toString()))
                .show();
    }

    private void resolveFgCode(String rawCode) {
        String value = rawCode == null ? "" : rawCode.trim();
        if (value.isEmpty()) {
            Toast.makeText(this, "أدخل كود FG أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        String code = value;
        try {
            // Accept a bare code, fgcode.org/ID, or a complete HTTP(S) URL.
            String candidate = value;
            if (candidate.matches("(?i)^fgcode\\.org/.*$")) candidate = "https://" + candidate;
            if (candidate.matches("(?i)^https?://(www\\.)?fgcode\\.org/.*$")) {
                Uri parsed = Uri.parse(candidate);
                code = parsed.getLastPathSegment();
            }
            if (code == null) code = "";
            code = code.trim();
            if (!code.matches("(?i)^[a-z0-9]+$")) {
                Toast.makeText(this, "اكتب كود FG أو رابط fgcode.org/الكود", Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, "صيغة كود FG غير صحيحة", Toast.LENGTH_SHORT).show();
            return;
        }

        final String requestedCode = code;
        final String playlistUrl = "https://fgcode.org/" + requestedCode;
        getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE).edit()
                .putString("last_fg_code", value).apply();
        Toast.makeText(this, "جاري الاتصال بخدمة FG Code...", Toast.LENGTH_SHORT).show();
        Request request = new Request.Builder()
                .url(playlistUrl)
                .header("User-Agent", WebSettings.getDefaultUserAgent(this))
                .header("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*")
                .header("Cache-Control", "no-cache")
                .get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                runOnUiThread(() -> new AlertDialog.Builder(BrowserActivity.this, R.style.ModernDialogTheme)
                        .setTitle("تعذر الاتصال بـ FG Code")
                        .setMessage("لم نتمكن من الوصول إلى fgcode.org.\nالتفصيل: " + reason
                                + "\n\nتأكد من اتصال الإنترنت أو جرّب فتح الرابط في المتصفح.")
                        .setPositiveButton("حسنًا", null).show());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                int status = response.code();
                String finalUrl = response.request().url().toString();
                String contentType = response.header("Content-Type", "");
                response.close();
                List<CapturedMedia> channels = parseFgM3u(body);
                runOnUiThread(() -> {
                    if (status < 200 || status >= 300) {
                        new AlertDialog.Builder(BrowserActivity.this, R.style.ModernDialogTheme)
                                .setTitle("خدمة FG Code رفضت الطلب")
                                .setMessage("رمز الاستجابة: " + status + "\nالرابط: " + finalUrl)
                                .setPositiveButton("إغلاق", null).show();
                    } else if (channels.isEmpty()) {
                        String preview = body.length() > 350 ? body.substring(0, 350) : body;
                        new AlertDialog.Builder(BrowserActivity.this, R.style.ModernDialogTheme)
                                .setTitle("لم يتم العثور على قنوات في هذا الكود")
                                .setMessage("استجابة الخدمة ليست قائمة M3U تحتوي على قنوات.\nنوع المحتوى: "
                                        + contentType + "\nالرابط النهائي: " + finalUrl
                                        + "\nبداية الرد: " + preview)
                                .setPositiveButton("إغلاق", null).show();
                    } else {
                        showFgChannelsDialog(channels, requestedCode);
                    }
                });
            }
        });
    }

    private List<CapturedMedia> parseFgM3u(String content) {
        List<CapturedMedia> result = new ArrayList<>();
        if (content == null || !content.trim().startsWith("#EXTM3U")) return result;
        String[] lines = content.replace("\\r", "").split("\\n");
        String title = "قناة FG";
        String poster = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                title = comma >= 0 && comma + 1 < line.length() ? line.substring(comma + 1).trim() : "قناة FG";
                poster = "";
            } else if (!line.isEmpty() && !line.startsWith("#")
                    && (line.startsWith("http://") || line.startsWith("https://"))) {
                result.add(new CapturedMedia(line, title, "FG / M3U", poster, 1));
                title = "قناة FG";
                poster = "";
            }
        }
        return result;
    }

    private void showFgChannelsDialog(List<CapturedMedia> channels, String code) {
        String[] names = new String[channels.size()];
        for (int i = 0; i < channels.size(); i++) names[i] = channels.get(i).title;
        new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("قنوات FG • " + channels.size() + " قناة")
                .setItems(names, (dialog, which) -> {
                    CapturedMedia selected = channels.get(which);
                    new AlertDialog.Builder(this, R.style.ModernDialogTheme).setTitle(selected.title)
                            .setItems(new String[]{"📺 بث القناة إلى AboEzzat Receiver", "▶️ تشغيل الرابط في المتصفح"},
                                    (d, action) -> {
                                        if (action == 0) {
                                            castToTv(selected.url);
                                        } else {
                                            WebView current = getCurrentWebView();
                                            if (current != null) current.loadUrl(selected.url);
                                        }
                                    }).show();
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void openDownloadsFolder() {
        showBrowserDownloadsDialog();
    }

    private void showBrowserDownloadsDialog() {
        showBrowserDownloadsDialog("all");
    }

    private void showBrowserDownloadsDialog(String initialCategory) {
        WebView current = getCurrentWebView();
        String referer = current == null || current.getUrl() == null ? "" : current.getUrl();
        DownloadManagerDialog.show(this, initialCategory, referer);
    }

    private String browserDownloadCategory(org.json.JSONObject item) {
        String type = item == null ? "" : item.optString("type", "").toLowerCase(Locale.ROOT);
        String mime = item == null ? "" : item.optString("mime", "").toLowerCase(Locale.ROOT);
        String file = item == null ? "" : item.optString("file", "").toLowerCase(Locale.ROOT);
        if ("audio".equals(type) || mime.startsWith("audio/") || file.matches(".*\\.(mp3|m4a|aac|wav|ogg|flac|opus)$")) return "audio";
        if ("image".equals(type) || mime.startsWith("image/") || file.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|svg)$")) return "image";
        if ("video".equals(type) || mime.startsWith("video/") || file.matches(".*\\.(mp4|mkv|webm|mov|avi|3gp|m4v)$")) return "video";
        return "file";
    }

    private String formatBrowserDownloadSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void showTabsDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.ModernDialogTheme)
                .setTitle("علامات التبويب المفتوحة")
                .setView(content)
                .setPositiveButton("＋ تبويب جديد", (d, w) -> addNewTab("about:home"))
                .setNegativeButton("إغلاق", null).create();
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            rows.removeAllViews();
            for (int i = 0; i < tabList.size(); i++) {
                final int index = i;
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(6), dp(8));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(i == currentTabIndex ? Color.rgb(31, 65, 94) : Color.rgb(25, 35, 52));
                bg.setCornerRadius(dp(10));
                row.setBackground(bg);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(7);
                rows.addView(row, rp);
                LinearLayout tabText = new LinearLayout(this);
                tabText.setOrientation(LinearLayout.VERTICAL);
                tabText.setPadding(0, dp(2), dp(6), dp(2));
                TextView title = new TextView(this);
                String label = tabTitles.get(i);
                title.setText(label == null || label.trim().isEmpty() ? "صفحة جديدة" : label);
                title.setTextColor(Color.WHITE);
                title.setTextSize(14);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tabText.addView(title);
                TextView address = new TextView(this);
                WebView tabWebView = tabList.get(i);
                String tabUrl = tabWebView == null ? "" : tabWebView.getUrl();
                address.setText(tabUrl == null ? "" : tabUrl);
                address.setTextColor(Color.LTGRAY);
                address.setTextSize(11);
                address.setSingleLine(true);
                address.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                tabText.addView(address);
                row.addView(tabText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button close = new Button(this);
                close.setText("×");
                close.setTextSize(19);
                close.setAllCaps(false);
                close.setMinWidth(0);
                close.setMinimumWidth(0);
                close.setPadding(dp(8), 0, dp(8), 0);
                row.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
                row.setOnClickListener(v -> {
                    switchToTab(index);
                    dialog.dismiss();
                });
                close.setOnClickListener(v -> {
                    closeTabAt(index);
                    if (tabList.isEmpty()) addNewTab("about:home");
                    render[0].run();
                });
            }
        };
        render[0].run();
        dialog.show();
    }

    private void closeTabAt(int index) {
        if (index < 0 || index >= tabList.size() || tabList.size() <= 1) return;
        WebView removed = tabList.remove(index);
        tabTitles.remove(index);
        navigationStartedAt.remove(removed);
        if (activeWebView == removed) { activeWebView = null; activePageUrl = ""; }
        webContainer.removeView(removed);
        removed.stopLoading();
        removed.destroy();
        int next = currentTabIndex;
        if (index < currentTabIndex) next--;
        else if (index == currentTabIndex) next = Math.min(index, tabList.size() - 1);
        switchToTab(Math.max(0, next));
    }

    private void closeCurrentTab() {
        closeTabAt(currentTabIndex);
    }

    private void recordVisitedUrl(WebView sourceView, String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        // WebView can deliver delayed history callbacks after a clear or redirect.
        // Only record the page that is still the WebView's active URL.
        String activeUrl = sourceView == null ? null : sourceView.getUrl();
        if (activeUrl == null || !url.equals(activeUrl)) return;
        SharedPreferences historyPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        long clearedAt = historyPrefs.getLong("history_cleared_at", 0L);
        long navigationAt = navigationStartedAt.containsKey(sourceView)
                ? navigationStartedAt.get(sourceView) : 0L;
        // Ignore a delayed WebView history callback for a page that was already open
        // when the user cleared history. A later navigation is recorded normally.
        if (clearedAt > 0L && navigationAt <= clearedAt) return;
        String title = url;
        WebView current = getCurrentWebView();
        if (current != null && url.equals(current.getUrl())) {
            String pageTitle = current.getTitle();
            if (pageTitle != null && !pageTitle.trim().isEmpty()) title = pageTitle.trim();
        }
        Set<String> history = new HashSet<>(historyPrefs.getStringSet("browser_history", new HashSet<String>()));
        history.remove(url);
        history.add(url);
        historyPrefs.edit().putStringSet("browser_history", history)
                .putString("history_title_" + url, title)
                .putLong("history_time_" + url, System.currentTimeMillis())
                .putInt("visit_count_" + url, historyPrefs.getInt("visit_count_" + url, 0) + 1)
                .apply();
    }

    private int previousSystemUiVisibility = 0;
    private int previousRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean fullScreenStateSaved = false;

    private void showHtml5FullScreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (view == null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        if (customView != null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }

        customView = view;
        customViewCallback = callback;
        previousSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
        previousRequestedOrientation = getRequestedOrientation();
        fullScreenStateSaved = true;

        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        fullScreenContainer.removeAllViews();
        fullScreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullScreenContainer.setVisibility(View.VISIBLE);

        if (topBar != null) topBar.setVisibility(View.GONE);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (appNavigation != null) appNavigation.setVisibility(View.GONE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void hideHtml5FullScreen() {
        if (customView == null) return;

        View oldView = customView;
        WebChromeClient.CustomViewCallback callback = customViewCallback;
        customView = null;
        customViewCallback = null;

        if (oldView.getParent() instanceof ViewGroup) {
            ((ViewGroup) oldView.getParent()).removeView(oldView);
        }
        fullScreenContainer.removeAllViews();
        fullScreenContainer.setVisibility(View.GONE);

        if (topBar != null) topBar.setVisibility(View.VISIBLE);
        if (progressBar != null && progressBar.getProgress() < 100) progressBar.setVisibility(View.VISIBLE);
        if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
        if (appNavigation != null) appNavigation.setVisibility(View.VISIBLE);

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (fullScreenStateSaved) {
            getWindow().getDecorView().setSystemUiVisibility(previousSystemUiVisibility);
            setRequestedOrientation(previousRequestedOrientation);
            fullScreenStateSaved = false;
        }
        if (callback != null) {
            try { callback.onCustomViewHidden(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire(60 * 60 * 1000L);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != GEOLOCATION_PERMISSION_REQUEST_CODE || pendingGeolocationCallback == null) return;

        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        pendingGeolocationCallback.invoke(pendingGeolocationOrigin, granted, false);
        pendingGeolocationCallback = null;
        pendingGeolocationOrigin = "";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (pendingFileChooserCallback != null) {
                Uri[] results = resultCode == Activity.RESULT_OK
                        ? WebChromeClient.FileChooserParams.parseResult(resultCode, data) : null;
                pendingFileChooserCallback.onReceiveValue(results);
                pendingFileChooserCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (pendingFileChooserCallback != null) {
            pendingFileChooserCallback.onReceiveValue(null);
            pendingFileChooserCallback = null;
        }
        if (pendingGeolocationCallback != null) {
            pendingGeolocationCallback.invoke(pendingGeolocationOrigin, false, false);
            pendingGeolocationCallback = null;
            pendingGeolocationOrigin = "";
        }
        if (customView != null) hideHtml5FullScreen();
        for (WebView tab : new ArrayList<>(tabList)) {
            if (tab == null) continue;
            try {
                tab.stopLoading();
                tab.removeJavascriptInterface("AboEzzatBridge");
                if (tab.getParent() instanceof ViewGroup) {
                    ((ViewGroup) tab.getParent()).removeView(tab);
                }
                tab.destroy();
            } catch (Exception ignored) {}
        }
        tabList.clear();
        tabTitles.clear();
        navigationStartedAt.clear();
        activeWebView = null;
        activePageUrl = "";
        activeYouTubeWatchUrl = "";
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideHtml5FullScreen();
            return;
        }
        WebView current = getCurrentWebView();
        if (current != null && current.canGoBack()) {
            current.goBack();
        } else if (tabList.size() > 1) {
            closeCurrentTab();
        } else {
            super.onBackPressed();
        }
    }
}
