package com.aboezzat.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.Response;
import org.json.JSONObject;

public class ShareActivity extends Activity {

    private static final int TARGET_PORT = 8081;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private FrameLayout rootLayout;
    private TextView tikTokDiagnosticStatus;
    private TextView tikTokDiagnosticUrl;
    private TextView tikTokDiagnosticAgent;
    private Button tikTokSendButton;
    private boolean isTikTokDiagnosticVisible = false;
    private boolean isCaptured = false;
    private String pendingTikTokLiveUrl = "";
    private String targetTvIp = "";
    // Threads can expose video and audio as separate DASH resources. Collect both
    // before sending so the Receiver receives a synchronized A/V pair.
    private String threadsSharedUrl = "";
    private String threadsVideoUrl = "";
    private String threadsAudioUrl = "";
    private boolean threadsCaptureTimerScheduled = false;
    private final Set<String> threadsMp4ProbeRequests = new HashSet<>();
    private final Map<String, String> tikTokLiveRequestHeaders = new HashMap<>();
    // Separate request headers for ordinary TikTok VOD/CDN captures.
    private final Map<String, String> tikTokRequestHeaders = new HashMap<>();
    private volatile String tikTokRequestSourceUrl = "";
    private volatile boolean tikTokLiveCaptureActive = false;
    private volatile String tikTokLiveSourceUrl = "";
    // Wait for the player to actually start before accepting a TikTok CDN URL.
    private volatile boolean tikTokLivePlaybackStarted = false;
    private String tikTokLiveCandidateUrl = "";
    private long tikTokLiveCandidateGeneration = 0L;
    private Runnable tikTokLiveCandidateFinalize;
    private final Runnable threadsCaptureTimeout = () -> finishThreadsCapture();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.TRANSPARENT);
        ProgressBar progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = android.view.Gravity.CENTER;
        rootLayout.addView(progressBar, params);
        setContentView(rootLayout);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) {
                String extractedUrl = extractUrl(sharedText);
                if (extractedUrl != null) {
                    dispatchPlatformRoute(extractedUrl);
                    return;
                }
            }
        }

        if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            ArrayList<Uri> mediaUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (mediaUris != null && !mediaUris.isEmpty()) {
                sendMultipleMedia(mediaUris);
                return;
            }
        }

        finish();
    }

    private String extractUrl(String text) {
        // Accept both fully-qualified links and copied links beginning with www.
        if (text == null || text.length() > 8192) return null;
        Pattern pattern = Pattern.compile("(?i)((?:https?://|www\\.)[\\w\\-\\.\\?\\=\\&\\%\\/\\#\\+]+)");
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        String url = matcher.group(1);
        if (url.regionMatches(true, 0, "www.", 0, 4)) url = "https://" + url;
        if (url.length() > 4096) return null;
        try {
            Uri parsed = Uri.parse(url);
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null || host.trim().isEmpty()
                    || parsed.getUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
            for (int i = 0; i < host.length(); i++) {
                char ch = host.charAt(i);
                if (!(Character.isLetterOrDigit(ch) || ch == '.' || ch == '-')) return null;
            }
            return url;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void dispatchPlatformRoute(String url) {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        targetTvIp = prefs.getString("tv_ip", "").trim();

        Uri parsedUrl = Uri.parse(url);
        String host = parsedUrl.getHost() == null ? "" : parsedUrl.getHost().toLowerCase(Locale.ROOT);

        if (host.equals("instagram.com") || host.endsWith(".instagram.com")) {
            Intent browserIntent = new Intent(this, BrowserActivity.class);
            browserIntent.setAction(Intent.ACTION_VIEW);
            browserIntent.setData(Uri.parse(url));
            startActivity(browserIntent);
            finish();
            return;
        }

        // Threads shared links belong to the full in-app browser capture flow.
        // Keep this separate from ShareActivity's background/direct media handlers.
        if (host.equals("threads.net") || host.endsWith(".threads.net")
                || host.equals("threads.com") || host.endsWith(".threads.com")) {
            openThreadsInBrowser(url);
            return;
        }

        if (targetTvIp.isEmpty()) {
            Toast.makeText(this, "يرجى تعيين عنوان IP الشاشة أولاً", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // This three-segment index.html URL is itself a live playlist endpoint.
        // Do not wait for WebView media sniffing: forward this known URL to Receiver,
        // which will hand it to VLC (and use ExoPlayer if VLC is unavailable).
        if (isSwiftDataDramaLiveUrl(url)) {

            sendToTv(targetTvIp, url, false, url);
            return;
        }

        if (host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtu.be") || host.equals("youtube-nocookie.com")
                || host.endsWith(".youtube-nocookie.com")) {

            YoutubeDirectExtractor.extract(url, new YoutubeDirectExtractor.Callback() {
                @Override public void onSuccess(String videoUrl, String audioUrl, String title, String quality) {
                    if (isFinishing() || videoUrl == null || videoUrl.trim().isEmpty()) return;
                    // Send extracted media renditions only. A separate audio URL is
                    // included when YouTube's highest-quality video is video-only.
                    sendToTv(targetTvIp, videoUrl, audioUrl, false, url);
                }

                @Override public void onError(String reason) {
                    if (isFinishing()) return;
                    Toast.makeText(ShareActivity.this,
                            "تعذر استخراج فيديو YouTube على الهاتف: " + reason,
                            Toast.LENGTH_LONG).show();
                    finish();
                }
            });
            return;
        }

        // TikTok LIVE canonical links can be shared as vt.tiktok.com short URLs.
        // Resolve short links first so LIVE is detected without changing ordinary-video handling.
        if (isTikTokShortShareUrl(url)) {
            resolveTikTokShortLink(url);
            return;
        }

        if (isTikTokLiveUrl(url)) {
            // Capture LIVE in the background and send its stable CDN URL directly.
            tikTokLiveSourceUrl = url;
            startTikTokLiveLegacySniffer(url);
            return;
        }

        if (host.equals("tiktok.com") || host.endsWith(".tiktok.com")) {

            handleTikTok(url);
            return;
        }

        startPlatformSniffer(url, "generic");
    }

    private boolean isSwiftDataDramaLiveUrl(String value) {
        if (value == null) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && path != null
                    && path.toLowerCase(Locale.ROOT).matches("^/[^/]+/[^/]+/index\\.html$");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTikTokLiveUrl(String value) {
        if (value == null) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            return host.equals("live.tiktok.com")
                    || ((host.equals("tiktok.com") || host.endsWith(".tiktok.com"))
                    && path.matches("(?i)^/@[^/]+/live/?$"));
        } catch (Exception ignored) {
            return false;
        }
    }

    // A LIVE page may deliver its stream from a TikTok CDN host that does not
    // contain the word "tiktok". Match the stream signatures consistently in
    // both WebView interception and the JavaScript resource scanner.
    private boolean isTikTokLiveStreamUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("pull-") || lower.contains(".m3u8") || lower.contains(".flv");
    }

    // TikTok VOD can use a signed /video/tos/ URL whose MP4 type is declared
    // in the query string instead of a literal .mp4 file extension.
    private boolean isTikTokVodVideoUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String query = uri.getEncodedQuery() == null ? "" : uri.getEncodedQuery().toLowerCase(Locale.ROOT);
            boolean tiktokHost = host.equals("tiktok.com") || host.endsWith(".tiktok.com")
                    || host.equals("tiktokcdn.com") || host.endsWith(".tiktokcdn.com");
            boolean videoPath = path.contains("/video/tos/");
            boolean explicitMp4 = query.contains("mime_type=video_mp4")
                    || query.contains("mime-type=video_mp4")
                    || path.matches(".*\\\\.mp4$");
            return "https".equalsIgnoreCase(uri.getScheme()) && tiktokHost
                    && videoPath && explicitMp4;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void captureTikTokRequestHeaders(WebResourceRequest request, String mediaUrl) {
        if (request == null || mediaUrl == null) return;
        synchronized (tikTokRequestHeaders) {
            Map<String, String> requestHeaders = request.getRequestHeaders();
            if (requestHeaders != null) {
                for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (key == null || value == null || value.isEmpty()) continue;
                    String lower = key.toLowerCase(Locale.ROOT);
                    if (("user-agent".equals(lower) || "referer".equals(lower)
                            || "origin".equals(lower) || "accept".equals(lower)
                            || "accept-language".equals(lower) || "sec-fetch-site".equals(lower)
                            || "sec-fetch-mode".equals(lower) || "sec-fetch-dest".equals(lower)
                            || "sec-ch-ua".equals(lower) || "sec-ch-ua-mobile".equals(lower)
                            || "sec-ch-ua-platform".equals(lower))
                            && value.length() <= 2048 && !value.contains("\r") && !value.contains("\n")) {
                        tikTokRequestHeaders.put(lower, value);
                    }
                }
            }
            String cookie = CookieManager.getInstance().getCookie(mediaUrl);
            if (cookie != null && !cookie.isEmpty() && cookie.length() <= 8192
                    && !cookie.contains("\r") && !cookie.contains("\n")) {
                tikTokRequestHeaders.put("cookie", cookie);
            }
        }
    }

    private boolean isTikTokShortShareUrl(String value) {
        if (value == null) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            return host.equals("vt.tiktok.com") || host.equals("vm.tiktok.com")
                    || ((host.equals("tiktok.com") || host.endsWith(".tiktok.com"))
                    && path.matches("(?i)^/t/[^/]+/?$"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void resolveTikTokShortLink(final String shortUrl) {
        Request request = new Request.Builder()
                .url(shortUrl)
                .header("User-Agent", getConfiguredBrowserUserAgent())
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // If a short-link redirect cannot be resolved, let the hidden
                // WebView resolve it and capture either VOD MP4 or LIVE FLV/HLS.
                tikTokRequestSourceUrl = shortUrl;
                synchronized (tikTokRequestHeaders) {
                    tikTokRequestHeaders.clear();
                }
                mainHandler.post(() -> startPlatformSniffer(shortUrl, "generic", 45000L, false));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String resolvedUrl = response.request().url().toString();
                response.close();

                mainHandler.post(() -> {
                    if (isTikTokLiveUrl(resolvedUrl)) {
                        tikTokLiveSourceUrl = resolvedUrl;
                        startTikTokLiveLegacySniffer(resolvedUrl);
                    } else {
                        // Ordinary TikTok posts retain their original Tikwm route.
                        handleTikTok(shortUrl);
                    }
                });
            }
        });
    }

    private void handleTikTok(final String url) {
        try {
            String apiUrl = "https://www.tikwm.com/api/?url=" + URLEncoder.encode(url, "UTF-8");
            Request request = new Request.Builder().url(apiUrl).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    mainHandler.post(() -> startTikTokVodFallback(url));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        if (json.optInt("code") == 0 && json.has("data")) {
                            JSONObject data = json.getJSONObject("data");
                            String play = data.optString("play");
                            if (!play.isEmpty()) {
                                if (!play.startsWith("http")) play = "https://www.tikwm.com" + play;
                                // Send the extracted TikTok VOD URL through the Receiver playback
                                // endpoint, keeping long CDN URLs out of the HTTP request line.
                                sendToTv(targetTvIp, play, false, url);
                                return;
                            }
                        }
                    } catch (Exception ignored) {}
                    mainHandler.post(() -> startTikTokVodFallback(url));
                }
            });
        } catch (Exception e) {
            startTikTokVodFallback(url);
        }
    }

    // Restore the original TikTok ordinary-video fallback exactly: a detached,
    // hidden WebView using the historical Android Chrome 122 User-Agent.
    @SuppressLint("SetJavaScriptEnabled")
    private void startTikTokVodFallback(String url) {
        isCaptured = false;
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(getConfiguredBrowserUserAgent());

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onMediaFound(String mediaUrl) {
                checkAndSendMedia(mediaUrl, "tiktok_vod");
            }
        }, "MediaSniffer");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();
                checkAndSendMedia(reqUrl, "tiktok_vod");
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                super.onPageFinished(view, loadedUrl);
                String js = "(function() { " +
                        "   try { " +
                        "       var vids = document.getElementsByTagName('video'); " +
                        "       for (var i = 0; i < vids.length; i++) { " +
                        "           vids[i].muted = true; " +
                        "           vids[i].play(); " +
                        "           var src = vids[i].currentSrc || vids[i].src; " +
                        "           if (src && src.startsWith('http')) { " +
                        "               window.MediaSniffer.onMediaFound(src); " +
                        "           } " +
                        "       } " +
                        "   } catch(e){} " +
                        "})();";
                view.evaluateJavascript(js, null);
            }
        });

        webView.loadUrl(url);
        mainHandler.postDelayed(() -> {
            if (!isCaptured) {
                isCaptured = true;
                destroyWebView();
                Toast.makeText(ShareActivity.this,
                        "تعذر القنص المباشر، جرب فتحه بالمتصفح", Toast.LENGTH_SHORT).show();
                finish();
            }
        }, 16000L);
    }

    // The old Receiver-compatible TikTok path is a plain GET query.
    private void sendTikTokLegacyToTv(String tvIp, String mediaUrl) {
        try {
            String endpoint = ReceiverApiClient.buildEndpoint(tvIp);
            OkHttpClient receiverClient = ReceiverApiClient.create(this, Uri.parse(endpoint).getHost());
            String fullUrl = endpoint + "/?url=" + URLEncoder.encode(mediaUrl, "UTF-8");
            Request request = new Request.Builder().url(fullUrl).get().build();
            receiverClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException error) {
                    mainHandler.post(() -> {
                        Toast.makeText(ShareActivity.this, "تعذر الإرسال للشاشة", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) {
                    mainHandler.post(() -> {
                        Toast.makeText(ShareActivity.this,
                                "تم الإرسال والتشغيل بنجاح ✓", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    response.close();
                }
            });
        } catch (Exception e) {
            mainHandler.post(this::finish);
        }
    }

    // TikTok LIVE has its own saved User-Agent preference, independent from
    // the normal browser preference. Keep Android Chrome 122 as the legacy default.
    private String getTikTokLiveUserAgent() {
        String configured = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE)
                .getString("tiktok_live_user_agent", "").trim();
        if (!configured.isEmpty()) return configured;
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";
    }

    // Normal browser/TikTok VOD capture follows the browser's own saved selection.
    private String getConfiguredBrowserUserAgent() {
        String configured = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE)
                .getString("browser_user_agent", "").trim();
        if (!configured.isEmpty()) return configured;
        String systemDefault = WebSettings.getDefaultUserAgent(this);
        return systemDefault == null || systemDefault.trim().isEmpty()
                ? "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                : systemDefault;
    }

    // Historical TikTok LIVE capture: a detached hidden WebView, Android Chrome 122,
    // immediate FLV/HLS detection, and BrowserActivity fallback after five seconds.
    @SuppressLint("SetJavaScriptEnabled")
    private void startTikTokLiveLegacySniffer(String url) {
        isCaptured = false;
        tikTokLiveCaptureActive = false;
        tikTokLivePlaybackStarted = false;
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(getTikTokLiveUserAgent());

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onMediaFound(String mediaUrl) {
                checkAndSendMedia(mediaUrl, "tiktok_live");
            }
        }, "MediaSniffer");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                checkAndSendMedia(request.getUrl().toString(), "tiktok_live");
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                super.onPageFinished(view, loadedUrl);
                String js = "(function() { " +
                        "   try { " +
                        "       var vids = document.getElementsByTagName('video'); " +
                        "       for (var i = 0; i < vids.length; i++) { " +
                        "           vids[i].muted = true; " +
                        "           vids[i].play(); " +
                        "           var src = vids[i].currentSrc || vids[i].src; " +
                        "           if (src && src.startsWith('http')) { " +
                        "               window.MediaSniffer.onMediaFound(src); " +
                        "           } " +
                        "       } " +
                        "   } catch(e){} " +
                        "})();";
                view.evaluateJavascript(js, null);
            }
        });

        webView.loadUrl(url);
        mainHandler.postDelayed(() -> {
            if (!isCaptured) {
                isCaptured = true;
                destroyWebView();
                openTikTokLiveInBrowser(url);
            }
        }, 5000L);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startPlatformSniffer(String url, final String platformType) {
        startPlatformSniffer(url, platformType, 16000L, false);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startPlatformSniffer(String url, final String platformType,
                                      long timeoutMs, boolean openBrowserOnTimeout) {
        isCaptured = false;
        if ("tiktok_live".equals(platformType)) {
            tikTokLivePlaybackStarted = false;
            tikTokLiveCandidateUrl = "";
            tikTokLiveCandidateGeneration++;
            if (tikTokLiveCandidateFinalize != null) {
                mainHandler.removeCallbacks(tikTokLiveCandidateFinalize);
                tikTokLiveCandidateFinalize = null;
            }
            // Start a fresh header snapshot for this shared LIVE session. Individual
            // CDN segment requests must merge into it, not erase earlier headers.
            synchronized (tikTokLiveRequestHeaders) {
                tikTokLiveRequestHeaders.clear();
            }
        }
        if ("threads".equals(platformType)) {
            threadsSharedUrl = url;
            threadsVideoUrl = "";
            threadsAudioUrl = "";
            threadsMp4ProbeRequests.clear();
            threadsCaptureTimerScheduled = false;
            mainHandler.removeCallbacks(threadsCaptureTimeout);
        }
        webView = new WebView(this);
        // Keep the capture WebView attached so Chromium actually loads the page,
        // executes the sniffer and requests media. A detached WebView can sit in
        // a permanent loading state and never expose FLV/HLS requests.
        FrameLayout.LayoutParams captureParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rootLayout.addView(webView, 0, captureParams);
        webView.setAlpha(0.01f);
        webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        // The capture page stays visually hidden; the existing progress indicator remains above it.
        isTikTokDiagnosticVisible = false;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // WebViews in this app share a persistent cookie store; accept TikTok's
        // first-party and embedded-player cookies for the signed-in browser session.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        String captureUserAgent = "tiktok_live".equals(platformType)
                ? getTikTokLiveUserAgent()
                : getConfiguredBrowserUserAgent();
        settings.setUserAgentString(captureUserAgent);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onMediaFound(String mediaUrl) {
                checkAndSendMedia(mediaUrl, platformType);
            }

            @JavascriptInterface
            public void onPlayerStarted() {
                if (!"tiktok_live".equals(platformType)) return;
                synchronized (ShareActivity.this) {
                    tikTokLivePlaybackStarted = true;
                    scheduleTikTokLiveCandidateCapture();
                }
            }
        }, "MediaSniffer");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String pageUrl, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, pageUrl, favicon);
                if ("tiktok_live".equals(platformType)) {
                    updateTikTokDiagnosticStatus("TikTok LIVE: جاري تحميل الصفحة...");
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if ("tiktok_live".equals(platformType) && request.isForMainFrame()) {
                    updateTikTokDiagnosticStatus("حصل خطأ أثناء تحميل صفحة TikTok. تقدر تشوف الصفحة وتحاول تاني.");
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();
                if ("generic".equals(platformType)
                        && (isTikTokVodVideoUrl(reqUrl) || isTikTokLiveStreamUrl(reqUrl))) {
                    captureTikTokRequestHeaders(request, reqUrl);
                }
                if ("tiktok_live".equals(platformType)) {
                    if (isTikTokLiveStreamUrl(reqUrl)) {
                        updateTikTokDiagnosticStatus("تم رصد طلب فيديو من TikTok CDN. جاري التقاط بياناته...");
                        synchronized (tikTokLiveRequestHeaders) {
                            // Preserve headers already captured from the initial media request;
                            // later FLV/HLS segment requests may omit Referer or client hints.
                            Map<String, String> requestHeaders = request.getRequestHeaders();
                            if (requestHeaders != null) {
                                for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                                    String key = entry.getKey();
                                    String value = entry.getValue();
                                    if (key == null || value == null || value.isEmpty()) continue;
                                    String lower = key.toLowerCase(Locale.ROOT);
                                    if (("user-agent".equals(lower) || "referer".equals(lower)
                                            || "origin".equals(lower) || "accept".equals(lower)
                                            || "accept-language".equals(lower) || "sec-fetch-site".equals(lower)
                                            || "sec-fetch-mode".equals(lower) || "sec-fetch-dest".equals(lower)
                                            || "sec-ch-ua".equals(lower) || "sec-ch-ua-mobile".equals(lower)
                                            || "sec-ch-ua-platform".equals(lower))
                                            && value.length() <= 2048 && !value.contains("\r") && !value.contains("\n")) {
                                        tikTokLiveRequestHeaders.put(lower, value);
                                    }
                                }
                            }
                            String cookie = CookieManager.getInstance().getCookie(reqUrl);
                            if (cookie != null && !cookie.isEmpty() && cookie.length() <= 8192) {
                                tikTokLiveRequestHeaders.put("cookie", cookie);
                            }
                        }
                    }
                }
                checkAndSendMedia(reqUrl, platformType);
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                super.onPageFinished(view, loadedUrl);
                if ("tiktok_live".equals(platformType)) {
                    updateTikTokDiagnosticStatus("الصفحة اتحملت. جاري فحص مشغل اللايف والتقاط رابط البث...");
                }
                if (loadedUrl != null && loadedUrl.toLowerCase(Locale.ROOT).contains("tiktok")) {
                    CookieManager.getInstance().flush();
                }
                String js = "(function() { " +
                        "   try { " +
                        "       if (window.__aboLiveSnifferStarted) return; " +
                        "       window.__aboLiveSnifferStarted = true; " +
                        "       var seen = {}; " +
                        "       var report = function(url) { " +
                        "           if (!url || typeof url !== 'string') return; " +
                        "           if (!/^https?:\\/\\//i.test(url)) return; " +
                        "           var low = url.toLowerCase(); " +
                        "           if (low.indexOf('pull-') < 0 && low.indexOf('.m3u8') < 0 && low.indexOf('.flv') < 0) return; " +
                        "           if (seen[url]) return; " +
                        "           seen[url] = true; " +
                        "           try { window.MediaSniffer.onMediaFound(url); } catch(e){} " +
                        "       }; " +
                        "       var scan = function() { " +
                        "           try { " +
                        "               var vids = document.getElementsByTagName('video'); " +
                        "               for (var i = 0; i < vids.length; i++) { " +
                        "                   var v = vids[i]; " +
                        "                   v.muted = true; " +
                        "                   v.defaultMuted = true; " +
                        "                   v.playsInline = true; " +
                        "                   v.setAttribute('muted', ''); " +
                        "                   v.setAttribute('playsinline', ''); " +
                        "                   report(v.currentSrc || v.src); " +
                        "                   if (!v.paused && v.readyState >= 2 && v.currentTime > 0) { try { window.MediaSniffer.onPlayerStarted(); } catch(e){} } " +
                        "                   var sources = v.getElementsByTagName('source'); " +
                        "                   for (var j = 0; j < sources.length; j++) report(sources[j].src); " +
                        "                   if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function(){}); } " +
                        "               } " +
                        "               var entries = performance.getEntriesByType ? performance.getEntriesByType('resource') : []; " +
                        "               for (var k = 0; k < entries.length; k++) report(entries[k].name); " +
                        "           } catch(e){} " +
                        "       }; " +
                        "       scan(); " +
                        "       var observer = new MutationObserver(function() { scan(); }); " +
                        "       observer.observe(document.documentElement || document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] }); " +
                        "       document.addEventListener('loadedmetadata', scan, true); " +
                        "       document.addEventListener('canplay', scan, true); " +
                        "       document.addEventListener('playing', function(e) { try { if (e.target && e.target.tagName === 'VIDEO') window.MediaSniffer.onPlayerStarted(); } catch(err){} scan(); }, true); " +
                        "       var attempts = 0; " +
                        "       var timer = setInterval(function() { scan(); attempts++; if (attempts >= 38) clearInterval(timer); }, 1100); " +
                        "   } catch(e){} " +
                        "})();";
                view.evaluateJavascript(js, null);
            }
        });

        webView.loadUrl(url);

        mainHandler.postDelayed(() -> {
            if (!isCaptured) {
                // Never open a visible web page for TikTok LIVE. If background
                // capture fails, stop and leave all other platform routes intact.
                isCaptured = true;
                destroyWebView();
                if ("tiktok_live".equals(platformType)) {
                    tikTokLiveCaptureActive = false;
                    Toast.makeText(ShareActivity.this, "تعذر التقاط رابط TikTok LIVE تلقائيًا", Toast.LENGTH_SHORT).show();
                    finish();
                } else if (openBrowserOnTimeout) {
                    openTikTokLiveInBrowser(url);
                } else if ("threads".equals(platformType)) {
                    openThreadsInBrowser(url);
                } else {
                    Toast.makeText(ShareActivity.this, "تعذر القنص المباشر، جرب فتحه بالمتصفح", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        }, timeoutMs);
    }

    private void showTikTokDiagnosticWindow(WebView liveWebView) {
        isTikTokDiagnosticVisible = true;
        rootLayout.removeAllViews();
        rootLayout.setBackgroundColor(Color.rgb(13, 18, 30));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.rgb(13, 18, 30));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(16, 12, 12, 12);
        header.setBackgroundColor(Color.rgb(24, 34, 52));

        TextView title = new TextView(this);
        title.setText("تشخيص TikTok LIVE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tikTokSendButton = new Button(this);
        tikTokSendButton.setText("إرسال للريسيفر للتجربة");
        tikTokSendButton.setEnabled(false);
        tikTokSendButton.setOnClickListener(v -> {
            String capturedUrl = pendingTikTokLiveUrl;
            if (capturedUrl == null || capturedUrl.trim().isEmpty()) {
                updateTikTokDiagnosticStatus("لسه مفيش رابط اتلقط لإرساله.");
                return;
            }
            tikTokSendButton.setEnabled(false);
            updateTikTokDiagnosticStatus("جاري إرسال الرابط الذي اخترته للريسيفر...");
            String sourceUrl = tikTokLiveSourceUrl;
            if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
                sourceUrl = "https://www.tiktok.com/";
            }
            sendToTv(targetTvIp, capturedUrl, false, sourceUrl);
        });
        header.addView(tikTokSendButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button closeButton = new Button(this);
        closeButton.setText("إغلاق بدون إرسال");
        closeButton.setOnClickListener(v -> {
            tikTokLiveCaptureActive = false;
            pendingTikTokLiveUrl = "";
            isCaptured = true;
            destroyWebView();
            finish();
        });
        header.addView(closeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tikTokDiagnosticStatus = new TextView(this);
        tikTokDiagnosticStatus.setText("جاري فتح صفحة TikTok LIVE...");
        tikTokDiagnosticStatus.setTextColor(Color.WHITE);
        tikTokDiagnosticStatus.setTextSize(14);
        tikTokDiagnosticStatus.setPadding(16, 12, 16, 12);
        tikTokDiagnosticStatus.setBackgroundColor(Color.rgb(35, 48, 70));

        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(tikTokDiagnosticStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tikTokDiagnosticUrl = new TextView(this);
        tikTokDiagnosticUrl.setText("الرابط الملتقط: لم يتم الالتقاط بعد");
        tikTokDiagnosticUrl.setTextColor(Color.rgb(180, 225, 255));
        tikTokDiagnosticUrl.setTextSize(12);
        tikTokDiagnosticUrl.setTextIsSelectable(true);
        tikTokDiagnosticUrl.setPadding(16, 8, 16, 8);
        panel.addView(tikTokDiagnosticUrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tikTokDiagnosticAgent = new TextView(this);
        String liveUa = getTikTokLiveUserAgent();
        boolean desktopUa = liveUa.toLowerCase(Locale.ROOT).contains("windows nt");
        tikTokDiagnosticAgent.setText((desktopUa ? "نوع المتصفح: Desktop / Windows" :
                "نوع المتصفح: Mobile / Android") + "\nUser-Agent: " + liveUa);
        tikTokDiagnosticAgent.setTextColor(Color.LTGRAY);
        tikTokDiagnosticAgent.setTextSize(11);
        tikTokDiagnosticAgent.setTextIsSelectable(true);
        tikTokDiagnosticAgent.setPadding(16, 4, 16, 8);
        panel.addView(tikTokDiagnosticAgent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button copyUrlButton = new Button(this);
        copyUrlButton.setText("نسخ الرابط الملتقط");
        copyUrlButton.setOnClickListener(v -> {
            if (pendingTikTokLiveUrl == null || pendingTikTokLiveUrl.isEmpty()) {
                Toast.makeText(this, "لم يتم التقاط رابط بعد", Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "TikTok LIVE URL", pendingTikTokLiveUrl));
                Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show();
            }
        });
        panel.addView(copyUrlButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(liveWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rootLayout.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void updateTikTokDiagnosticStatus(String message) {
        mainHandler.post(() -> {
            if (tikTokDiagnosticStatus != null && !isFinishing()) {
                tikTokDiagnosticStatus.setText(message);
            }
        });
    }

    private void openTikTokLiveInBrowser(String url) {
        try {
            Intent browserIntent = new Intent(this, BrowserActivity.class);
            browserIntent.setAction(Intent.ACTION_VIEW);
            browserIntent.setData(Uri.parse(url));
            browserIntent.putExtra("auto_cast_tiktok_live", true);
            startActivity(browserIntent);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح TikTok LIVE في المتصفح", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private synchronized void checkAndSendMedia(String reqUrl, String platformType) {
        if (isCaptured || reqUrl == null || reqUrl.isEmpty()) return;

        String lower = reqUrl.toLowerCase();

        if (lower.contains(".jpg") || lower.contains(".png") || lower.contains(".webp") ||
                lower.contains(".js") || lower.contains(".css") || lower.contains("rsrc.php") ||
                lower.contains("preview") || lower.contains(".ico")) {
            return;
        }

        if ("tiktok_vod".equals(platformType)) {
            boolean oldTikTokMatch =
                    (lower.contains("pull-") && lower.contains("tiktokcdn.com")) ||
                    (lower.contains("tiktokcdn.com") && (lower.contains(".flv") || lower.contains(".m3u8"))) ||
                    lower.contains(".mp4") || lower.contains(".m3u8") ||
                    lower.contains("video_playback") || lower.contains("&mime=video");
            if (oldTikTokMatch) {
                isCaptured = true;
                mainHandler.post(() -> {
                    sendTikTokLegacyToTv(targetTvIp, reqUrl);
                    destroyWebView();
                });
            }
            return;
        }

        if ("tiktok_live".equals(platformType)) {
            // Keep the original LIVE matcher: FLV/HLS or pull-* CDN streams only.
            boolean liveMatch = lower.contains("pull-")
                    || (lower.contains("tiktok") && (lower.contains(".m3u8") || lower.contains(".flv")));
            if (liveMatch) {
                isCaptured = true;
                mainHandler.post(() -> {
                    sendToTv(targetTvIp, reqUrl, false);
                    destroyWebView();
                });
            }
            return;
        }

        if ("threads".equals(platformType)) {
            // Audio must be classified before video because some CDN URLs carry
            // overlapping path hints. Never send an audio-only rendition as video.
            if (isThreadsAudioResource(lower)) {
                acceptThreadsTrack(reqUrl, 2);
            } else if (isThreadsVideoResource(lower)) {
                acceptThreadsTrack(reqUrl, 1);
            } else if (lower.matches(".*\\.mp4(?:[?#].*)?$")) {
                probeThreadsMp4(reqUrl);
            }
            return;
        }

        boolean matched = false;
        if ("tiktok_live".equals(platformType)) {
            // LIVE capture is intentionally limited to stream-like TikTok resources.
            // Do not accept ordinary .mp4 video links in this branch.
            matched = isTikTokLiveStreamUrl(reqUrl);
        } else {
            // Keep existing generic media signatures, and explicitly recognize
            // TikTok's signed /video/tos/...?...mime_type=video_mp4 URLs.
            matched = isTikTokVodVideoUrl(reqUrl)
                    || (lower.contains("pull-") && lower.contains("tiktokcdn.com"))
                    || (lower.contains("tiktokcdn.com") && (lower.contains(".flv") || lower.contains(".m3u8")))
                    || lower.contains(".mp4") || lower.contains(".m3u8")
                    || lower.contains("video_playback") || lower.contains("&mime=video");
        }

        if (matched) {
            isCaptured = true;
            mainHandler.post(() -> {
                if ("tiktok_live".equals(platformType)) {
                    // Restore the proven automatic-send behavior: once the hidden
                    // WebView sees the real TikTok LIVE FLV/HLS request, forward it
                    // immediately with the headers captured from that same request.
                    String sourceUrl = tikTokLiveSourceUrl;
                    if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
                        sourceUrl = "https://www.tiktok.com/";
                    }
                    sendToTv(targetTvIp, reqUrl, false, sourceUrl);
                } else if (isTikTokVodVideoUrl(reqUrl) || isTikTokLiveStreamUrl(reqUrl)) {
                    Map<String, String> capturedHeaders = new HashMap<>();
                    synchronized (tikTokRequestHeaders) {
                        capturedHeaders.putAll(tikTokRequestHeaders);
                    }
                    if (!capturedHeaders.containsKey("user-agent")) {
                        String ua = getConfiguredBrowserUserAgent();
                        if (ua != null && !ua.trim().isEmpty()) capturedHeaders.put("user-agent", ua);
                    }
                    String sourceUrl = tikTokRequestSourceUrl;
                    if (sourceUrl == null || sourceUrl.trim().isEmpty()) sourceUrl = "https://www.tiktok.com/";
                    if (!capturedHeaders.containsKey("referer")) capturedHeaders.put("referer", sourceUrl);
                    if (!capturedHeaders.containsKey("origin")) capturedHeaders.put("origin", "https://www.tiktok.com");
                    sendToTv(targetTvIp, reqUrl, null, false, sourceUrl, capturedHeaders);
                } else {
                    sendToTv(targetTvIp, reqUrl, false);
                }
                destroyWebView();
            });
        }
    }

    private synchronized void scheduleTikTokLiveCandidateCapture() {
        if (isCaptured || tikTokLiveCandidateUrl == null || tikTokLiveCandidateUrl.isEmpty()
                || tikTokLiveCandidateFinalize != null) return;

        // Let rotating signed CDN URLs settle, then automatically send the newest
        // FLV/HLS URL and the exact captured request headers to the Receiver.
        tikTokLiveCandidateFinalize = () -> {
            synchronized (ShareActivity.this) {
                tikTokLiveCandidateFinalize = null;
                if (isCaptured || tikTokLiveCandidateUrl == null || tikTokLiveCandidateUrl.isEmpty()) return;
                isCaptured = true;
                final String candidate = tikTokLiveCandidateUrl;
                String sourceUrl = tikTokLiveSourceUrl;
                if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
                    sourceUrl = "https://www.tiktok.com/";
                }
                tikTokLiveCaptureActive = true;
                destroyWebView();
                sendToTv(targetTvIp, candidate, false, sourceUrl);
            }
        };
        mainHandler.postDelayed(tikTokLiveCandidateFinalize, 3000L);
    }

    private void probeThreadsMp4(final String mediaUrl) {
        synchronized (this) {
            if (isCaptured || !threadsMp4ProbeRequests.add(mediaUrl)) return;
        }
        android.os.AsyncTask.execute(() -> {
            int type = Mp4TrackInspector.inspect(mediaUrl);
            if (type != 0) {
                synchronized (ShareActivity.this) {
                    if (isCaptured) return;
                    acceptThreadsTrack(mediaUrl, type);
                }
            }
        });
    }

    private synchronized void acceptThreadsTrack(String mediaUrl, int type) {
        if (isCaptured || mediaUrl == null || mediaUrl.isEmpty()) return;
        if (type == 2) {
            threadsAudioUrl = mediaUrl;
        } else if (type == 1) {
            threadsVideoUrl = mediaUrl;
        }
        if (!threadsVideoUrl.isEmpty() && !threadsAudioUrl.isEmpty()) {
            finishThreadsCapture();
        } else if ((!threadsVideoUrl.isEmpty() || !threadsAudioUrl.isEmpty())
                && !threadsCaptureTimerScheduled) {
            threadsCaptureTimerScheduled = true;
            // Allow remote MP4 track probing to finish before falling back.
            mainHandler.postDelayed(threadsCaptureTimeout, 7000L);
        }
    }

    private boolean isThreadsAudioResource(String lower) {
        return lower.contains("mime_type=audio") || lower.contains("mime-type=audio")
                || lower.contains("mime=audio") || lower.contains("type=audio")
                || lower.contains("content_type=audio") || lower.contains("content-type=audio")
                || lower.contains("audio_dash") || lower.contains("dash_ln_heaac")
                || lower.matches(".*\\.(aac|m4a|mp3|opus|ogg)(?:[?#].*)?$");
    }

    private boolean isThreadsVideoResource(String lower) {
        boolean explicitVideo = lower.contains("video_playback")
                || lower.contains("mime_type=video") || lower.contains("mime-type=video")
                || lower.contains("mime=video") || lower.contains("type=video")
                || lower.contains("content_type=video") || lower.contains("content-type=video");
        // An .mp4 suffix alone (or a generic CDN path) does not prove this is a
        // video track; audio-only DASH renditions can also be packaged as MP4.
        if (lower.matches(".*\\.mp4(?:[?#].*)?$")) return explicitVideo;
        return explicitVideo || lower.contains("/video/")
                || (lower.contains("cdninstagram.com") && lower.contains("/v/"));
    }

    private synchronized void finishThreadsCapture() {
        if (isCaptured) return;
        mainHandler.removeCallbacks(threadsCaptureTimeout);
        threadsCaptureTimerScheduled = false;

        if (!threadsVideoUrl.isEmpty()) {
            isCaptured = true;
            final String video = threadsVideoUrl;
            final String audio = threadsAudioUrl;
            mainHandler.post(() -> {
                if (!audio.isEmpty()) {
                    sendToTv(targetTvIp, video, audio, false);
                } else {
                    // A progressive MP4 already contains both audio and video.
                    sendToTv(targetTvIp, video, false);
                }
                destroyWebView();
            });
        } else {
            // Audio alone is not a playable video. Fall back to the in-app browser
            // rather than sending a black/audio-only playback request to Receiver.
            isCaptured = true;
            final String fallback = threadsSharedUrl;
            mainHandler.post(() -> {
                destroyWebView();
                openThreadsInBrowser(fallback);
            });
        }
    }

    private void openThreadsInBrowser(String url) {
        try {
            Intent browserIntent = new Intent(this, BrowserActivity.class);
            browserIntent.setAction(Intent.ACTION_VIEW);
            browserIntent.setData(Uri.parse(url));
            startActivity(browserIntent);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح Threads في المتصفح", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void destroyWebView() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }

    private void sendToTv(String tvIp, String mediaUrl, boolean isQueue) {
        sendToTv(tvIp, mediaUrl, null, isQueue, null);
    }

    private void sendToTv(String tvIp, String mediaUrl, boolean isQueue, String sourceUrl) {
        sendToTv(tvIp, mediaUrl, null, isQueue, sourceUrl);
    }

    private void sendToTv(String tvIp, String mediaUrl, String audioUrl, boolean isQueue) {
        sendToTv(tvIp, mediaUrl, audioUrl, isQueue, null);
    }

    private void sendToTv(String tvIp, String mediaUrl, String audioUrl,
                          boolean isQueue, String sourceUrl) {
        sendToTv(tvIp, mediaUrl, audioUrl, isQueue, sourceUrl, null);
    }

    private void sendToTv(String tvIp, String mediaUrl, String audioUrl,
                          boolean isQueue, String sourceUrl, Map<String, String> mediaHeaders) {
        // Resolve the selected receiver at the moment the network request is created.
        // Do not trust an IP snapshot captured earlier while TikTok/YouTube extraction
        // was still running in the background.
        SharedPreferences currentPrefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        String selectedReceiverIp = currentPrefs.getString("tv_ip", "").trim();
        if (selectedReceiverIp.isEmpty()) {
            
            finish();
            return;
        }
        targetTvIp = selectedReceiverIp;
        tvIp = selectedReceiverIp;
        try {
            final String receiverEndpoint = ReceiverApiClient.buildEndpoint(tvIp);
            String receiverHost = Uri.parse(receiverEndpoint).getHost();
            OkHttpClient receiverClient = ReceiverApiClient.create(this, receiverHost);
            Request request;
            if (!isQueue) {
                JSONObject payload = new JSONObject();
                if (mediaUrl != null && !mediaUrl.trim().isEmpty()) payload.put("url", mediaUrl);
                if (audioUrl != null && !audioUrl.trim().isEmpty()) payload.put("audio", audioUrl);
                if (sourceUrl != null && !sourceUrl.trim().isEmpty()) payload.put("sourceUrl", sourceUrl);
                if (tikTokLiveCaptureActive && isTikTokLiveStreamUrl(mediaUrl)) {
                    JSONObject liveHeaders = new JSONObject();
                    synchronized (tikTokLiveRequestHeaders) {
                        for (Map.Entry<String, String> entry : tikTokLiveRequestHeaders.entrySet()) {
                            liveHeaders.put(entry.getKey(), entry.getValue());
                        }
                    }
                    if (!liveHeaders.has("user-agent")) {
                        // Match the hidden TikTok LIVE WebView UA when the request
                        // interceptor did not provide one.
                        liveHeaders.put("user-agent", getTikTokLiveUserAgent());
                    }
                    if (!liveHeaders.has("referer") && sourceUrl != null && !sourceUrl.trim().isEmpty()) {
                        liveHeaders.put("referer", sourceUrl);
                    }
                    if (!liveHeaders.has("origin")) {
                        liveHeaders.put("origin", "https://www.tiktok.com");
                    }
                    if (liveHeaders.length() > 0) payload.put("headers", liveHeaders);
                } else if (mediaHeaders != null && !mediaHeaders.isEmpty()) {
                    JSONObject supplied = new JSONObject();
                    String[] allowed = {"user-agent", "referer", "origin", "accept", "accept-language", "cookie",
                            "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "sec-ch-ua",
                            "sec-ch-ua-mobile", "sec-ch-ua-platform"};
                    for (String key : allowed) {
                        String value = mediaHeaders.get(key);
                        int max = "cookie".equals(key) ? 8192 : 2048;
                        if (value != null && !value.isEmpty() && value.length() <= max
                                && !value.contains("\r") && !value.contains("\n")) {
                            supplied.put(key, value);
                        }
                    }
                    if (supplied.length() > 0) payload.put("headers", supplied);
                }
                if ((mediaUrl != null && mediaUrl.contains("googlevideo.com"))
                        || (audioUrl != null && audioUrl.contains("googlevideo.com"))) {
                    JSONObject headers = new JSONObject();
                    // Match the User-Agent actually used by NewPipe when resolving
                    // googlevideo URLs; a WebView UA can make Receiver requests return 403.
                    headers.put("user-agent", YoutubeDirectExtractor.getStreamUserAgent());
                    headers.put("referer", "https://www.youtube.com/");
                    headers.put("origin", "https://www.youtube.com");
                    headers.put("accept-language", "ar-EG,ar;q=0.9,en-US;q=0.8");
                    payload.put("headers", headers);
                }
                RequestBody body = RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"), payload.toString());
                request = new Request.Builder().url(receiverEndpoint + "/play").post(body).build();
            } else {
                String fullUrl = receiverEndpoint + "/?queue=" + URLEncoder.encode(mediaUrl, "UTF-8");
                request = new Request.Builder().url(fullUrl).get().build();
            }
            AtomicBoolean requestHeadersSent = new AtomicBoolean(false);
            OkHttpClient trackedClient = receiverClient.newBuilder()
                    .eventListener(new EventListener() {
                        @Override
                        public void requestHeadersEnd(Call call, Request request) {
                            requestHeadersSent.set(true);
                        }
                    }).build();

            trackedClient.newCall(request).enqueue(new Callback() {
                private void finishSend() {
                    mainHandler.post(() -> {
                        if (requestHeadersSent.get()) {
                            Toast.makeText(ShareActivity.this, "✅ تم الإرسال", Toast.LENGTH_SHORT).show();
                        }
                        finish();
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
            finish();
        }
    }

    private void sendMultipleMedia(ArrayList<Uri> uris) {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        targetTvIp = prefs.getString("tv_ip", "").trim();

        if (targetTvIp.isEmpty()) {
            Toast.makeText(this, "يرجى تعيين عنوان IP الشاشة أولاً", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Toast.makeText(this, "جاري إرسال قائمة الفيديوهات للشاشة...", Toast.LENGTH_SHORT).show();

        for (int i = 0; i < uris.size(); i++) {
            final boolean isQueue = (i > 0);
            final String fileUrl = uris.get(i).toString();
            mainHandler.postDelayed(() -> sendToTv(targetTvIp, fileUrl, isQueue), i * 350L);
        }
    }

    @Override
    protected void onDestroy() {
        destroyWebView();
        super.onDestroy();
    }
}