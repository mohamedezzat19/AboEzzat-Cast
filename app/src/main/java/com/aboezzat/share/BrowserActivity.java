package com.aboezzat.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.text.TextUtils;
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
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
import okhttp3.Response;

public class BrowserActivity extends Activity {

    private final List<WebView> tabList = new ArrayList<>();
    private final List<String> tabTitles = new ArrayList<>();
    private final Map<WebView, Long> navigationStartedAt = new HashMap<>();
    private int currentTabIndex = -1;

    private LinearLayout rootLayout;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private LinearLayout appNavigation;
    private ProgressBar progressBar;
    private FrameLayout webContainer;
    private FrameLayout fullScreenContainer;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private EditText etUrl;
    private Button btnSniff;
    private Button btnCast;
    private TextView btnTabs;

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
    private final OkHttpClient client = new OkHttpClient();
    private String tvIp = "";
    private String lastDiscoveredPoster = "";
    private String lastDiscoveredTitle = "";
    // Only capture resources after the user activates a visible video player.
    private volatile String activePlaybackKey = "";

    private final List<String> adDomains = Arrays.asList(
            "doubleclick.net", "googleads", "pagead2", "adservice.google",
            "googletagmanager", "adcolony", "criteo", "adnxs", "outbrain",
            "taboola", "popads", "popcash", "propellerads", "adsterra",
            "exoclick", "smartadserver", "scorecardresearch",
            "zedo", "adroll", "pubmatic", "rubiconproject"
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

        Button btnMenu = new Button(this);
        btnMenu.setText("☰");
        btnMenu.setTextColor(Color.WHITE);
        btnMenu.setBackgroundColor(Color.TRANSPARENT);
        btnMenu.setTextSize(20);
        btnMenu.setOnClickListener(v -> showSideMenu());
        topBar.addView(btnMenu, new LinearLayout.LayoutParams(95, ViewGroup.LayoutParams.WRAP_CONTENT));

        etUrl = new EditText(this);
        etUrl.setHint("اكتب الرابط أو ابحث...");
        etUrl.setHintTextColor(Color.GRAY);
        etUrl.setTextColor(Color.WHITE);
        etUrl.setTextSize(13);
        etUrl.setSingleLine(true);
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
        LinearLayout.LayoutParams sniffParams = new LinearLayout.LayoutParams(95, ViewGroup.LayoutParams.WRAP_CONTENT);
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

        btnTabs = createBottomButton("⋮", v -> showBrowserMoreMenu());
        btnTabs.setContentDescription("المزيد من أدوات المتصفح");
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
        addNewTab(startUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (intent == null) return;
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
        TextView anchor = btnTabs;
        if (anchor == null) {
            showSideMenu();
            return;
        }
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add("إضافة تبويب").setOnMenuItemClickListener(item -> { addNewTab("about:home"); return true; });
        menu.getMenu().add("علامات التبويب").setOnMenuItemClickListener(item -> { showTabsDialog(); return true; });
        menu.getMenu().add("المواقع السريعة").setOnMenuItemClickListener(item -> { showQuickSitesDialog(); return true; });
        menu.getMenu().add("المفضلة").setOnMenuItemClickListener(item -> {
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
        menu.getMenu().add("الإعدادات").setOnMenuItemClickListener(item -> { showBrowserSettingsDialog(); return true; });
        menu.getMenu().add("القائمة الجانبية").setOnMenuItemClickListener(item -> { showSideMenu(); return true; });
        menu.show();
    }

    private void showQuickSitesDialog() {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("custom_sites", new HashSet<String>());
        List<String> labels = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (String entry : saved) {
            if (entry == null) continue;
            int split = entry.indexOf('|');
            if (split <= 0 || split >= entry.length() - 1) continue;
            labels.add(entry.substring(0, split));
            urls.add(entry.substring(split + 1));
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "لا توجد مواقع محفوظة بعد", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("المواقع السريعة")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> loadTargetUrl(urls.get(which)))
                .setNegativeButton("إغلاق", null).show();
    }

    private void showBrowserSettingsDialog() {
        String[] options = {"إعادة تحميل الصفحة", "مسح ذاكرة التخزين المؤقت", "القائمة والإعدادات العامة"};
        new AlertDialog.Builder(this).setTitle("إعدادات المتصفح")
                .setItems(options, (dialog, which) -> {
                    WebView current = getCurrentWebView();
                    if (which == 0) {
                        if (current != null) current.reload();
                    } else if (which == 1) {
                        if (current != null) current.clearCache(true);
                        Toast.makeText(this, "تم مسح ذاكرة المتصفح", Toast.LENGTH_SHORT).show();
                    } else {
                        showSideMenu();
                    }
                }).setNegativeButton("إغلاق", null).show();
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

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Support OAuth/login popup windows used by social websites.
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // User-Agent رسمي كامل مطابق للمتصفحات لفتح تيك توك لايف بدون تسجيل دخول
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36");
        settings.setGeolocationEnabled(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new JsBridge(), "AboEzzatBridge");

        webView.setWebChromeClient(new WebChromeClient() {
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
                injectAdKiller(view);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // Block pop-up windows by default. A short consent bar lets the user
                // explicitly open the requested destination without interrupting LIVE.
                String popupTarget = "";
                try {
                    WebView.HitTestResult hit = view.getHitTestResult();
                    if (hit != null && hit.getExtra() != null) popupTarget = hit.getExtra();
                } catch (Exception ignored) {}
                if (popupTarget == null || popupTarget.trim().isEmpty()) {
                    popupTarget = view.getUrl() == null ? "" : view.getUrl();
                }
                showRedirectConsent(popupTarget, true);
                return false;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void doUpdateVisitedHistory(WebView view, String loadedUrl, boolean isReload) {
                super.doUpdateVisitedHistory(view, loadedUrl, isReload);
                injectAdKiller(view);
                recordVisitedUrl(view, loadedUrl);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String targetUrl = request.getUrl().toString();
                String lower = targetUrl.toLowerCase();

                // Preserve the current ad destination blocking.
                if (lower.contains("popads") || lower.contains("bet365")) return true;

                // Play Store / app schemes and other non-web links require consent.
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

                for (String ad : adDomains) {
                    if (lowerUrl.contains(ad)) {
                        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("".getBytes()));
                    }
                }

                // Ignore every page/list/thumbnail request until a specific visible
                // player has been activated by the user in the browser.
                if (view == getCurrentWebView() && !activePlaybackKey.isEmpty()) {
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
                            addCapturedMedia(cleanMedia, title, format, lastDiscoveredPoster, type);
                        }
                    }
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String loadedUrl, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, loadedUrl, favicon);
                navigationStartedAt.put(view, System.currentTimeMillis());
                if (getCurrentWebView() == view && loadedUrl != null
                        && loadedUrl.startsWith("http")) {
                    activePlaybackKey = "";
                    clearCapturedMedia();
                }
            }

            @Override
            public void onPageFinished(final WebView view, String loadedUrl) {
                super.onPageFinished(view, loadedUrl);
                if (getCurrentWebView() == view) {
                    etUrl.setText(loadedUrl.equals("about:home") ? "" : loadedUrl);
                    tabTitles.set(currentTabIndex, view.getTitle() != null ? view.getTitle() : "علامة تبويب");
                }
                injectAdKiller(view);
                injectTikTokLiveSniffer(view);
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
            if (host != null && !host.trim().isEmpty()) return host.replaceFirst("^www\\\\.", "");
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

    private void injectAdKiller(WebView view) {
        String js = "javascript:(function(){try{" +
                "if(window.__aboActiveCaptureInstalled)return;window.__aboActiveCaptureInstalled=true;" +
                "var armed=false,activeKey='';" +
                "var style=document.getElementById('abo-ezzat-adblock');if(!style){style=document.createElement('style');style.id='abo-ezzat-adblock';" +
                "style.innerHTML='.ytp-ad-overlay-container,.ytp-ad-message-container,ytd-promoted-sparkles-web-renderer,ytd-banner-promo-renderer,ytd-ad-slot-renderer,.ad-container,.ad-div,ytd-in-feed-ad-layout-renderer,ytm-promoted-sparkles-web-renderer,.ytp-ad-action-interstitial,[id^=\\\"google_ads_\\\"],iframe[src*=\\\"doubleclick\\\"]{display:none!important;width:0!important;height:0!important}';if(document.head)document.head.appendChild(style);}" +
                "if(!window.AboEzzatKillerLoop){window.AboEzzatKillerLoop=setInterval(function(){try{" +
                "var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.videoAdUiSkipButton');if(b)b.click();" +
                "var av=document.querySelectorAll('video');for(var ai=0;ai<av.length;ai++){var x=av[ai];if(document.querySelector('.ad-interrupting,.ad-showing')||x.classList.contains('ad-interrupting')){" +
                "x.muted=true;if(!isNaN(x.duration)&&x.duration>0)x.currentTime=x.duration+1;}}}catch(e){}},200);}" +
                "function titleFor(v){try{var a=v.closest('article')||v.parentElement,owner='',caption='';" +
                "if(a){var links=[].slice.call(a.querySelectorAll('a[href]'));for(var i=0;i<links.length;i++){var h=links[i].getAttribute('href')||'';var m=h.match(/\\/@([A-Za-z0-9._-]{2,})/);if(m){owner='@'+m[1];break;}" +
                "var al=links[i].getAttribute('aria-label')||links[i].innerText||'';if(!owner&&/^@?[A-Za-z0-9._-]{2,}$/.test(al.trim()))owner=al.trim().charAt(0)==='@'?al.trim():'@'+al.trim();}" +
                "var c=a.querySelector('[data-e2e*=\\\"video-desc\\\"],[data-testid*=\\\"text\\\"],[dir=\\\"auto\\\"]');caption=c?(c.innerText||c.textContent||'').trim():(a.innerText||'').trim();" +
                "caption=caption.replace(/\\s+/g,' ').substring(0,95);}" +
                "var t=(owner?owner+' — ':'')+caption;return t||document.title||'فيديو قيد التشغيل';" +
                "}catch(e){}return document.title||'فيديو قيد التشغيل';}" +
                "function posterFor(v){try{if(v.poster&&/^https?:/i.test(v.poster))return v.poster;var a=v.closest('article')||v.parentElement;" +
                "if(a){var imgs=[].slice.call(a.querySelectorAll('img[src],img[data-src]'));imgs.sort(function(x,y){var r=x.getBoundingClientRect(),q=y.getBoundingClientRect();return (q.width*q.height)-(r.width*r.height);});" +
                "for(var i=0;i<imgs.length;i++){var u=imgs[i].currentSrc||imgs[i].src||imgs[i].getAttribute('data-src')||'';if(/^https?:/i.test(u)&&!/(avatar|profile|emoji|icon)/i.test(u))return u;}}" +
                "}catch(e){}return '';}" +
                "function area(v){try{var r=v.getBoundingClientRect();var w=Math.max(0,Math.min(innerWidth,r.right)-Math.max(0,r.left));" +
                "var h=Math.max(0,Math.min(innerHeight,r.bottom)-Math.max(0,r.top));return w*h;}catch(e){return 0;}}" +
                "function visible(v){return area(v)>Math.max(12000,(innerWidth*innerHeight)*0.025);}" +
                "function bridgeStart(v,key){var t=titleFor(v),p=posterFor(v);" +
                "if(key!==activeKey){activeKey=key;window.AboEzzatBridge.onActiveVideoChanged(key,t,p);}" +
                "var src=v.currentSrc||v.src||'';if(/^https?:/i.test(src))window.AboEzzatBridge.onVideoPlayingWithPoster(src,t,p);" +
                "}" +
                "function scanResources(v){try{var entries=performance.getEntriesByType('resource'),now=performance.now();" +
                "var videoTracks=[],bestAudio=null;" +
                "for(var i=0;i<entries.length;i++){var e=entries[i],u=e.name||'',l=u.toLowerCase();if(!/^https?:/i.test(u))continue;" +
                "if(now-e.startTime>10000)continue;" +
                "if(/\\.(jpg|jpeg|png|webp|gif|bmp|svg|ts|m4s|js|css)(?:[?#]|$)/i.test(l))continue;" +
                "var audio=/mime(?:_|-)?type=audio|mime=audio|type=audio|content[_-]?type=audio|dash_ln_heaac|audio_dash|\\.(aac|m4a|mp3|opus|ogg)(?:[?#]|$)/i.test(l);" +
                "var video=/mime(?:_|-)?type=video|mime=video|type=video|content[_-]?type=video|video\\/tos|pull-|videoplayback|\\.(mp4|m4v|m3u8|mpd|webm|flv|mov)(?:[?#]|$)/i.test(l);" +
                "if(!audio&&!video)continue;" +
                "var score=(e.initiatorType==='video'||e.initiatorType==='media'?40:0)+(now-e.startTime<8000?20:0)+" +
                "(/\\.(m3u8|mpd)(?:[?#]|$)/i.test(l)?25:0);" +
                "var item={url:u,score:score,time:e.startTime};" +
                "if(audio){if(!bestAudio||item.score>bestAudio.score||(item.score===bestAudio.score&&item.time>bestAudio.time))bestAudio=item;}" +
                "else if(video&&!videoTracks.some(function(x){return x.url===u;})){videoTracks.push(item);}" +
                "}" +
                "videoTracks.sort(function(a,b){return b.score-a.score||b.time-a.time;});" +
                "for(var j=0;j<Math.min(videoTracks.length,2);j++)window.AboEzzatBridge.onVideoPlayingWithPoster(videoTracks[j].url,titleFor(v),posterFor(v));" +
                "if(bestAudio)window.AboEzzatBridge.onVideoPlayingWithPoster(bestAudio.url,titleFor(v),posterFor(v));" +
                "}catch(e){}}" +
                "function pick(){try{if(!armed)return;var vids=[].slice.call(document.querySelectorAll('video'));" +
                "var active=vids.filter(function(v){return !v.paused&&!v.ended&&v.readyState>=2&&visible(v);});" +
                "active.sort(function(a,b){return area(b)-area(a);});" +
                "if(!active.length){if(activeKey){var old=activeKey;activeKey='';window.AboEzzatBridge.onActivePlaybackStopped(old);}return;}" +
                "var v=active[0],idx=vids.indexOf(v),key=(v.currentSrc||v.src||'')+'|'+idx;" +
                "if(!key||key==='|'+idx)return;bridgeStart(v,key);scanResources(v);" +
                "}catch(e){}}" +
                "function armFromTap(e){try{var x=e.clientX,y=e.clientY;if(e.changedTouches&&e.changedTouches.length){x=e.changedTouches[0].clientX;y=e.changedTouches[0].clientY;}" +
                "var vids=[].slice.call(document.querySelectorAll('video')),v=null,best=0;for(var i=0;i<vids.length;i++){var r=vids[i].getBoundingClientRect();" +
                "if(x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom&&area(vids[i])>best){v=vids[i];best=area(vids[i]);}}" +
                "if(v){armed=true;setTimeout(pick,80);setTimeout(pick,500);}}catch(e){}}" +
                "window.__aboArmActiveCapture=function(){armed=true;pick();};" +
                "document.addEventListener('click',armFromTap,true);document.addEventListener('touchend',armFromTap,true);" +
                "document.addEventListener('playing',function(e){if(armed)setTimeout(pick,80);},true);" +
                "document.addEventListener('pause',function(){setTimeout(pick,250);},true);" +
                "window.addEventListener('scroll',function(){if(armed)setTimeout(pick,250);},true);" +
                "setInterval(function(){if(armed)pick();},900);" +
                "}catch(e){}})()";
        view.evaluateJavascript(js, null);
    }

    private class JsBridge {
        @JavascriptInterface
        public void onMetaFound(String title, String posterUrl) {
            if (title != null && !title.isEmpty()) {
                lastDiscoveredTitle = title.replace("Instagram: ", "").replace("on Instagram", "").trim();
            }
            if (posterUrl != null && !posterUrl.isEmpty()) lastDiscoveredPoster = posterUrl;
        }

        @JavascriptInterface
        public void onActiveVideoChanged(String key, String title, String posterUrl) {
            if (key == null || key.isEmpty()) return;
            synchronized (BrowserActivity.this) {
                if (!key.equals(activePlaybackKey)) {
                    activePlaybackKey = key;
                    clearCapturedMedia();
                }
                if (title != null && !title.trim().isEmpty()) lastDiscoveredTitle = title.trim();
                if (posterUrl != null && !posterUrl.trim().isEmpty()) lastDiscoveredPoster = posterUrl;
            }
        }

        @JavascriptInterface
        public void onActivePlaybackStopped(String key) {
            synchronized (BrowserActivity.this) {
                if (key != null && key.equals(activePlaybackKey)) {
                    activePlaybackKey = "";
                    clearCapturedMedia();
                }
            }
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
            saveCustomSite(name, siteUrl);
            runOnUiThread(() -> loadHomePage());
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

    private int classifyCapturedMedia(String mediaUrl) {
        if (mediaUrl == null) return 0;
        String lower = mediaUrl.toLowerCase();
        if (lower.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|svg|ts|m4s|js|css)(?:[?#].*)?$")) return 0;

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
        boolean video = lower.matches(".*\\.(mp4|m4v|m3u8|mpd|webm|flv|mov)(?:[?#].*)?$")
                || lower.contains("mime_type=video") || lower.contains("mime-type=video")
                || lower.contains("mime=video") || lower.contains("type=video")
                || lower.contains("contenttype=video") || lower.contains("content_type=video")
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
        if ((mediaType != 1 && mediaType != 2) || activePlaybackKey.isEmpty()) return;
        boolean added = false;
        synchronized (capturedList) {
            for (CapturedMedia item : capturedList) {
                if (item.url.equalsIgnoreCase(url)) {
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

    private void loadThumbnailAsync(final CapturedMedia media) {
        AsyncTask.execute(() -> {
            try {
                URL u = new URL(media.posterUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setDoInput(true);
                conn.connect();
                InputStream input = conn.getInputStream();
                media.posterBitmap = BitmapFactory.decodeStream(input);
            } catch (Exception ignored) {}
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
        for (int i = 0; i < tabList.size(); i++) {
            tabList.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        WebView current = getCurrentWebView();
        if (current != null) {
            String u = current.getUrl();
            etUrl.setText((u == null || u.equals("about:home")) ? "" : u);
        }
        btnTabs.setText("[ " + tabList.size() + " ]");
    }

    private void loadTargetUrl(String target) {
        WebView current = getCurrentWebView();
        if (current == null || target.isEmpty()) return;

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
        etUrl.setText(target);

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
            for (String entry : customSites) {
                String[] parts = entry.split("\\|", 2);
                if (parts.length == 2) {
                    customCards.append("<a href='").append(parts[1]).append("' class='card'>")
                               .append("<div class='icon-circle' style='background:#2C3E50;'>🌐</div>")
                               .append("<span>").append(parts[0]).append("</span>")
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

        current.loadDataWithBaseURL("https://www.google.com.eg", homeHtml, "text/html", "UTF-8", null);
    }

    private void saveCustomSite(String name, String url) {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        Set<String> set = new HashSet<>(prefs.getStringSet("custom_sites", new HashSet<>()));
        set.add(name + "|" + url);
        prefs.edit().putStringSet("custom_sites", set).apply();
    }

    private void triggerManualSniff() {
        final WebView current = getCurrentWebView();
        if (current == null) return;
        Toast.makeText(this, "شغّل الفيديو واضغط عليه أولاً، ثم جرّب القنص", Toast.LENGTH_SHORT).show();

        String js = "javascript:(function(){try{" +
                "if(window.__aboArmActiveCapture)window.__aboArmActiveCapture();" +
                "var vids=[].slice.call(document.querySelectorAll('video'));" +
                "vids=vids.filter(function(v){var r=v.getBoundingClientRect();return !v.paused&&!v.ended&&v.readyState>=2&&" +
                "r.width>0&&r.height>0&&r.bottom>0&&r.top<innerHeight;});" +
                "vids.sort(function(a,b){var x=a.getBoundingClientRect(),y=b.getBoundingClientRect();return (y.width*y.height)-(x.width*x.height);});" +
                "if(!vids.length){window.AboEzzatBridge.onActivePlaybackStopped('');return;}" +
                "var v=vids[0],key=(v.currentSrc||v.src||'')+'|'+[].indexOf.call(document.querySelectorAll('video'),v);" +
                "var t=(v.closest('article')&&(v.closest('article').innerText||'').trim())||document.title||'فيديو';" +
                "window.AboEzzatBridge.onActiveVideoChanged(key,t.substring(0,90),v.poster||'');" +
                "if(v.currentSrc||v.src)window.AboEzzatBridge.onVideoPlayingWithPoster(v.currentSrc||v.src,t,v.poster||'');" +
                "var es=performance.getEntriesByType('resource'),now=performance.now(),bv=null,ba=null;" +
                "for(var i=0;i<es.length;i++){var e=es[i],u=e.name||'',l=u.toLowerCase();if(!/^https?:/i.test(u)||now-e.startTime>25000)continue;" +
                "if(/\\.(jpg|jpeg|png|webp|gif|ts|m4s|js|css)(?:[?#]|$)/i.test(l))continue;" +
                "var a=/mime=audio|type=audio|contenttype=audio|dash_ln_heaac|\\.(aac|m4a|mp3|opus|ogg)(?:[?#]|$)/i.test(l);" +
                "var z=/mime=video|type=video|contenttype=video|video\\/tos|pull-|videoplayback|\\.(mp4|m4v|m3u8|mpd|webm|flv|mov)(?:[?#]|$)/i.test(l);" +
                "if(!a&&!z)continue;var q=(e.initiatorType==='video'||e.initiatorType==='media'?50:0)+(now-e.startTime<8000?20:0);" +
                "if(a){if(!ba||q>ba.q)ba={u:u,q:q};}else if(z){if(!bv||q>bv.q)bv={u:u,q:q};}}" +
                "if(bv)window.AboEzzatBridge.onVideoPlayingWithPoster(bv.u,t,v.poster||'');" +
                "if(ba)window.AboEzzatBridge.onVideoPlayingWithPoster(ba.u,t,v.poster||'');" +
                "}catch(e){}})()";
        current.evaluateJavascript(js, null);
    }

    private void showMediaListDialog() {
        if (capturedList.isEmpty()) {
            Toast.makeText(this, "لا توجد فيديوهات مكتشفة، شغل الفيديو أولاً ثم اضغط 🎯", Toast.LENGTH_SHORT).show();
            return;
        }

        final Dialog dialog = new Dialog(this);
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

        listView.setAdapter(new BaseAdapter() {
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

                row.addView(infoLayout);

                ImageView thumb = new ImageView(BrowserActivity.this);
                LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(120, 100);
                thumb.setLayoutParams(thumbParams);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);

                if (item.posterBitmap != null) {
                    thumb.setImageBitmap(item.posterBitmap);
                } else {
                    thumb.setBackgroundColor(Color.parseColor("#2C2C2C"));
                }
                row.addView(thumb);

                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    showMediaOptionsDialog(item);
                });

                return row;
            }
        });

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

    private void downloadSelectedMedia(boolean audioOnly) {
        String targetUrl = "";

        for (CapturedMedia item : capturedList) {
            if (item.isSelected) {
                if (audioOnly && item.mediaType == 2) {
                    targetUrl = item.url;
                    break;
                } else if (!audioOnly && item.mediaType == 1) {
                    targetUrl = item.url;
                    break;
                }
            }
        }

        if (targetUrl.isEmpty()) {
            for (CapturedMedia item : capturedList) {
                if (audioOnly && item.mediaType == 2) {
                    targetUrl = item.url;
                    break;
                } else if (!audioOnly && item.mediaType == 1) {
                    targetUrl = item.url;
                    break;
                }
            }
        }

        if (targetUrl.isEmpty() && !capturedList.isEmpty()) {
            targetUrl = capturedList.get(0).url;
        }

        if (targetUrl.isEmpty()) {
            Toast.makeText(this, "تعذر إيجاد مسار للتحميل", Toast.LENGTH_SHORT).show();
            return;
        }

        downloadMedia(targetUrl, audioOnly);
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

        new AlertDialog.Builder(this)
                .setTitle(media.title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        mergeAndCastToTv();
                    } else if (which == 1) {
                        castToTv(media.url);
                    } else if (which == 2) {
                        downloadMedia(media.url, false);
                    } else if (which == 3) {
                        downloadMedia(media.url, true);
                    }
                })
                .show();
    }

    private void mergeAndCastToTv() {
        String videoTrackUrl = "";
        String audioTrackUrl = "";
        CapturedMedia bestVideo = null;
        CapturedMedia bestAudio = null;

        synchronized (capturedList) {
            for (CapturedMedia item : capturedList) {
                if (item.mediaType == 1 && (bestVideo == null
                        || (item.isSelected && !bestVideo.isSelected)
                        || (item.isSelected == bestVideo.isSelected
                            && capturedVideoQualityScore(item.url) > capturedVideoQualityScore(bestVideo.url)))) {
                    bestVideo = item;
                } else if (item.mediaType == 2 && (bestAudio == null || item.isSelected)) {
                    bestAudio = item;
                }
            }
        }

        if (bestVideo != null) videoTrackUrl = bestVideo.url;
        if (bestAudio != null) audioTrackUrl = bestAudio.url;

        if (videoTrackUrl.isEmpty()) {
            Toast.makeText(this, "تعذر العثور على مسار الفيديو للدمج", Toast.LENGTH_SHORT).show();
            return;
        }

        if (audioTrackUrl.isEmpty()) {
            castToTv(videoTrackUrl);
            return;
        }

        if (tvIp.isEmpty()) {
            Toast.makeText(this, "يرجى تعيين IP الشاشة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "جاري دمج أعلى جودة فيديو مع مسار الصوت وإرسالهما...", Toast.LENGTH_SHORT).show();

        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                endpoint = "http://" + endpoint;
            }
            if (!endpoint.contains(":8081")) {
                endpoint = endpoint + ":8081";
            }

            String fullUrl = endpoint + "/?video=" + URLEncoder.encode(videoTrackUrl, "UTF-8")
                                      + "&audio=" + URLEncoder.encode(audioTrackUrl, "UTF-8");

            Request req = new Request.Builder().url(fullUrl).build();
            client.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> Toast.makeText(BrowserActivity.this, "تعذر الإرسال للشاشة، تأكد من الاتصال", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    boolean accepted = response.isSuccessful();
                    response.close();
                    runOnUiThread(() -> Toast.makeText(BrowserActivity.this,
                            accepted ? "تم إرسال الفيديو عالي الجودة مع الصوت ✓" : "الشاشة رفضت طلب الوسائط",
                            Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "خطأ في الرابط", Toast.LENGTH_SHORT).show();
        }
    }

    private void castToTv(String mediaUrl) {
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

            String fullUrl = endpoint + "/?url=" + URLEncoder.encode(mediaUrl, "UTF-8");
            Request req = new Request.Builder().url(fullUrl).get().build();
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
                private void showResult(String message) {
                    runOnUiThread(() -> Toast.makeText(
                            BrowserActivity.this, message, Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onFailure(Call call, IOException e) {
                    // The Receiver can accept the URL and start playback, then the
                    // connection may close before OkHttp receives the HTTP response.
                    // For a GET request, sent headers mean the complete request was sent.
                    if (requestHeadersSent.get()) {
                        showResult("تم الإرسال والتشغيل بنجاح ✓");
                        return;
                    }

                    Request ping = new Request.Builder()
                            .url(receiverEndpoint + "/?command=ping")
                            .get()
                            .build();
                    client.newCall(ping).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call pingCall, IOException pingError) {
                            showResult("تعذر الإرسال للشاشة، تأكد من الـ IP");
                        }

                        @Override
                        public void onResponse(Call pingCall, Response pingResponse) {
                            boolean receiverAlive = pingResponse.isSuccessful();
                            pingResponse.close();
                            showResult(receiverAlive
                                    ? "الشاشة متصلة، لكن لم يصل طلب الوسائط"
                                    : "تعذر تأكيد استقبال الوسائط");
                        }
                    });
                }

                @Override
                public void onResponse(Call call, Response response) {
                    boolean accepted = response.isSuccessful();
                    response.close();
                    showResult(accepted
                            ? "تم الإرسال والتشغيل بنجاح ✓"
                            : "الشاشة رفضت طلب الوسائط");
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "خطأ في الرابط", Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadMedia(String mediaUrl, boolean audioOnly) {
        try {
            if (mediaUrl.contains(".m3u8") || mediaUrl.contains("pull-") || mediaUrl.contains(".flv")) {
                Toast.makeText(this, "البث المباشر الحي يبث للشاشة فقط ولا يتم تنزيله", Toast.LENGTH_LONG).show();
                return;
            }

            String cleanUrl = mediaUrl;
            if (cleanUrl.contains("&bytestart=")) {
                cleanUrl = cleanUrl.replaceAll("&bytestart=\\d+", "").replaceAll("&byteend=\\d+", "");
            }

            String ext = audioOnly ? (cleanUrl.contains("dash_ln_heaac") || cleanUrl.contains("mp4") ? ".m4a" : ".mp3") : ".mp4";
            String title = audioOnly ? "تحميل صوت - Abo Ezzat" : "تحميل فيديو - Abo Ezzat";

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(cleanUrl));
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
            request.setTitle(title);
            request.setDescription("جاري تنزيل الملف...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AboEzzat_" + System.currentTimeMillis() + ext);

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
                Toast.makeText(this, "بدأ التنزيل بنجاح في مجلد Downloads ✓", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "تعذر بدء التنزيل", Toast.LENGTH_SHORT).show();
        }
    }

    private void showSideMenu() {
        String[] menuItems = {
                "🔄 تحديث الصفحة الحالية",
                "📂 مجلد التنزيلات (Downloads)",
                "➕ فتح علامة تبويب جديدة",
                "🧹 مسح الكوكيز والبيانات المسجلة"
        };
        new AlertDialog.Builder(this)
                .setTitle("القائمة الجانبية")
                .setItems(menuItems, (dialog, which) -> {
                    if (which == 0) {
                        WebView current = getCurrentWebView();
                        if (current != null) current.reload();
                    } else if (which == 1) {
                        openDownloadsFolder();
                    } else if (which == 2) {
                        addNewTab("about:home");
                    } else if (which == 3) {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        android.webkit.WebStorage.getInstance().deleteAllData();
                        for (WebView webView : new ArrayList<>(tabList)) {
                            webView.clearCache(true);
                            webView.clearFormData();
                            webView.clearHistory();
                        }
                        Toast.makeText(BrowserActivity.this, "تم مسح الكوكيز وبيانات المواقع وذاكرة المتصفح", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void openDownloadsFolder() {
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
                Toast.makeText(this, "تعذر فتح مجلد التنزيلات تلقائياً", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showTabsDialog() {
        String[] titles = new String[tabList.size()];
        for (int i = 0; i < tabList.size(); i++) {
            titles[i] = (i + 1) + ". " + tabTitles.get(i);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("علامات التبويب المفتوحة (" + tabList.size() + ")");
        builder.setItems(titles, (dialog, which) -> switchToTab(which));
        builder.setPositiveButton("➕ علامة تبويب جديدة", (dialog, which) -> addNewTab("about:home"));
        if (tabList.size() > 1) {
            builder.setNegativeButton("✖ إغلاق التبويب الحالي", (dialog, which) -> closeCurrentTab());
        }
        builder.show();
    }

    private void closeCurrentTab() {
        if (tabList.size() <= 1) return;
        WebView toRemove = tabList.remove(currentTabIndex);
        tabTitles.remove(currentTabIndex);
        navigationStartedAt.remove(toRemove);
        webContainer.removeView(toRemove);
        toRemove.destroy();
        switchToTab(Math.max(0, currentTabIndex - 1));
    }

    private void recordVisitedUrl(WebView sourceView, String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
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
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
            }
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
