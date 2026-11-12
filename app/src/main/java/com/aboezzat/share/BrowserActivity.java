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
import android.media.MediaExtractor;
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
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.Response;
import org.json.JSONObject;

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
    private final Set<String> mp4ProbeRequests = Collections.synchronizedSet(new HashSet<String>());
    private final OkHttpClient client = new OkHttpClient();
    private String tvIp = "";
    private String lastDiscoveredPoster = "";
    private String lastDiscoveredTitle = "";
    // Only capture resources after the user activates a visible video player.
    private volatile String activePlaybackKey = "";
    // Isolated manual capture window for generic embedded players; never armed by share-sheet routes.
    private volatile long manualSniffUntilMs = 0L;
    // Automatic generic capture is armed only for ordinary web players and TikTok LIVE.
    private volatile long genericAutoSniffUntilMs = 0L;

    private boolean isManualSniffActive() {
        return System.currentTimeMillis() <= manualSniffUntilMs;
    }

    private boolean isGenericAutoSniffActive() {
        return System.currentTimeMillis() <= genericAutoSniffUntilMs;
    }

    private boolean shouldAutoCaptureGenericPage(String url) {
        if (url == null || !url.startsWith("http")) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
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
        View anchor = browserMenuButton;
        if (anchor == null) {
            showSideMenu();
            return;
        }
        // Keep one flat menu: each browser action appears once and opens its
        // destination directly instead of chaining into another menu.
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
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
        menu.show();
    }

    private void showBrowserHistoryDialog() {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("browser_history", new HashSet<String>());
        List<String> urls = new ArrayList<>(saved == null ? new HashSet<String>() : saved);
        Collections.sort(urls, (a, b) -> Long.compare(
                prefs.getLong("history_time_" + b, 0L), prefs.getLong("history_time_" + a, 0L)));
        if (urls.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("سجل التصفح")
                    .setMessage("لا توجد مواقع في سجل التصفح حتى الآن.")
                    .setPositiveButton("إغلاق", null).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (String url : urls) {
            String title = prefs.getString("history_title_" + url, url);
            labels.add(title + "\\n" + url);
        }
        android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                TextView first = row.findViewById(android.R.id.text1);
                TextView second = row.findViewById(android.R.id.text2);
                String url = urls.get(position);
                first.setText(prefs.getString("history_title_" + url, url));
                second.setText(url);
                first.setTextColor(Color.WHITE);
                second.setTextColor(Color.LTGRAY);
                return row;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("سجل التصفح • " + urls.size())
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
            String url = urls.get(position);
            Set<String> updated = new HashSet<>(prefs.getStringSet("browser_history", new HashSet<String>()));
            updated.remove(url);
            prefs.edit().putStringSet("browser_history", updated)
                    .remove("history_title_" + url).remove("history_time_" + url)
                    .remove("visit_count_" + url).apply();
            dialog.dismiss();
            showBrowserHistoryDialog();
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
            new AlertDialog.Builder(this).setTitle("المفضلة والمواقع المحفوظة")
                    .setMessage("لم تحفظ أي مواقع بعد. افتح موقعاً ثم اختر المفضلة من قائمة المتصفح.")
                    .setPositiveButton("حسنًا", null).show();
            return;
        }
        android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, entries) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                String entry = entries.get(position);
                int split = entry.indexOf('|');
                TextView title = row.findViewById(android.R.id.text1);
                TextView address = row.findViewById(android.R.id.text2);
                title.setText(entry.substring(0, split));
                address.setText(entry.substring(split + 1));
                title.setTextColor(Color.WHITE);
                address.setTextColor(Color.LTGRAY);
                return row;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
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
            Set<String> updated = new HashSet<>(prefs.getStringSet("custom_sites", new HashSet<String>()));
            updated.remove(entry);
            prefs.edit().putStringSet("custom_sites", updated).apply();
            dialog.dismiss();
            showQuickSitesDialog();
            Toast.makeText(this, "تم حذف الموقع من المفضلة", Toast.LENGTH_SHORT).show();
            return true;
        });
        dialog.show();
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
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Support OAuth/login popup windows used by social websites.
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        // Preserve the original multi-window behavior for non-QFilm sites.
        // QFilm is switched to same-tab handling only when that domain loads.
        settings.setSupportMultipleWindows(true);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // Keep the established UA for existing sites. QFilm receives the current
        // Android WebView Chromium UA (without the WebView marker) for compatibility.
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
            public void doUpdateVisitedHistory(WebView view, String loadedUrl, boolean isReload) {
                super.doUpdateVisitedHistory(view, loadedUrl, isReload);
                injectAdKiller(view);
                recordVisitedUrl(view, loadedUrl);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String targetUrl = request.getUrl().toString();
                String lower = targetUrl.toLowerCase();

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
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();
                String lowerUrl = reqUrl.toLowerCase();

                if (isBlockedAdUrl(reqUrl)) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }

                // Ignore every page/list/thumbnail request until a specific visible
                // player has been activated by the user in the browser.
                if (view == getCurrentWebView() && (!activePlaybackKey.isEmpty() || isManualSniffActive() || isGenericAutoSniffActive())) {
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
                        } else if (lowerUrl.matches(".*\\.mp4(?:[?#].*)?$")) {
                            probeAndAddMp4(reqUrl, lastDiscoveredTitle, lastDiscoveredPoster);
                        }
                    }
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String loadedUrl, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, loadedUrl, favicon);
                navigationStartedAt.put(view, System.currentTimeMillis());
                if (loadedUrl != null && isQFilmUrl(loadedUrl)) {
                    // QFilm server buttons commonly use target=_blank/window.open for
                    // their player endpoints. Keep those destinations in this WebView.
                    view.getSettings().setSupportMultipleWindows(false);
                    view.getSettings().setUserAgentString(getQFilmCompatibleUserAgent());
                } else if (loadedUrl != null && loadedUrl.startsWith("http")) {
                    // Restore the established browser settings on every other site.
                    view.getSettings().setSupportMultipleWindows(true);
                    view.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36");
                }
                if (getCurrentWebView() == view && loadedUrl != null
                        && loadedUrl.startsWith("http")) {
                    activePlaybackKey = "";
                    manualSniffUntilMs = 0L;
                    genericAutoSniffUntilMs = shouldAutoCaptureGenericPage(loadedUrl)
                            ? System.currentTimeMillis() + 10 * 60 * 1000L : 0L;
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
                injectPopupAndOverlayBlocker(view);
                injectAdKiller(view);
                injectTikTokLiveSniffer(view);
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
                "var armed=false,activeKey='',activeSince=0,seenResources={};" +
                // Keep extended preload history only for Threads; preserve Instagram behavior.
                "var isThreadsHost=/(?:threads\\.net|threads\\.com)$/i.test(location.hostname||'');" +
                "var isTikTokLivePage=/(?:^|\\.)tiktok\\.com$/i.test(location.hostname||'')&&/\\/live(?:[/?#]|$)/i.test(location.pathname||'');" +
                "if(isThreadsHost||isTikTokLivePage){try{if(performance.setResourceTimingBufferSize)performance.setResourceTimingBufferSize(1500);}catch(e){}}" +
                "var style=document.getElementById('abo-ezzat-adblock');if(!style){style=document.createElement('style');style.id='abo-ezzat-adblock';" +
                "style.innerHTML='.ytp-ad-overlay-container,.ytp-ad-message-container,ytd-promoted-sparkles-web-renderer,ytd-banner-promo-renderer,ytd-ad-slot-renderer,.ad-container,.ad-div,ytd-in-feed-ad-layout-renderer,ytm-promoted-sparkles-web-renderer,.ytp-ad-action-interstitial,[id^=\\\"google_ads_\\\"],iframe[src*=\\\"doubleclick\\\"]{display:none!important;width:0!important;height:0!important}';if(document.head)document.head.appendChild(style);}" +
                "if(!window.AboEzzatKillerLoop){window.AboEzzatKillerLoop=setInterval(function(){try{" +
                "var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.videoAdUiSkipButton');if(b)b.click();" +
                "var av=document.querySelectorAll('video');for(var ai=0;ai<av.length;ai++){var x=av[ai];if(document.querySelector('.ad-interrupting,.ad-showing')||x.classList.contains('ad-interrupting')){" +
                "x.muted=true;if(!isNaN(x.duration)&&x.duration>0)x.currentTime=x.duration+1;}}}catch(e){}},200);}" +
                "function metaValue(prop,name){try{var ms=document.getElementsByTagName('meta');for(var i=0;i<ms.length;i++){var m=ms[i];if((prop&&m.getAttribute('property')===prop)||(name&&m.getAttribute('name')===name)){var c=m.getAttribute('content')||'';if(c.trim())return c.trim();}}}catch(e){}return '';}" +
                "function titleFor(v){try{if(isTikTokLivePage){var t=metaValue('og:title','twitter:title');if(!t){var ns=[].slice.call(document.querySelectorAll('[data-e2e]'));for(var ni=0;ni<ns.length;ni++){var de=(ns[ni].getAttribute('data-e2e')||'').toLowerCase();if(de.indexOf('live-room')>=0||de.indexOf('live-title')>=0||de.indexOf('live-user')>=0){t=(ns[ni].innerText||ns[ni].textContent||'').trim();if(t)break;}}}if(!t)t=document.title||'';t=t.replace(/\\s*[|—-]\\s*TikTok.*$/i,'').replace(/^TikTok\\s*(LIVE)?\\s*[:—-]?\\s*/i,'').trim();if(t&&!/^live$|^tiktok$/i.test(t))return t;return '';}" +
                "var a=v.closest('article')||v.parentElement,owner='',caption='';if(a){var links=[].slice.call(a.querySelectorAll('a[href]'));for(var i=0;i<links.length;i++){var h=links[i].getAttribute('href')||'';var m=h.match(/\\/@([A-Za-z0-9._-]{2,})/);if(m){owner='@'+m[1];break;}var al=links[i].getAttribute('aria-label')||links[i].innerText||'';if(!owner&&/^@?[A-Za-z0-9._-]{2,}$/.test(al.trim()))owner=al.trim().charAt(0)==='@'?al.trim():'@'+al.trim();}var c=a.querySelector('[data-e2e*=\\\"video-desc\\\"],[data-testid*=\\\"text\\\"],[dir=\\\"auto\\\"]');caption=c?(c.innerText||c.textContent||'').trim():(a.innerText||'').trim();caption=caption.replace(/\\s+/g,' ').substring(0,95);}var t=(owner?owner+' — ':'')+caption;return t||document.title||'فيديو قيد التشغيل';}catch(e){}return isTikTokLivePage?'':(document.title||'فيديو قيد التشغيل');}" +
                "function posterFor(v){try{if(v.poster&&/^https?:/i.test(v.poster))return v.poster;if(isTikTokLivePage){var p=metaValue('og:image','twitter:image');if(/^https?:/i.test(p))return p;var all=[].slice.call(document.getElementsByTagName('img'));all.sort(function(x,y){return (y.naturalWidth*y.naturalHeight)-(x.naturalWidth*x.naturalHeight);});for(var k=0;k<all.length;k++){var im=all[k],u=im.currentSrc||im.src||im.getAttribute('data-src')||'';if(/^https?:/i.test(u)&&im.naturalWidth>=120&&im.naturalHeight>=90&&!/(avatar|profile|emoji|icon)/i.test(u))return u;}return '';}var a=v.closest('article')||v.parentElement;if(a){var imgs=[].slice.call(a.querySelectorAll('img[src],img[data-src]'));imgs.sort(function(x,y){var r=x.getBoundingClientRect(),q=y.getBoundingClientRect();return (q.width*q.height)-(r.width*r.height);});for(var i=0;i<imgs.length;i++){var u=imgs[i].currentSrc||imgs[i].src||imgs[i].getAttribute('data-src')||'';if(/^https?:/i.test(u)&&!/(avatar|profile|emoji|icon)/i.test(u))return u;}}}catch(e){}return '';}" +
                "function area(v){try{var r=v.getBoundingClientRect();var w=Math.max(0,Math.min(innerWidth,r.right)-Math.max(0,r.left));" +
                "var h=Math.max(0,Math.min(innerHeight,r.bottom)-Math.max(0,r.top));return w*h;}catch(e){return 0;}}" +
                "function visible(v){return area(v)>Math.max(12000,(innerWidth*innerHeight)*0.025);}" +
                "function bridgeStart(v,key){var t=titleFor(v),p=posterFor(v);" +
                "if(key!==activeKey){activeKey=key;activeSince=performance.now()-4000;seenResources={};window.AboEzzatBridge.onActiveVideoChanged(key,t,p);}else if(isTikTokLivePage){window.AboEzzatBridge.onActiveVideoChanged(key,t,p);}" +
                "var src=v.currentSrc||v.src||'';if(/^https?:/i.test(src))window.AboEzzatBridge.onActiveVideoTrack(src,t,p);" +
                "}" +
                "function scanResources(v){try{var entries=performance.getEntriesByType('resource'),now=performance.now();" +
                "var videoTracks=[],bestAudio=null;" +
                "for(var i=0;i<entries.length;i++){var e=entries[i],u=e.name||'',l=u.toLowerCase();if(!/^https?:/i.test(u))continue;" +
                // For Threads, only consider resources requested during the currently playing visible post.
                "if(isThreadsHost&&(e.startTime<activeSince||e.startTime>performance.now()+100))continue;" +
                "if(isThreadsHost&&seenResources[u])continue;" +
                // Threads may preload DASH audio early; Instagram and other sites keep the original 10s window.
                "if(!(isThreadsHost||isTikTokLivePage)&&now-e.startTime>10000)continue;" +
                "if(/\\.(jpg|jpeg|png|webp|gif|bmp|svg|ts|m4s|js|css)(?:[?#]|$)/i.test(l))continue;" +
                "var audio=/mime(?:_|-)?type=audio|mime=audio|type=audio|content[_-]?type=audio|dash_ln_heaac|audio_dash|\\.(aac|m4a|mp3|opus|ogg)(?:[?#]|$)/i.test(l);" +
                "var mp4Candidate=/\\.mp4(?:[?#]|$)/i.test(l);" +
                "var video=/mime(?:_|-)?type=video|mime=video|type=video|content[_-]?type=video|video\\/tos|pull-|videoplayback|\\.(m4v|m3u8|mpd|webm|flv|mov)(?:[?#]|$)/i.test(l);" +
                "if(!audio&&!video&&!mp4Candidate)continue;" +
                "var score=(e.initiatorType==='video'||e.initiatorType==='media'?40:0)+(now-e.startTime<8000?20:0)+" +
                "(/\\.(m3u8|mpd)(?:[?#]|$)/i.test(l)?25:0);" +
                "var item={url:u,score:score,time:e.startTime};if(isThreadsHost)seenResources[u]=true;" +
                "if(audio){if(!bestAudio||item.score>bestAudio.score||(item.score===bestAudio.score&&item.time>bestAudio.time))bestAudio=item;}" +
                "else if(video&&!videoTracks.some(function(x){return x.url===u;})){videoTracks.push(item);}" +
                "else if(mp4Candidate)window.AboEzzatBridge.onCapturedCandidateTrack(u,titleFor(v),posterFor(v));" +
                "}" +
                "videoTracks.sort(function(a,b){return b.score-a.score||b.time-a.time;});" +
                "for(var j=0;j<Math.min(videoTracks.length,2);j++)window.AboEzzatBridge.onCapturedVideoTrack(videoTracks[j].url,titleFor(v),posterFor(v));" +
                "if(bestAudio)window.AboEzzatBridge.onCapturedAudioTrack(bestAudio.url,titleFor(v),posterFor(v));" +
                "}catch(e){}}" +
                "function pick(){try{var vids=[].slice.call(document.querySelectorAll('video'));" +
                "if(isThreadsHost&&!armed){var candidates=vids.filter(function(v){return !v.paused&&!v.ended&&v.readyState>=2&&visible(v);});" +
                "if(candidates.length){armed=true;activeSince=performance.now()-4000;seenResources={};}}" +
                "if(!armed)return;" +
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
                "document.addEventListener('playing',function(e){if(isThreadsHost&&e.target&&e.target.tagName==='VIDEO'&&visible(e.target)){armed=true;activeSince=performance.now()-4000;seenResources={};}" +
                "if(armed)setTimeout(pick,80);},true);" +
                "document.addEventListener('pause',function(){setTimeout(pick,250);},true);" +
                "window.addEventListener('scroll',function(){if(isThreadsHost||armed)setTimeout(pick,250);},true);" +
                "setInterval(function(){if(armed)pick();},900);" +
                "}catch(e){}})()";
        view.evaluateJavascript(js, null);
    }

    // Automatic generic player monitor. It is installed only on non-social web-player
    // pages (plus TikTok LIVE), so existing platform-specific sniffers remain isolated.
    private void injectGenericAutoCapture(WebView view) {
        String js = "javascript:(function(){try{" +
                "if(window.__aboGenericAutoCaptureInstalled)return;window.__aboGenericAutoCaptureInstalled=true;" +
                "function scan(w,seen){try{if(!w||seen.indexOf(w)!==-1)return;seen.push(w);var d=w.document;if(!d)return;" +
                "var title=d.title||document.title||'فيديو قيد التشغيل';" +
                "var vs=[].slice.call(d.querySelectorAll('video'));" +
                "for(var i=0;i<vs.length;i++){var v=vs[i],u=v.currentSrc||v.src||'';" +
                "if(!v.paused&&!v.ended&&v.readyState>=2&&/^https?:/i.test(u))window.AboEzzatBridge.onVideoPlayingWithPoster(u,title,v.poster||'');}" +
                "var es=w.performance&&w.performance.getEntriesByType?w.performance.getEntriesByType('resource'):[];" +
                "var now=w.performance&&w.performance.now?w.performance.now():0;" +
                "for(var j=0;j<es.length;j++){var e=es[j],u=e.name||'',l=u.toLowerCase();if(!/^https?:/i.test(u)||now-e.startTime>120000)continue;" +
                "if(/\\.(jpg|jpeg|png|webp|gif|bmp|svg|ts|m4s|js|css|woff2?)(?:[?#]|$)/i.test(l))continue;" +
                "var a=/mime(?:_|-)?type=audio|mime=audio|type=audio|content[_-]?type=audio|dash_ln_heaac|audio_dash|\\.(aac|m4a|mp3|opus|ogg)(?:[?#]|$)/i.test(l);" +
                "var v=/mime(?:_|-)?type=video|mime=video|type=video|content[_-]?type=video|video\\/|videoplayback|pull-|\\.(m3u8|mpd|mp4|m4v|webm|flv|mov)(?:[?#]|$)/i.test(l);" +
                "if(a)window.AboEzzatBridge.onCapturedAudioTrack(u,title,'');" +
                "else if(v){if(/\\.mp4(?:[?#]|$)/i.test(l))window.AboEzzatBridge.onCapturedCandidateTrack(u,title,'');" +
                "else window.AboEzzatBridge.onCapturedVideoTrack(u,title,'');}}" +
                "var fs=d.querySelectorAll('iframe,frame');for(var k=0;k<fs.length;k++){try{if(fs[k].contentWindow)scan(fs[k].contentWindow,seen);}catch(e){}}" +
                "}catch(e){}}" +
                "function tick(){scan(window,[]);}document.addEventListener('playing',tick,true);" +
                "document.addEventListener('loadedmetadata',tick,true);window.addEventListener('load',tick,true);" +
                "tick();setInterval(tick,1400);" +
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
        public void onActiveVideoTrack(String mediaUrl, String title, String posterUrl) {
            if ((activePlaybackKey.isEmpty() && !isManualSniffActive() && !isGenericAutoSniffActive()) || mediaUrl == null || !mediaUrl.startsWith("http")) return;
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
            if ((activePlaybackKey.isEmpty() && !isManualSniffActive() && !isGenericAutoSniffActive()) || mediaUrl == null || !mediaUrl.startsWith("http")) return;
            String clean = mediaUrl.replaceAll("&bytestart=\\d+", "")
                    .replaceAll("&byteend=\\d+", "");
            String cleanTitle = title == null || title.trim().isEmpty() ? "فيديو قيد التشغيل" : title.trim();
            addCapturedMedia(clean, cleanTitle, "🎬 مسار فيديو", posterUrl, 1);
        }

        @JavascriptInterface
        public void onCapturedCandidateTrack(String mediaUrl, String title, String posterUrl) {
            if ((activePlaybackKey.isEmpty() && !isManualSniffActive() && !isGenericAutoSniffActive()) || mediaUrl == null || !mediaUrl.startsWith("http")) return;
            probeAndAddMp4(mediaUrl, title, posterUrl);
        }

        @JavascriptInterface
        public void onCapturedAudioTrack(String mediaUrl, String title, String posterUrl) {
            if ((activePlaybackKey.isEmpty() && !isManualSniffActive() && !isGenericAutoSniffActive()) || mediaUrl == null || !mediaUrl.startsWith("http")) return;
            String clean = mediaUrl.replaceAll("&bytestart=\\d+", "")
                    .replaceAll("&byteend=\\d+", "");
            String cleanTitle = title == null || title.trim().isEmpty() ? "فيديو قيد التشغيل" : title.trim();
            addCapturedMedia(clean, cleanTitle, "🎵 مسار صوت منفصل", posterUrl, 2);
        }

        @JavascriptInterface
        public void onVideoPlayingWithPoster(String mediaUrl, String title, String posterUrl) {
            if ((activePlaybackKey.isEmpty() && !isGenericAutoSniffActive()) || mediaUrl == null || mediaUrl.isEmpty()
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
        boolean video = lower.matches(".*\\.(m4v|m3u8|mpd|webm|flv|mov)(?:[?#].*)?$")
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
        if ((mediaType != 1 && mediaType != 2) || (activePlaybackKey.isEmpty() && !isManualSniffActive() && !isGenericAutoSniffActive())) return;
        boolean added = false;
        synchronized (capturedList) {
            for (CapturedMedia item : capturedList) {
                if (item.url.equalsIgnoreCase(url)) {
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

    private void probeAndAddMp4(final String mediaUrl, final String title, final String posterUrl) {
        if (mediaUrl == null || !mp4ProbeRequests.add(mediaUrl)) return;
        AsyncTask.execute(() -> {
            int type = Mp4TrackInspector.inspect(mediaUrl);
            if (type == 0 || (activePlaybackKey.isEmpty() && !isManualSniffActive() && !isGenericAutoSniffActive())) return;
            String clean = mediaUrl.replaceAll("&bytestart=\\d+", "")
                    .replaceAll("&byteend=\\d+", "");
            String label = type == 2 ? "🎵 مسار صوت (تم فحص MP4)" : "🎬 مسار فيديو (تم فحص MP4)";
            addCapturedMedia(clean, title, label, posterUrl, type);
        });
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
        btnTabs.setContentDescription("علامات التبويب المفتوحة: " + tabList.size());
    }

    private boolean isBlockedAdUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
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
        // Apply compatibility before the first document script runs. This is scoped
        // to QFilm only so existing Facebook/Instagram/TikTok behavior is unchanged.
        if (isQFilmUrl(target)) {
            current.getSettings().setSupportMultipleWindows(false);
            current.getSettings().setUserAgentString(getQFilmCompatibleUserAgent());
        } else {
            current.getSettings().setSupportMultipleWindows(true);
            current.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36");
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

        // Arm only the generic manual-capture path for 45 seconds. This does not
        // modify share-sheet dispatch or the platform-specific automatic sniffers.
        manualSniffUntilMs = System.currentTimeMillis() + 45000L;
        clearCapturedMedia();
        Toast.makeText(this, "جاري فحص مشغل الموقع والتقاط مسارات الفيديو والصوت...", Toast.LENGTH_LONG).show();

        String js = "javascript:(function(){try{" +
                "var visited=[];" +
                "function scan(w){try{if(!w||visited.indexOf(w)!==-1)return;visited.push(w);" +
                "var d=w.document;if(d){" +
                "var vs=[].slice.call(d.querySelectorAll('video'));" +
                "vs.sort(function(a,b){var x=a.getBoundingClientRect(),y=b.getBoundingClientRect();return (y.width*y.height)-(x.width*x.height);});" +
                "for(var i=0;i<vs.length;i++){var v=vs[i],u=v.currentSrc||v.src||'';if(/^https?:/i.test(u))window.AboEzzatBridge.onActiveVideoTrack(u,d.title||document.title,v.poster||'');}" +
                "var es=w.performance&&w.performance.getEntriesByType?w.performance.getEntriesByType('resource'):[];" +
                "for(var j=0;j<es.length;j++){var u=es[j].name||'',l=u.toLowerCase();if(!/^https?:/i.test(u))continue;" +
                "if(/\\.(jpg|jpeg|png|webp|gif|bmp|svg|ts|m4s|js|css)(?:[?#]|$)/i.test(l))continue;" +
                "var a=/mime(?:_|-)?type=audio|mime=audio|type=audio|content[_-]?type=audio|dash_ln_heaac|audio_dash|\\.(aac|m4a|mp3|opus|ogg)(?:[?#]|$)/i.test(l);" +
                "var v=/mime(?:_|-)?type=video|mime=video|type=video|content[_-]?type=video|videoplayback|video\\/|\\.(m3u8|mpd|mp4|m4v|webm|flv|mov)(?:[?#]|$)/i.test(l);" +
                "if(a)window.AboEzzatBridge.onCapturedAudioTrack(u,d.title||document.title,'');" +
                "else if(v){if(/\\.mp4(?:[?#]|$)/i.test(l))window.AboEzzatBridge.onCapturedCandidateTrack(u,d.title||document.title,'');" +
                "else window.AboEzzatBridge.onCapturedVideoTrack(u,d.title||document.title,'');}" +
                "}" +
                "var fs=d.querySelectorAll('iframe,frame');for(var k=0;k<fs.length;k++){try{if(fs[k].contentWindow)scan(fs[k].contentWindow);}catch(e){}}" +
                "}" +
                "}catch(e){}}" +
                "scan(window);" +
                "}catch(e){}})()";
        current.evaluateJavascript(js, null);
    }

    private String buildCapturedMediaSummary() {
        int videoCount = 0;
        int audioCount = 0;
        int selectedCount = 0;
        synchronized (capturedList) {
            for (CapturedMedia media : capturedList) {
                if (media.mediaType == 1) videoCount++;
                else if (media.mediaType == 2) audioCount++;
                if (media.isSelected) selectedCount++;
            }
        }
        return "المسارات: " + capturedList.size() + " • فيديو: " + videoCount
                + " • صوت: " + audioCount + " • محدد: " + selectedCount;
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
        header.setText(buildCapturedMediaSummary());
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
                cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    item.isSelected = isChecked;
                    header.setText(buildCapturedMediaSummary());
                });
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

    private boolean isCapturedAudioTrack(CapturedMedia item) {
        if (item == null) return false;
        String label = item.format == null ? "" : item.format.toLowerCase(java.util.Locale.ROOT);
        return item.mediaType == 2 || item.isAudio || label.contains("audio")
                || label.contains("مسار صوت") || label.contains("صوت فقط");
    }

    private void downloadSelectedMedia(boolean audioOnly) {
        CapturedMedia bestVideo = null;
        CapturedMedia selectedAudio = null;
        CapturedMedia bestAvailableAudio = null;
        int audioTrackCount = 0;
        CapturedMedia onlyAudioTrack = null;

        synchronized (capturedList) {
            for (CapturedMedia item : capturedList) {
                if (isCapturedAudioTrack(item)) {
                    audioTrackCount++;
                    onlyAudioTrack = item;
                    if (bestAvailableAudio == null || capturedUrlConfidence(item.url, 2)
                            > capturedUrlConfidence(bestAvailableAudio.url, 2)) {
                        bestAvailableAudio = item;
                    }
                }
                if (!item.isSelected) continue;

                if (isCapturedAudioTrack(item)) {
                    if (selectedAudio == null || capturedUrlConfidence(item.url, 2)
                            > capturedUrlConfidence(selectedAudio.url, 2)) {
                        selectedAudio = item;
                    }
                } else if (item.mediaType == 1 && (bestVideo == null
                        || capturedVideoQualityScore(item.url) > capturedVideoQualityScore(bestVideo.url))) {
                    bestVideo = item;
                }
            }
        }

        // A single captured audio rendition is unambiguous: allow the audio button
        // to use it even if a checkbox state was lost while the dialog was refreshed.
        if (audioOnly && selectedAudio == null && audioTrackCount == 1) {
            selectedAudio = onlyAudioTrack;
        }

        if (audioOnly) {
            if (selectedAudio == null) {
                Toast.makeText(this, "لم يتم العثور على مسار صوت صالح في القائمة. افتح قائمة المسارات وتأكد من ظهور مسار الصوت.", Toast.LENGTH_LONG).show();
                return;
            }
            downloadMedia(selectedAudio.url, true, "audio/mp4");
            return;
        }

        if (bestVideo == null) {
            Toast.makeText(this, "حدد مسار فيديو من مربعات الاختيار أولاً", Toast.LENGTH_LONG).show();
            return;
        }

        // The video-download action means a complete video: if the page exposed
        // a separate audio rendition, prefer it even if its checkbox was toggled off.
        if (selectedAudio == null) selectedAudio = bestAvailableAudio;

        WebView current = getCurrentWebView();
        String referer = current == null || current.getUrl() == null ? "" : current.getUrl();
        if (selectedAudio != null) {
            Toast.makeText(this, "تم تحديد الفيديو والصوت — جاري تنزيلهما ودمجهما", Toast.LENGTH_SHORT).show();
            BrowserFileDownloader.startMux(this, bestVideo.url, selectedAudio.url, referer, bestVideo.title);
        } else {
            downloadMedia(bestVideo.url, false);
        }
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
                        downloadSelectedMedia(false);
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
        JSONObject payload = new JSONObject();
        if (videoUrl != null && !videoUrl.trim().isEmpty()) payload.put("url", videoUrl);
        if (audioUrl != null && !audioUrl.trim().isEmpty()) payload.put("audio", audioUrl);
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"), payload.toString());
        return new Request.Builder().url(endpoint + "/play").post(body).build();
    }

    private void mergeAndCastToTv() {
        String youtubePage = getCurrentYouTubePageUrl();
        if (youtubePage != null) {
            castToTv(youtubePage);
            return;
        }
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

            Request req = buildReceiverPlayRequest(endpoint, videoTrackUrl, audioTrackUrl);
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

                private void confirmReceiverAfterAmbiguousFailure() {
                    Request ping = new Request.Builder()
                            .url(receiverEndpoint + "/?command=ping")
                            .get()
                            .build();
                    client.newCall(ping).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call pingCall, IOException pingError) {
                            showResult(requestHeadersSent.get()
                                    ? "تم إرسال طلب الفيديو والصوت، لكن تعذر تأكيد رد الشاشة"
                                    : "تعذر الإرسال للشاشة، تأكد من الاتصال");
                        }

                        @Override
                        public void onResponse(Call pingCall, Response pingResponse) {
                            boolean receiverAlive = pingResponse.isSuccessful();
                            pingResponse.close();
                            if (receiverAlive && requestHeadersSent.get()) {
                                showResult("تم إرسال الفيديو عالي الجودة مع الصوت ✓");
                            } else if (receiverAlive) {
                                showResult("الشاشة متصلة، لكن لم يتأكد إرسال مساري الوسائط");
                            } else {
                                showResult("تعذر تأكيد استقبال مساري الفيديو والصوت");
                            }
                        }
                    });
                }

                @Override
                public void onFailure(Call call, IOException e) {
                    confirmReceiverAfterAmbiguousFailure();
                }

                @Override
                public void onResponse(Call call, Response response) {
                    boolean accepted = response.isSuccessful();
                    response.close();
                    if (accepted) {
                        showResult("تم إرسال الفيديو عالي الجودة مع الصوت ✓");
                    } else {
                        confirmReceiverAfterAmbiguousFailure();
                    }
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "خطأ في الرابط", Toast.LENGTH_SHORT).show();
        }
    }

    private String getCurrentYouTubePageUrl() {
        WebView current = getCurrentWebView();
        if (current == null) return null;
        String page = current.getUrl();
        if (page == null || page.trim().isEmpty()) return null;
        try {
            Uri uri = Uri.parse(page.trim());
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("youtu.be") || host.equals("youtube.com")
                    || host.endsWith(".youtube.com") || host.equals("youtube-nocookie.com")
                    || host.endsWith(".youtube-nocookie.com")) return page.trim();
        } catch (Exception ignored) { }
        return null;
    }

    private void castToTv(String mediaUrl) {
        String youtubePage = getCurrentYouTubePageUrl();
        if (youtubePage != null) mediaUrl = youtubePage;
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

            Request req = buildReceiverPlayRequest(endpoint, mediaUrl, null);
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
        downloadMedia(mediaUrl, audioOnly, null);
    }

    private void downloadMedia(String mediaUrl, boolean audioOnly, String hintedMime) {
        if (mediaUrl == null || mediaUrl.trim().isEmpty()) {
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
        BrowserFileDownloader.start(this, cleanUrl, type, hintedMime, referer);
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
                        clearBrowserSiteData();
                    }
                })
                .show();
    }

    private void openDownloadsFolder() {
        showBrowserDownloadsDialog();
    }

    private void showBrowserDownloadsDialog() {
        showBrowserDownloadsDialog("all");
    }

    private void showBrowserDownloadsDialog(String initialCategory) {
        final String key = "in_app_download_items";
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        org.json.JSONArray items;
        try { items = new org.json.JSONArray(prefs.getString(key, "[]")); }
        catch (Exception ignored) { items = new org.json.JSONArray(); }

        final List<org.json.JSONObject> records = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            org.json.JSONObject item = items.optJSONObject(i);
            if (item != null) records.add(item);
        }
        // Keep the newest downloads at the top, regardless of when their status changed.
        Collections.sort(records, (left, right) ->
                Long.compare(right.optLong("created", right.optLong("id", 0L)),
                        left.optLong("created", left.optLong("id", 0L))));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(8), dp(14), dp(8));
        content.setBackgroundColor(Color.rgb(12, 18, 32));

        TextView summary = new TextView(this);
        int completeCount = 0;
        int downloadingCount = 0;
        int failedCount = 0;
        for (org.json.JSONObject record : records) {
            String recordStatus = record.optString("status", "failed");
            if ("complete".equals(recordStatus)) completeCount++;
            else if ("downloading".equals(recordStatus)) downloadingCount++;
            else failedCount++;
        }
        summary.setText(records.size() + " عنصر • مكتمل: " + completeCount
                + " • جارٍ: " + downloadingCount + " • فشل: " + failedCount);
        summary.setTextColor(Color.LTGRAY);
        summary.setTextSize(12);
        summary.setPadding(dp(4), dp(4), dp(4), dp(10));
        content.addView(summary);

        android.widget.HorizontalScrollView filterScroll = new android.widget.HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        final String[] categories = {"all", "video", "audio", "image", "file"};
        final String[] categoryLabels = {"الكل", "فيديو", "صوت", "صور", "ملفات"};
        final String[] selected = {initialCategory == null ? "all" : initialCategory};
        filterScroll.addView(filters);
        content.addView(filterScroll);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(rows);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(390));
        scrollParams.topMargin = dp(8);
        content.addView(scroll, scrollParams);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("مدير تنزيلات AboEzzat Cast")
                .setView(content)
                .setNegativeButton("إغلاق", null)
                .create();

        Runnable renderRows = () -> {
            rows.removeAllViews();
            int visibleCount = 0;
            for (int i = 0; i < records.size(); i++) {
                final org.json.JSONObject item = records.get(i);
                String type = browserDownloadCategory(item);
                if (!"all".equals(selected[0]) && !selected[0].equals(type)) continue;
                visibleCount++;

                String status = item.optString("status", "failed");
                String state;
                if ("complete".equals(status)) {
                    long bytes = item.optLong("bytes", 0L);
                    state = bytes > 0 ? "اكتمل التنزيل • " + formatBrowserDownloadSize(bytes) : "ملف غير صالح";
                } else if ("downloading".equals(status)) {
                    int progress = item.optInt("progress", -1);
                    state = progress >= 0 ? "جارٍ التنزيل • " + progress + "%" : "جارٍ الاتصال...";
                } else {
                    state = "فشل التنزيل • " + item.optString("error", "تعذر التنزيل");
                }

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
                cardBg.setColor(Color.rgb(25, 35, 55));
                cardBg.setCornerRadius(dp(12));
                cardBg.setStroke(dp(1), Color.rgb(48, 64, 91));
                card.setBackground(cardBg);
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardParams.bottomMargin = dp(8);
                rows.addView(card, cardParams);

                LinearLayout heading = new LinearLayout(this);
                heading.setOrientation(LinearLayout.HORIZONTAL);
                heading.setGravity(Gravity.CENTER_VERTICAL);
                TextView typeIcon = new TextView(this);
                typeIcon.setText("video".equals(type) ? "🎬" : "audio".equals(type) ? "🎵"
                        : "image".equals(type) ? "🖼️" : "📄");
                typeIcon.setTextSize(22);
                heading.addView(typeIcon, new LinearLayout.LayoutParams(dp(38), ViewGroup.LayoutParams.WRAP_CONTENT));

                LinearLayout textColumn = new LinearLayout(this);
                textColumn.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(this);
                name.setText(item.optString("title", item.optString("file", "ملف بدون اسم")));
                name.setTextColor(Color.WHITE);
                name.setTextSize(14);
                name.setMaxLines(2);
                TextView fileName = new TextView(this);
                fileName.setText(item.optString("file", "ملف"));
                fileName.setTextColor(Color.rgb(165, 178, 198));
                fileName.setTextSize(11);
                fileName.setMaxLines(1);
                fileName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                textColumn.addView(name);
                textColumn.addView(fileName);
                heading.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView more = new TextView(this);
                more.setText("⋮");
                more.setTextSize(25);
                more.setTextColor(Color.WHITE);
                more.setGravity(Gravity.CENTER);
                heading.addView(more, new LinearLayout.LayoutParams(dp(34), dp(42)));
                card.addView(heading);

                TextView stateText = new TextView(this);
                stateText.setText(state);
                stateText.setTextColor("complete".equals(status) ? Color.rgb(105, 220, 145)
                        : "failed".equals(status) ? Color.rgb(255, 145, 125) : Color.rgb(255, 205, 105));
                stateText.setTextSize(12);
                stateText.setPadding(dp(38), dp(5), 0, 0);
                card.addView(stateText);

                if ("downloading".equals(status)) {
                    ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                    progress.setMax(100);
                    int value = item.optInt("progress", -1);
                    if (value >= 0) progress.setProgress(value);
                    else progress.setIndeterminate(true);
                    LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
                    progressParams.setMargins(dp(38), dp(7), 0, 0);
                    card.addView(progress, progressParams);
                }

                View.OnClickListener actions = v -> {
                    final String uriText = item.optString("uri", "");
                    final String itemStatus = item.optString("status", "");
                    final boolean downloading = "downloading".equals(itemStatus);
                    final boolean complete = "complete".equals(itemStatus)
                            && item.optLong("bytes", 0L) > 0 && !uriText.isEmpty();
                    String[] actionsList = complete
                            ? new String[]{"فتح الملف", "مشاركة الملف", "حذف الملف"}
                            : downloading
                                    ? new String[]{"عرض التفاصيل"}
                                    : new String[]{"إعادة المحاولة", "عرض التفاصيل", "إزالة من القائمة"};
                    new AlertDialog.Builder(this).setTitle(item.optString("file", "التنزيل"))
                            .setItems(actionsList, (actionDialog, choice) -> {
                                if (!complete && !downloading && choice == 0) {
                                    String retryUrl = item.optString("url", "");
                                    if (retryUrl.isEmpty()) {
                                        Toast.makeText(this, "هذا التنزيل لا يحتوي على رابط مباشر لإعادة المحاولة", Toast.LENGTH_LONG).show();
                                    } else {
                                        org.json.JSONArray updated = new org.json.JSONArray();
                                        for (org.json.JSONObject record : records) {
                                            if (record != item) updated.put(record);
                                        }
                                        prefs.edit().putString(key, updated.toString()).apply();
                                        WebView current = getCurrentWebView();
                                        String retryReferer = current == null || current.getUrl() == null ? "" : current.getUrl();
                                        BrowserFileDownloader.start(this, retryUrl,
                                                item.optString("type", "video"),
                                                item.optString("mime", "video/mp4"),
                                                retryReferer);
                                        dialog.dismiss();
                                        showBrowserDownloadsDialog(selected[0]);
                                    }
                                } else if (complete && choice == 0) {
                                    try {
                                        Intent open = new Intent(Intent.ACTION_VIEW);
                                        open.setDataAndType(Uri.parse(uriText), item.optString("mime", "*/*"));
                                        open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                        startActivity(open);
                                    } catch (Exception e) {
                                        Toast.makeText(this, "لا يوجد تطبيق مناسب لفتح الملف", Toast.LENGTH_SHORT).show();
                                    }
                                } else if (complete && choice == 1) {
                                    try {
                                        Intent share = new Intent(Intent.ACTION_SEND);
                                        share.setType(item.optString("mime", "*/*"));
                                        share.putExtra(Intent.EXTRA_STREAM, Uri.parse(uriText));
                                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                        startActivity(Intent.createChooser(share, "مشاركة الملف"));
                                    } catch (Exception e) {
                                        Toast.makeText(this, "تعذرت مشاركة الملف", Toast.LENGTH_SHORT).show();
                                    }
                                } else if ((complete && choice == 2) || (!complete && !downloading && choice == 2)) {
                                    BrowserFileDownloader.deleteFile(this, item);
                                    org.json.JSONArray updated = new org.json.JSONArray();
                                    for (org.json.JSONObject record : records) {
                                        if (record != item) updated.put(record);
                                    }
                                    prefs.edit().putString(key, updated.toString()).apply();
                                    dialog.dismiss();
                                    showBrowserDownloadsDialog(selected[0]);
                                } else {
                                    String detail = "الحالة: " + item.optString("status", "غير معروفة")
                                            + "\nالحجم: " + formatBrowserDownloadSize(item.optLong("bytes", 0L))
                                            + (item.optString("error", "").isEmpty() ? "" : "\nالسبب: " + item.optString("error"));
                                    new AlertDialog.Builder(this).setMessage(detail).setPositiveButton("حسنًا", null).show();
                                }
                            }).setNegativeButton("إلغاء", null).show();
                };
                card.setOnClickListener(actions);
                more.setOnClickListener(actions);
            }
            if (visibleCount == 0) {
                TextView empty = new TextView(this);
                empty.setText("لا توجد عناصر في هذا القسم حتى الآن");
                empty.setTextColor(Color.LTGRAY);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(12), dp(30), dp(12), dp(30));
                rows.addView(empty);
            }
            summary.setText(visibleCount + " في القسم • مكتمل: " + completeCount
                    + " • جارٍ: " + downloadingCount + " • فشل: " + failedCount
                    + " • الإجمالي: " + records.size());
        };

        for (int i = 0; i < categories.length; i++) {
            final String category = categories[i];
            Button filter = new Button(this);
            filter.setText(categoryLabels[i]);
            filter.setAllCaps(false);
            filter.setTextSize(12);
            filter.setTextColor(Color.WHITE);
            filter.setMinHeight(0);
            filter.setPadding(dp(10), 0, dp(10), 0);
            filter.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    category.equals(selected[0]) ? Color.rgb(0, 120, 190) : Color.rgb(37, 49, 70)));
            LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            filterParams.setMargins(dp(3), dp(3), dp(3), dp(3));
            filters.addView(filter, filterParams);
            filter.setOnClickListener(v -> {
                selected[0] = category;
                for (int j = 0; j < filters.getChildCount(); j++) {
                    Button button = (Button) filters.getChildAt(j);
                    button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                            categoryLabels[j].equals(button.getText().toString())
                                    ? Color.rgb(0, 120, 190) : Color.rgb(37, 49, 70)));
                }
                renderRows.run();
            });
        }

        renderRows.run();
        dialog.show();
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
        AlertDialog dialog = new AlertDialog.Builder(this)
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
