package com.aboezzat.share;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

public class ClipboardShortcutAccessibilityService extends AccessibilityService {
    private static final String PREFS = "AboEzzatSharePrefs";
    private static final long VISIBLE_MS = 3000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View bubble;
    private WindowManager.LayoutParams bubbleParams;
    private Runnable hideRunnable;
    private float downRawX, downRawY;
    private int downX, downY;
    private boolean dragged;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !android.provider.Settings.canDrawOverlays(this)) return;
        if (isCopyConfirmation(event)) showBubble();
    }

    private boolean isCopyConfirmation(AccessibilityEvent event) {
        // Inspect only the small accessibility event payload for copy confirmations.
        // No screen hierarchy, browsing content, or clipboard contents are collected.
        StringBuilder payload = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence item : event.getText()) {
                if (item != null) payload.append(item).append(' ');
            }
        }
        CharSequence description = event.getContentDescription();
        if (description != null) payload.append(description);
        int type = event.getEventType();
        // MIUI/HyperOS may announce clipboard confirmation from the app that
        // performed the copy, not from Android SystemUI. Accept these event
        // types from any package, but require an explicit completed-copy phrase.
        if (type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_ANNOUNCEMENT) return false;

        String text = payload.toString().trim().toLowerCase(java.util.Locale.ROOT);
        boolean copyConfirmed = text.contains("copied to clipboard")
                || text.contains("link copied")
                || text.contains("copied")
                || text.contains("تم النسخ")
                || text.contains("تم نسخ")
                || text.contains("نسخ إلى الحافظة")
                || text.contains("نسخ الرابط")
                || text.contains("تم نسخ الرابط")
                || text.contains("تم النسخ إلى الحافظة");
        if (!copyConfirmed) return false;

        // Do not show the bubble for ordinary copied text or ordinary web links.
        // The URL must be present in the accessibility event itself; never read
        // clipboard contents in the background to classify a copy operation.
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("https?://[^\\s]+", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!matcher.find()) return false;
        String url = matcher.group().replaceAll("[),.؛،]+$", "");
        return isLikelyVideoUrl(url);
    }

    private boolean isLikelyVideoUrl(String rawUrl) {
        try {
            android.net.Uri uri = android.net.Uri.parse(rawUrl);
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if (path.matches(".*\\\\.(mp4|m3u8|webm|mov|mkv|avi)$")) return true;

            if (host.equals("youtu.be") || host.endsWith("youtube.com")
                    || host.endsWith("youtube-nocookie.com")) return true;
            if (host.equals("fb.watch")) return true;
            if (host.endsWith("facebook.com") || host.equals("fb.com"))
                return path.contains("/watch") || path.contains("/reel")
                        || path.contains("/videos/") || path.contains("/share/v/");
            if (host.endsWith("tiktok.com"))
                return path.contains("/video/") || path.contains("/live");
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

    private void showBubble() {
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;
        removeBubble();

        int size = dp(48);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        icon.setElevation(dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.rgb(28, 103, 210));
        background.setStroke(dp(2), Color.WHITE);
        icon.setBackground(background);
        icon.setContentDescription("AboEzzat Cast - اضغط لإرسال الرابط");

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        bubbleParams = new WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("clipboard_bubble_x", dp(16));
        bubbleParams.y = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("clipboard_bubble_y", dp(220));

        icon.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = bubbleParams.x;
                    downY = bubbleParams.y;
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downRawX);
                    int dy = (int) (event.getRawY() - downRawY);
                    if (Math.abs(dx) + Math.abs(dy) > dp(5)) dragged = true;
                    if (dragged) {
                        bubbleParams.x = Math.max(0, downX + dx);
                        bubbleParams.y = Math.max(0, downY + dy);
                        try { windowManager.updateViewLayout(bubble, bubbleParams); }
                        catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragged) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putInt("clipboard_bubble_x", bubbleParams.x)
                                .putInt("clipboard_bubble_y", bubbleParams.y).apply();
                    } else {
                        launchCastFromClipboard();
                    }
                    return true;
            }
            return true;
        });

        bubble = icon;
        try {
            windowManager.addView(bubble, bubbleParams);
            hideRunnable = this::removeBubble;
            handler.postDelayed(hideRunnable, VISIBLE_MS);
        } catch (Exception e) {
            bubble = null;
            bubbleParams = null;
        }
    }

    private void launchCastFromClipboard() {
        removeBubble();
        Intent launch = new Intent(this, MainActivity.class);
        // Use a separate translucent task so tapping the bubble does not
        // bring the normal AboEzzat Cast home screen to the foreground.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        launch.putExtra("clipboard_cast_shortcut", true);
        try { startActivity(launch); } catch (Exception ignored) {}
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void removeBubble() {
        if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
        hideRunnable = null;
        if (bubble != null && windowManager != null) {
            try { windowManager.removeView(bubble); } catch (Exception ignored) {}
        }
        bubble = null;
        bubbleParams = null;
    }

    @Override
    public void onInterrupt() { removeBubble(); }

    @Override
    public void onDestroy() {
        removeBubble();
        super.onDestroy();
    }
}
