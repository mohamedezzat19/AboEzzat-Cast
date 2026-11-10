package com.aboezzat.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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
        Pattern pattern = Pattern.compile("(https?://[\\w\\-\\.\\?\\=\\&\\%\\/\\#\\+]+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) return matcher.group(1);
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

        if (targetTvIp.isEmpty()) {
            Toast.makeText(this, "يرجى تعيين عنوان IP الشاشة أولاً", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be")) {
            sendToTv(targetTvIp, url, false);
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

        if (lowerUrl.contains("threads.net") || lowerUrl.contains("threads.com")) {
            Toast.makeText(this, "جاري قنص ثريد للشاشة مباشرة...", Toast.LENGTH_SHORT).show();
            startPlatformSniffer(url, "threads");
            return;
        }

        Toast.makeText(this, "جاري قنص الفيديو للشاشة...", Toast.LENGTH_SHORT).show();
        startPlatformSniffer(url, "generic");
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

        boolean matched = false;

        if ("threads".equals(platformType)) {
            if (lower.contains(".mp4") || lower.contains("video_playback") || (lower.contains("cdninstagram.com") && lower.contains("/v/"))) {
                matched = true;
            }
        } else if ("tiktok_live".equals(platformType)) {
            // LIVE capture is intentionally limited to stream-like TikTok resources.
            // Do not accept ordinary .mp4 video links in this branch.
            if (lower.contains("pull-") ||
                    (lower.contains("tiktok") && (lower.contains(".m3u8") || lower.contains(".flv")))) {
                matched = true;
            }
        } else {
            if ((lower.contains("pull-") && lower.contains("tiktokcdn.com")) ||
                (lower.contains("tiktokcdn.com") && (lower.contains(".flv") || lower.contains(".m3u8"))) ||
                lower.contains(".mp4") || lower.contains(".m3u8") ||
                lower.contains("video_playback") || lower.contains("&mime=video")) {
                matched = true;
            }
        }

        if (matched) {
            isCaptured = true;
            mainHandler.post(() -> {
                sendToTv(targetTvIp, reqUrl, false);
                destroyWebView();
            });
        }
    }

    private void destroyWebView() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }

    private void sendToTv(String tvIp, String mediaUrl, boolean isQueue) {
        try {
            if (!tvIp.startsWith("http://") && !tvIp.startsWith("https://")) {
                tvIp = "http://" + tvIp;
            }
            if (!tvIp.contains(":" + TARGET_PORT)) {
                tvIp = tvIp + ":" + TARGET_PORT;
            }

            final String receiverEndpoint = tvIp;
            String paramName = isQueue ? "queue" : "url";
            String fullUrl = receiverEndpoint + "/?" + paramName + "="
                    + URLEncoder.encode(mediaUrl, "UTF-8");
            Request request = new Request.Builder().url(fullUrl).get().build();
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
