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
import android.view.ViewGroup;
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
    private boolean isCaptured = false;
    private String targetTvIp = "";
    // Threads can expose video and audio as separate DASH resources. Collect both
    // before sending so the Receiver receives a synchronized A/V pair.
    private String threadsSharedUrl = "";
    private String threadsVideoUrl = "";
    private String threadsAudioUrl = "";
    private boolean threadsCaptureTimerScheduled = false;
    private final Set<String> threadsMp4ProbeRequests = new HashSet<>();
    private final Map<String, String> tikTokLiveRequestHeaders = new HashMap<>();
    private volatile boolean tikTokLiveCaptureActive = false;
    private final Runnable threadsCaptureTimeout = () -> finishThreadsCapture();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout rootLayout = new FrameLayout(this);
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
        Pattern pattern = Pattern.compile("(?i)((?:https?://|www\\.)[\\w\\-\\.\\?\\=\\&\\%\\/\\#\\+]+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String url = matcher.group(1);
            return url.regionMatches(true, 0, "www.", 0, 4) ? "https://" + url : url;
        }
        return null;
    }

    private void dispatchPlatformRoute(String url) {
        SharedPreferences prefs = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE);
        targetTvIp = prefs.getString("tv_ip", "").trim();

        String lowerUrl = url.toLowerCase();

        if (lowerUrl.contains("instagram.com")) {
            Intent browserIntent = new Intent(this, BrowserActivity.class);
            browserIntent.setAction(Intent.ACTION_VIEW);
            browserIntent.setData(Uri.parse(url));
            startActivity(browserIntent);
            finish();
            return;
        }

        // Threads shared links belong to the full in-app browser capture flow.
        // Keep this separate from ShareActivity's background/direct media handlers.
        if (lowerUrl.contains("threads.net") || lowerUrl.contains("threads.com")) {
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

        if (lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be")
                || lowerUrl.contains("youtube-nocookie.com")) {

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

        if (isTikTokLiveUrl(lowerUrl)) {
            // First attempt a fully hidden WebView capture: autoplay muted, detect the
            // LIVE HLS/FLV resource, and cast it without opening the visible browser.
            // Preserve the proven browser route as a fallback if background capture fails.
            tikTokLiveCaptureActive = true;
            startPlatformSniffer(url, "tiktok_live", 45000L, true);
            return;
        }

        if (lowerUrl.contains("tiktok.com")) {

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
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("live.tiktok.com")
                || lower.matches(".*tiktok\\.com/@[^/]+/live(?:[/?#].*)?");
    }

    // A LIVE page may deliver its stream from a TikTok CDN host that does not
    // contain the word "tiktok". Match the stream signatures consistently in
    // both WebView interception and the JavaScript resource scanner.
    private boolean isTikTokLiveStreamUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("pull-") || lower.contains(".m3u8") || lower.contains(".flv");
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
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/122.0.0.0 Mobile Safari/537.36")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // If TikTok cannot resolve the shared short link, open it in the
                // app browser instead of leaving the user at a failed-capture toast.
                mainHandler.post(() -> openTikTokLiveInBrowser(shortUrl));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String resolvedUrl = response.request().url().toString();
                response.close();

                mainHandler.post(() -> {
                    if (isTikTokLiveUrl(resolvedUrl)) {
                        tikTokLiveCaptureActive = true;
                        startPlatformSniffer(resolvedUrl, "tiktok_live", 45000L, true);
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
                    mainHandler.post(() -> startPlatformSniffer(url, "generic"));
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
                                sendToTv(targetTvIp, play, false);
                                return;
                            }
                        }
                    } catch (Exception ignored) {}
                    mainHandler.post(() -> startPlatformSniffer(url, "generic"));
                }
            });
        } catch (Exception e) {
            startPlatformSniffer(url, "generic");
        }
    }

    // TikTok LIVE may expose a desktop autoplay/player flow that differs from
    // its mobile WebView flow. Keep this override isolated to the hidden LIVE
    // capture WebView; the user's browser UA preference remains unchanged elsewhere.
    // Match TikTok LIVE capture to the actual Android WebView used by the browser.
    private String getTikTokLiveUserAgent() {
        String ua = WebSettings.getDefaultUserAgent(this);
        if (ua == null) return "";
        return ua.replace("; wv", "").replace("Version/4.0 ", "").trim();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startPlatformSniffer(String url, final String platformType) {
        startPlatformSniffer(url, platformType, 16000L, false);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startPlatformSniffer(String url, final String platformType,
                                      long timeoutMs, boolean openBrowserOnTimeout) {
        isCaptured = false;
        if ("threads".equals(platformType)) {
            threadsSharedUrl = url;
            threadsVideoUrl = "";
            threadsAudioUrl = "";
            threadsMp4ProbeRequests.clear();
            threadsCaptureTimerScheduled = false;
            mainHandler.removeCallbacks(threadsCaptureTimeout);
        }
        webView = new WebView(this);
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

        String preferredUserAgent = getSharedPreferences("AboEzzatSharePrefs", MODE_PRIVATE)
                .getString("browser_user_agent", "").trim();
        String captureUserAgent = "tiktok_live".equals(platformType)
                ? getTikTokLiveUserAgent()
                : (preferredUserAgent.isEmpty()
                    ? WebSettings.getDefaultUserAgent(this) : preferredUserAgent);
        settings.setUserAgentString(captureUserAgent);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onMediaFound(String mediaUrl) {
                checkAndSendMedia(mediaUrl, platformType);
            }
        }, "MediaSniffer");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();
                if ("tiktok_live".equals(platformType)) {
                    if (isTikTokLiveStreamUrl(reqUrl)) {
                        synchronized (tikTokLiveRequestHeaders) {
                            tikTokLiveRequestHeaders.clear();
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
                        "       document.addEventListener('playing', scan, true); " +
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
                // Ignore any late WebView resource callbacks after falling back.
                isCaptured = true;
                destroyWebView();
                if (openBrowserOnTimeout) {
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
            matched = (lower.contains("pull-") && lower.contains("tiktokcdn.com"))
                    || (lower.contains("tiktokcdn.com") && (lower.contains(".flv") || lower.contains(".m3u8")))
                    || lower.contains(".mp4") || lower.contains(".m3u8")
                    || lower.contains("video_playback") || lower.contains("&mime=video");
        }

        if (matched) {
            isCaptured = true;
            mainHandler.post(() -> {
                if ("tiktok_live".equals(platformType)) {
                    sendToTv(targetTvIp, reqUrl, false, "https://www.tiktok.com/");
                } else {
                    sendToTv(targetTvIp, reqUrl, false);
                }
                destroyWebView();
            });
        }
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
            if (!tvIp.startsWith("http://") && !tvIp.startsWith("https://")) {
                tvIp = "http://" + tvIp;
            }
            if (!tvIp.contains(":" + TARGET_PORT)) {
                tvIp = tvIp + ":" + TARGET_PORT;
            }

            final String receiverEndpoint = tvIp;
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
                    if (liveHeaders.length() > 0) payload.put("headers", liveHeaders);
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
            OkHttpClient trackedClient = client.newBuilder()
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
