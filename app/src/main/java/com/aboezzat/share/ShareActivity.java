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

        // Drama Live's SwiftData URL is itself a live playlist endpoint.
        // Do not wait for WebView media sniffing: forward this known URL to Receiver,
        // which will hand it to VLC (and use ExoPlayer if VLC is unavailable).
        if (isSwiftDataDramaLiveUrl(url)) {
            Toast.makeText(this, "تم التعرف على رابط Drama Live، جاري إرساله للشاشة…",
                    Toast.LENGTH_LONG).show();
            sendToTv(targetTvIp, url, false, url);
            return;
        }

        if (lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be")
                || lowerUrl.contains("youtube-nocookie.com")) {
            Toast.makeText(this, "جاري استخراج رابط YouTube المباشر على الهاتف...", Toast.LENGTH_SHORT).show();
            YoutubeDirectExtractor.extract(url, new YoutubeDirectExtractor.Callback() {
                @Override public void onSuccess(String videoUrl, String audioUrl, String title, String quality) {
                    if (isFinishing() || videoUrl == null || videoUrl.trim().isEmpty()) return;
                    // Send extracted media renditions only. A separate audio URL is
                    // included when YouTube's highest-quality video is video-only.
                    sendToTv(targetTvIp, videoUrl, audioUrl, false);
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
        if (lowerUrl.contains("vt.tiktok.com") || lowerUrl.contains("vm.tiktok.com")) {
            resolveTikTokShortLink(url);
            return;
        }

        if (isTikTokLiveUrl(lowerUrl)) {
            startPlatformSniffer(url, "tiktok_live", 5000L, true);
            return;
        }

        if (lowerUrl.contains("tiktok.com")) {
            Toast.makeText(this, "جاري استخراج فيديو تيك توك...", Toast.LENGTH_SHORT).show();
            handleTikTok(url);
            return;
        }

        Toast.makeText(this, "جاري قنص الفيديو للشاشة...", Toast.LENGTH_SHORT).show();
        startPlatformSniffer(url, "generic");
    }

    private boolean isSwiftDataDramaLiveUrl(String value) {
        if (value == null) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && path != null
                    && path.toLowerCase(Locale.ROOT).matches("^/fk/[^/]+/index\\.html$");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTikTokLiveUrl(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase();
        return lower.contains("live.tiktok.com")
                || lower.matches(".*tiktok\\.com/@[^/]+/live(?:[/?#].*)?");
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
                        startPlatformSniffer(resolvedUrl, "tiktok_live", 5000L, true);
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

        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");

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
                checkAndSendMedia(reqUrl, platformType);
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
            matched = lower.contains("pull-")
                    || (lower.contains("tiktok") && (lower.contains(".m3u8") || lower.contains(".flv")));
        } else {
            matched = (lower.contains("pull-") && lower.contains("tiktokcdn.com"))
                    || (lower.contains("tiktokcdn.com") && (lower.contains(".flv") || lower.contains(".m3u8")))
                    || lower.contains(".mp4") || lower.contains(".m3u8")
                    || lower.contains("video_playback") || lower.contains("&mime=video");
        }

        if (matched) {
            isCaptured = true;
            mainHandler.post(() -> {
                sendToTv(targetTvIp, reqUrl, false);
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
                if ((mediaUrl != null && mediaUrl.contains("googlevideo.com"))
                        || (audioUrl != null && audioUrl.contains("googlevideo.com"))) {
                    JSONObject headers = new JSONObject();
                    headers.put("user-agent", WebSettings.getDefaultUserAgent(this));
                    headers.put("referer", "https://www.youtube.com/");
                    headers.put("origin", "https://www.youtube.com");
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
                private void showResult(String message) {
                    mainHandler.post(() -> {
                        Toast.makeText(ShareActivity.this, message, Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

                @Override
                public void onFailure(Call call, IOException error) {
                    // The Receiver may start playback and close the original socket before
                    // the sender receives the HTTP response. Its ping endpoint tells us
                    // whether the Receiver is still reachable after that ambiguous failure.
                    Request ping = new Request.Builder()
                            .url(receiverEndpoint + "/?command=ping")
                            .get()
                            .build();
                    client.newCall(ping).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call pingCall, IOException pingError) {
                            showResult(requestHeadersSent.get()
                                    ? "تم إرسال الطلب، لكن تعذر تأكيد رد الشاشة"
                                    : "تعذر الاتصال بالشاشة");
                        }

                        @Override
                        public void onResponse(Call pingCall, Response pingResponse) {
                            boolean receiverAlive = pingResponse.isSuccessful();
                            pingResponse.close();
                            if (receiverAlive && requestHeadersSent.get()) {
                                showResult(isQueue
                                        ? "تم إرسال الطلب للشاشة ✓"
                                        : "تم الإرسال والتشغيل بنجاح ✓");
                            } else if (receiverAlive) {
                                showResult("الشاشة متصلة، لكن لم يتأكد إرسال الوسائط");
                            } else {
                                showResult("تعذر تأكيد استقبال الطلب");
                            }
                        }
                    });
                }

                @Override
                public void onResponse(Call call, Response response) {
                    boolean accepted = response.isSuccessful();
                    response.close();
                    showResult(accepted
                            ? (isQueue ? "تمت الإضافة لقائمة التشغيل ✓" : "تم الإرسال والتشغيل بنجاح ✓")
                            : "الشاشة رفضت الطلب (HTTP)");
                }
            });
        } catch (Exception e) {
            mainHandler.post(() -> {
                Toast.makeText(this, "تعذر تجهيز الإرسال للشاشة", Toast.LENGTH_SHORT).show();
                finish();
            });
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
