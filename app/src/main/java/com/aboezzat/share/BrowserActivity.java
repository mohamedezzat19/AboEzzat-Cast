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
import java.util.Map;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BrowserActivity extends Activity {

    private final List<WebView> tabList = new ArrayList<>();
    private final List<String> tabTitles = new ArrayList<>();
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
                final Dialog popupDialog = new Dialog(BrowserActivity.this);
                final WebView popup = new WebView(BrowserActivity.this);
                WebSettings popupSettings = popup.getSettings();
                popupSettings.setJavaScriptEnabled(true);
                popupSettings.setDomStorageEnabled(true);
                popupSettings.setDatabaseEnabled(true);
                popupSettings.setJavaScriptCanOpenWindowsAutomatically(true);
                popupSettings.setSupportMultipleWindows(true);
                popupSettings.setUserAgentString(view.getSettings().getUserAgentString());
                CookieManager cookies = CookieManager.getInstance();
                cookies.setAcceptCookie(true);
                cookies.setAcceptThirdPartyCookies(popup, true);

                popup.setWebChromeClient(new WebChromeClient() {
                    @Override public boolean onCreateWindow(WebView child, boolean dialog, boolean gesture, android.os.Message message) {
                        return false;
                    }
                    @Override public void onCloseWindow(WebView window) {
                        try { popupDialog.dismiss(); } catch (Exception ignored) {}
                    }
                });
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        Uri uri = request.getUrl();
                        String scheme = uri.getScheme();
                        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                        try {
                            Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                            if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
                            else {
                                String fallback = intent.getStringExtra("browser_fallback_url");
                                if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) child.loadUrl(fallback);
                            }
                        } catch (Exception ignored) {}
                        return true;
                    }
                });

                popupDialog.setContentView(popup);
                popupDialog.setOnDismissListener(dialog -> {
                    try { popup.stopLoading(); popup.destroy(); } catch (Exception ignored) {}
                });
                popupDialog.show();
                Window popupWindow = popupDialog.getWindow();
                if (popupWindow != null) {
                    popupWindow.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                    popupWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                }
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    onHideCustomView();
                    return;
                }
                customView = view;
                customViewCallback = callback;

                topBar.setVisibility(View.GONE);
                progressBar.setVisibility(View.GONE);
                bottomBar.setVisibility(View.GONE);
                appNavigation.setVisibility(View.GONE);
                webContainer.setVisibility(View.GONE);

                fullScreenContainer.addView(customView);
                fullScreenContainer.setVisibility(View.VISIBLE);

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;

                fullScreenContainer.removeView(customView);
                fullScreenContainer.setVisibility(View.GONE);
                customView = null;

                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }

                topBar.setVisibility(View.VISIBLE);
                bottomBar.setVisibility(View.VISIBLE);
                appNavigation.setVisibility(View.VISIBLE);
                webContainer.setVisibility(View.VISIBLE);

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void doUpdateVisitedHistory(WebView view, String loadedUrl, boolean isReload) {
                super.doUpdateVisitedHistory(view, loadedUrl, isReload);
                injectAdKiller(view);
                recordVisitedUrl(loadedUrl);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String targetUrl = request.getUrl().toString();
                String lower = targetUrl.toLowerCase();

                // Block only known ad destinations. OAuth/login redirect URLs must remain allowed.
                if (lower.contains("popads") || lower.contains("bet365")) return true;

                if (targetUrl.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(targetUrl, Intent.URI_INTENT_SCHEME);
                        if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
                        else {
                            String fallback = intent.getStringExtra("browser_fallback_url");
                            if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) view.loadUrl(fallback);
                        }
                    } catch (Exception ignored) {}
                    return true;
                }
                if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                        if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
                    } catch (Exception ignored) {}
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

                boolean isJunk = lowerUrl.contains(".ts") || lowerUrl.contains("seg-") ||
                                 lowerUrl.contains(".js") || lowerUrl.contains(".css") ||
                                 lowerUrl.contains("preview") || lowerUrl.contains("rsrc.php") ||
                                 lowerUrl.contains("google_ads") || lowerUrl.contains("ad-interrupting");

                if (!isJunk) {
                    if (lowerUrl.contains(".mp4") || lowerUrl.contains(".flv") || 
                        lowerUrl.contains("pull-") || lowerUrl.contains(".m3u8") || 
                        lowerUrl.contains("mime=video") || lowerUrl.contains("mime=audio") || 
                        lowerUrl.contains("fbcdn.net")) {
                        
                        String cleanMedia = reqUrl;
                        if (cleanMedia.contains("&bytestart=")) {
                            cleanMedia = cleanMedia.replaceAll("&bytestart=\\d+", "").replaceAll("&byteend=\\d+", "");
                        }

                        int type = 0;
                        String format = "⚠️ مسار غير معروف";

                        if (lowerUrl.contains("audio") || lowerUrl.contains("dash_ln_heaac") || (lowerUrl.contains("bytestart=0") && lowerUrl.contains("mime=audio"))) {
                            type = 2;
                            format = "🎵 مسار صوت مؤكد";
                        } else if (lowerUrl.contains("video") || lowerUrl.contains(".mp4") || lowerUrl.contains(".m3u8") || lowerUrl.contains(".flv")) {
                            type = 1;
                            format = "🎬 مسار فيديو مؤكد";
                            if (lowerUrl.contains(".flv") || lowerUrl.contains("pull-")) format = "📡 بث حي - flv";
                            else if (lowerUrl.contains(".m3u8")) format = "📡 بث مباشر - m3u8";
                        }

                        String cleanName = !lastDiscoveredTitle.isEmpty() ? lastDiscoveredTitle : "مقطع وسائط";
                        try {
                            String pageTitle = view.getTitle();
                            if (pageTitle != null && !pageTitle.isEmpty() && !pageTitle.contains("http") && !pageTitle.contains("Instagram")) {
                                cleanName = pageTitle;
                            }
                        } catch (Exception ignored) {}

                        addCapturedMedia(cleanMedia, cleanName, format, lastDiscoveredPoster, type);
                    }
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(final WebView view, String loadedUrl) {
                super.onPageFinished(view, loadedUrl);
                if (getCurrentWebView() == view) {
                    etUrl.setText(loadedUrl.equals("about:home") ? "" : loadedUrl);
                    tabTitles.set(currentTabIndex, view.getTitle() != null ? view.getTitle() : "علامة تبويب");
                }
                injectAdKiller(view);
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

    private void injectAdKiller(WebView view) {
        String adBlockAndSniffJs = "javascript:(function(){" +
                "  try {" +
                "    var style = document.getElementById('abo-ezzat-adblock');" +
                "    if(!style){" +
                "      style = document.createElement('style');" +
                "      style.id = 'abo-ezzat-adblock';" +
                "      style.innerHTML = '.ytp-ad-overlay-container, .ytp-ad-message-container, " +
                "                         ytd-promoted-sparkles-web-renderer, ytd-banner-promo-renderer, " +
                "                         ytd-ad-slot-renderer, .ad-container, .ad-div, ytd-in-feed-ad-layout-renderer, " +
                "                         ytm-promoted-sparkles-web-renderer, .ytp-ad-action-interstitial, " +
                "                         [id^=\"google_ads_\"], iframe[src*=\"doubleclick\"] { display: none !important; width:0px!important; height:0px!important; }';" +
                "      document.head.appendChild(style);" +
                "    }" +
                "  } catch(e){}" +
                "  if (!window.AboEzzatKillerLoop) {" +
                "    window.AboEzzatKillerLoop = setInterval(function(){" +
                "      try {" +
                "        var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .videoAdUiSkipButton');" +
                "        if (skipBtn) { skipBtn.click(); }" +
                "        var adVideos = document.querySelectorAll('video');" +
                "        for(var i=0; i<adVideos.length; i++){" +
                "          var v = adVideos[i];" +
                "          if (document.querySelector('.ad-interrupting, .ad-showing') || v.classList.contains('ad-interrupting')) {" +
                "            v.muted = true;" +
                "            if(!isNaN(v.duration) && v.duration > 0){ v.currentTime = v.duration + 1; }" +
                "          }" +
                "        }" +
                "      } catch(e) {}" +
                "    }, 200);" +
                "  }" +
                "  try{" +
                "    var title = ''; var poster = '';" +
                "    var metaTitle = document.querySelector('meta[property=\"og:title\"], meta[name=\"twitter:title\"]');" +
                "    if(metaTitle && metaTitle.content){ title = metaTitle.content; }" +
                "    var metaImg = document.querySelector('meta[property=\"og:image\"], meta[name=\"twitter:image\"]');" +
                "    if(metaImg && metaImg.content){ poster = metaImg.content; }" +
                "    window.AboEzzatBridge.onMetaFound(title, poster);" +
                "  }catch(e){}" +
                "  try{" +
                "    var origPlay = HTMLMediaElement.prototype.play;" +
                "    HTMLMediaElement.prototype.play = function(){" +
                "      var src = this.currentSrc || this.src;" +
                "      if(src && src.indexOf('http') === 0 && !this.classList.contains('ad-interrupting')){" +
                "        window.AboEzzatBridge.onVideoPlayingWithPoster(src, '', '');" +
                "      }" +
                "      return origPlay.apply(this, arguments);" +
                "    };" +
                "  }catch(e){}" +
                "})()";
        view.evaluateJavascript(adBlockAndSniffJs, null);
    }
    private class JsBridge {
        @JavascriptInterface
        public void onMetaFound(String title, String posterUrl) {
            if (title != null && !title.isEmpty()) {
                lastDiscoveredTitle = title.replace("Instagram: ", "").replace("on Instagram", "").trim();
            }
            if (posterUrl != null && !posterUrl.isEmpty()) {
                lastDiscoveredPoster = posterUrl;
            }
            for (CapturedMedia item : capturedList) {
                if (item.posterBitmap == null && !lastDiscoveredPoster.isEmpty()) {
                    item.posterUrl = lastDiscoveredPoster;
                    loadThumbnailAsync(item);
                }
                if (!lastDiscoveredTitle.isEmpty() && (item.title.equals("مقطع إنستجرام") || item.title.equals("مقطع وسائط"))) {
                    item.title = lastDiscoveredTitle;
                }
            }
        }

        @JavascriptInterface
        public void onVideoPlayingWithPoster(String mediaUrl, String title, String posterUrl) {
            if (mediaUrl != null && !mediaUrl.isEmpty() && mediaUrl.startsWith("http")) {
                String cleanMedia = mediaUrl;
                if (cleanMedia.contains("&bytestart=")) {
                    cleanMedia = cleanMedia.replaceAll("&bytestart=\\d+", "").replaceAll("&byteend=\\d+", "");
                }
                
                int type = 0;
                String lower = cleanMedia.toLowerCase();
                String format = "⚠️ مسار غير معروف";
                if (lower.contains("audio") || lower.contains("dash_ln_heaac")) {
                    type = 2;
                    format = "🎵 مسار صوت مؤكد";
                } else if (lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".flv") ||
                        (lower.contains("tiktok") && lower.contains("pull-"))) {
                    // TikTok LIVE may expose a pull stream URL without a file extension.
                    type = 1;
                    format = lower.contains("pull-") ? "📡 بث تيك توك مباشر" : "🎬 مسار فيديو مؤكد";
                }

                String realTitle = !lastDiscoveredTitle.isEmpty() ? lastDiscoveredTitle : (!title.isEmpty() ? title : "مقطع وسائط");
                String realPoster = !posterUrl.isEmpty() ? posterUrl : lastDiscoveredPoster;
                addCapturedMedia(cleanMedia, realTitle, format, realPoster, type);
            }
        }

        @JavascriptInterface
        public void onNewCustomSite(String name, String siteUrl) {
            saveCustomSite(name, siteUrl);
            runOnUiThread(() -> loadHomePage());
        }
    }

    private synchronized void addCapturedMedia(String url, String title, String format, String posterUrl, int mediaType) {
        for (CapturedMedia item : capturedList) {
            if (item.url.equalsIgnoreCase(url) || (item.url.contains("manifest") && url.contains("manifest"))) {
                if (item.posterUrl == null && posterUrl != null) {
                    item.posterUrl = posterUrl;
                    loadThumbnailAsync(item);
                }
                return;
            }
        }

        String cleanTitle = (title != null && !title.isEmpty()) ? title : "مقطع فيديو";
        if (cleanTitle.length() > 50) {
            cleanTitle = cleanTitle.substring(0, 50) + "...";
        }
        final CapturedMedia newMedia = new CapturedMedia(url, cleanTitle, format, posterUrl, mediaType);
        capturedList.add(0, newMedia);

        autoSelectTracks();

        if (posterUrl != null && !posterUrl.isEmpty()) {
            loadThumbnailAsync(newMedia);
        }

        runOnUiThread(() -> {
            btnCast.setVisibility(View.VISIBLE);
            btnCast.setText("📺 " + capturedList.size());

            Animation blink = new AlphaAnimation(0.3f, 1.0f);
            blink.setDuration(350);
            blink.setRepeatMode(Animation.REVERSE);
            blink.setRepeatCount(4);
            btnCast.startAnimation(blink);
        });
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
        Toast.makeText(this, "جاري فحص وتأكيد الفيديو...", Toast.LENGTH_SHORT).show();

        String curUrl = current.getUrl();
        if (curUrl != null && (curUrl.contains("watch") || curUrl.contains("youtu.be"))) {
            addCapturedMedia(curUrl, current.getTitle() != null ? current.getTitle() : "فيديو يوتيوب", "🎬 فيديو مباشر", null, 1);
            return;
        }

        // Keep the existing sniff path unchanged for every platform. TikTok LIVE gets
        // an additional targeted scan for the actual HLS/live media URL.
        String js = "javascript:(function(){"+
                "var title=''; var poster='';"+
                "var mt=document.querySelector('meta[property=\\\"og:title\\\"],meta[name=\\\"twitter:title\\\"]');"+
                "if(mt&&mt.content)title=mt.content;"+
                "var mi=document.querySelector('meta[property=\\\"og:image\\\"],meta[name=\\\"twitter:image\\\"]');"+
                "if(mi&&mi.content)poster=mi.content;"+
                "if(title||poster)window.AboEzzatBridge.onMetaFound(title,poster);"+
                "var meta=document.querySelector('meta[property=\\\"og:video\\\"],meta[property=\\\"og:video:secure_url\\\"]');"+
                "if(meta&&meta.content)window.AboEzzatBridge.onVideoPlayingWithPoster(meta.content,title||document.title,poster);"+
                "var vids=document.getElementsByTagName('video');"+
                "for(var i=0;i<vids.length;i++){var src=vids[i].currentSrc||vids[i].src;"+
                "if(src&&src.indexOf('http')===0&&!vids[i].classList.contains('ad-interrupting'))"+
                "window.AboEzzatBridge.onVideoPlayingWithPoster(src,title||document.title,poster);}"+
                "var u=location.href.toLowerCase();"+
                "if(u.indexOf('tiktok.com')!==-1&&(u.indexOf('/live')!==-1||u.indexOf('live')!==-1)){"+
                "try{var entries=performance.getEntriesByType('resource');"+
                "for(var j=0;j<entries.length;j++){var ru=entries[j].name;var rl=ru.toLowerCase();"+
                "if(rl.indexOf('.m3u8')!==-1||rl.indexOf('pull-')!==-1||rl.indexOf('live')!==-1&&"+
                "(rl.indexOf('video')!==-1||rl.indexOf('stream')!==-1)){"+
                "window.AboEzzatBridge.onVideoPlayingWithPoster(ru,title||document.title,poster);}}"+
                "}catch(e){}}"+
                "})()";
        current.loadUrl(js);
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
        String videoTrack = "";
        String audioTrack = "";

        for (CapturedMedia item : capturedList) {
            if (item.isSelected) {
                if (item.mediaType == 1 && videoTrack.isEmpty()) {
                    videoTrack = item.url;
                } else if (item.mediaType == 2 && audioTrack.isEmpty()) {
                    audioTrack = item.url;
                } else if (videoTrack.isEmpty()) {
                    videoTrack = item.url;
                } else if (audioTrack.isEmpty()) {
                    audioTrack = item.url;
                }
            }
        }

        if (videoTrack.isEmpty()) {
            for (CapturedMedia item : capturedList) {
                if (item.mediaType == 1) {
                    videoTrack = item.url;
                    break;
                }
            }
        }

        if (videoTrack.isEmpty() && !capturedList.isEmpty()) {
            videoTrack = capturedList.get(0).url;
        }

        if (videoTrack.isEmpty()) {
            Toast.makeText(this, "يرجى تشغيل الفيديو وقنصه أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        if (audioTrack.isEmpty()) {
            castToTv(videoTrack);
            return;
        }

        if (tvIp.isEmpty()) {
            Toast.makeText(this, "يرجى تعيين IP الشاشة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "جاري إرسال البث المدمج للشاشة...", Toast.LENGTH_SHORT).show();

        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) endpoint = "http://" + endpoint;
            if (!endpoint.contains(":8081")) endpoint += ":8081";

            String fullUrl = endpoint + "/?video=" + URLEncoder.encode(videoTrack, "UTF-8") 
                                      + "&audio=" + URLEncoder.encode(audioTrack, "UTF-8");

            Request req = new Request.Builder().url(fullUrl).build();
            client.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> Toast.makeText(BrowserActivity.this, "تعذر الإرسال للشاشة", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    runOnUiThread(() -> Toast.makeText(BrowserActivity.this, "تم بدء البث المدمج في الشاشة بنجاح ✓", Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception ignored) {}
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

        for (CapturedMedia m : capturedList) {
            if (m.isAudio && audioTrackUrl.isEmpty()) {
                audioTrackUrl = m.url;
            } else if (!m.isAudio && videoTrackUrl.isEmpty()) {
                videoTrackUrl = m.url;
            }
        }

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

        Toast.makeText(this, "جاري إرسال البث المدمج للشاشة...", Toast.LENGTH_SHORT).show();

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
                    runOnUiThread(() -> Toast.makeText(BrowserActivity.this, "تم بدء العرض المدمج في الشاشة بنجاح ✓", Toast.LENGTH_SHORT).show());
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
            Request req = new Request.Builder().url(fullUrl).build();
            client.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> Toast.makeText(BrowserActivity.this, "تعذر الإرسال للشاشة، تأكد من الـ IP", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    runOnUiThread(() -> Toast.makeText(BrowserActivity.this, "تم الإرسال والتشغيل بنجاح ✓", Toast.LENGTH_SHORT).show());
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
        webContainer.removeView(toRemove);
        toRemove.destroy();
        switchToTab(Math.max(0, currentTabIndex - 1));
    }

    private void recordVisitedUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        SharedPreferences historyPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
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
