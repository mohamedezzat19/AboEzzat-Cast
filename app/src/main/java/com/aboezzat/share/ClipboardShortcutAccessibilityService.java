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
    private long lastWebVideoCasterScanAt;
    private static final String WEB_VIDEO_CASTER_PACKAGE = "com.instantbits.cast.webvideo";

    private android.content.ClipboardManager clipboardManager;
    private android.content.ClipboardManager.OnPrimaryClipChangedListener clipboardListener;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("diag_service_connected", true)
                .putInt("diag_clipboard_count", 0)
                .putString("diag_last_clipboard_mime", "لم يصل حدث نسخ بعد")
                .putBoolean("diag_clipboard_listener_registered", false)
                .putString("diag_clipboard_listener_status", "لم يتم تسجيل المستمع بعد")
                .putInt("diag_systemui_event_count", 0)
                .putString("diag_last_systemui_event", "لم يصل حدث من SystemUI بعد")
                .putString("diag_last_bubble_result", "لم تتم محاولة عرض الفقاعة بعد")
                .putInt("diag_accessibility_copy_count", 0)
                .putString("diag_last_copy_package", "لم يتم رصد أمر نسخ بعد")
                .putBoolean("diag_last_systemui_url_match", false)
                .putString("diag_last_clipboard_host", "لم يتم فحص نافذة النظام بعد")
                .putInt("diag_source_event_count", 0)
                .putInt("diag_source_click_count", 0)
                .putString("diag_last_source_package", "لم يصل حدث من تطبيق مصدر بعد")
                .putString("diag_last_source_text", "لا توجد بيانات")
                .putString("diag_last_click_package", "لم يصل حدث ضغط بعد")
                .putString("diag_last_click_text", "لا توجد بيانات")
                .apply();
        clipboardManager = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardListener = () -> {
                // Android restricts reading clipboard contents in the background.
                // Detect text clipboard changes here; inspect the URL only after
                // the user taps the bubble and the app is foregrounded.
                try {
                    int count = getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getInt("diag_clipboard_count", 0) + 1;
                    android.content.ClipDescription description =
                            clipboardManager.getPrimaryClipDescription();
                    StringBuilder seenMimeTypes = new StringBuilder();
                    if (description != null) {
                        for (int i = 0; i < description.getMimeTypeCount(); i++) {
                            if (seenMimeTypes.length() > 0) seenMimeTypes.append(", ");
                            seenMimeTypes.append(description.getMimeType(i));
                        }
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("diag_clipboard_count", count)
                            .putString("diag_last_clipboard_mime",
                                    seenMimeTypes.length() == 0 ? "لا يوجد MIME ظاهر" : seenMimeTypes.toString())
                            .apply();
                    if (clipboardManager.hasPrimaryClip()) {
                        if (description != null) {
                            // Apps do not all copy links as text/plain. YouTube and
                            // browser share menus may publish text/html or vendor
                            // text MIME types, so accept every textual/URL clipboard
                            // representation and validate the actual URL only on tap.
                            boolean containsTextOrUrl = false;
                            for (int i = 0; i < description.getMimeTypeCount(); i++) {
                                String mime = description.getMimeType(i);
                                if (mime != null) {
                                    String lowerMime = mime.toLowerCase(java.util.Locale.ROOT);
                                    if (lowerMime.startsWith("text/")
                                            || lowerMime.contains("uri-list")
                                            || lowerMime.contains("url")) {
                                        containsTextOrUrl = true;
                                        break;
                                    }
                                }
                            }
                            // Clipboard access is restricted while the app is in the background.
                            // Show the short-lived shortcut for textual clips; the URL itself is
                            // validated only after the user taps and AboEzzat Cast is foregrounded.
                            CharSequence clipLabel = description.getLabel();
                            boolean isOurDiagnosticCopy = clipLabel != null
                                    && clipLabel.toString().toLowerCase(java.util.Locale.ROOT)
                                    .contains("aboezzat cast diagnostics");
                            if (containsTextOrUrl && !isOurDiagnosticCopy
                                    && android.provider.Settings.canDrawOverlays(this)) {
                                handler.post(this::showBubble);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            };
            try {
                clipboardManager.addPrimaryClipChangedListener(clipboardListener);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("diag_clipboard_listener_registered", true)
                        .putString("diag_clipboard_listener_status", "تم تسجيل مستمع الحافظة بنجاح")
                        .apply();
            } catch (Exception registrationError) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("diag_clipboard_listener_registered", false)
                        .putString("diag_clipboard_listener_status",
                                "فشل التسجيل: " + registrationError.getClass().getSimpleName())
                        .apply();
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence packageName = event.getPackageName();
        String eventPackage = packageName == null ? "" : packageName.toString();

        // Read only Web Video Caster's own exposed accessibility nodes. This does not
        // inspect pixels, capture the screen, click controls, or read its private files.
        if (WEB_VIDEO_CASTER_PACKAGE.equals(eventPackage)) {
            int type = event.getEventType();
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastWebVideoCasterScanAt >= 350L) {
                    lastWebVideoCasterScanAt = now;
                    captureWebVideoCasterUrls();
                }
            }
        }

        // Ignore accessibility events generated by AboEzzat Cast itself.
        // Opening our own screens can expose words such as "copy" and must
        // never be counted as a user's copy action.
        if (getPackageName().equals(eventPackage)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("diag_last_ignored_copy_package", eventPackage)
                    .apply();
            return;
        }

        // Diagnostic-only capture: record source-app events and clicked labels.
        // This does not change bubble detection or read clipboard contents.
        boolean isSystemClipboardHost = "com.android.systemui".equals(eventPackage)
                || "com.mi.android.globallauncher".equals(eventPackage);
        if (isSystemClipboardHost) {
            android.content.SharedPreferences systemPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            systemPrefs.edit()
                    .putInt("diag_systemui_event_count",
                            systemPrefs.getInt("diag_systemui_event_count", 0) + 1)
                    .putString("diag_last_systemui_event",
                            "type=" + event.getEventType() + " | " + describeEvent(event))
                    .apply();
        }
        if (!isSystemClipboardHost) {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            android.content.SharedPreferences.Editor eventEditor = prefs.edit()
                    .putInt("diag_source_event_count",
                            prefs.getInt("diag_source_event_count", 0) + 1)
                    .putString("diag_last_source_package", eventPackage)
                    .putString("diag_last_source_text", describeEvent(event));
            int type = event.getEventType();
            if (type == AccessibilityEvent.TYPE_VIEW_CLICKED
                    || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                    || (Build.VERSION.SDK_INT >= 23
                    && type == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED)) {
                eventEditor.putInt("diag_source_click_count",
                                prefs.getInt("diag_source_click_count", 0) + 1)
                        .putString("diag_last_click_package", eventPackage)
                        .putString("diag_last_click_text", describeEvent(event));
            }
            eventEditor.apply();
        }

        // First detect an explicit "Copy link/URL" action in the source app.
        // If the app does not expose that action, fall back to a URL shown by
        // Android/MIUI's own clipboard preview window.
        boolean copyConfirmed = isCopyLinkAction(event);
        boolean systemClipboardHost = "com.android.systemui".equals(eventPackage)
                || "com.mi.android.globallauncher".equals(eventPackage);
        if (!copyConfirmed && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && systemClipboardHost) {
            copyConfirmed = hasSystemClipboardUrl();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("diag_last_systemui_url_match", copyConfirmed)
                    .putString("diag_last_clipboard_host", eventPackage)
                    .apply();
        }

        android.content.SharedPreferences.Editor diagnosticEditor =
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("diag_last_accessibility_package",
                                packageName == null ? "غير معروف" : packageName.toString())
                        .putInt("diag_last_accessibility_type", event.getEventType());
        if (copyConfirmed) {
            int copyCount = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getInt("diag_accessibility_copy_count", 0) + 1;
            diagnosticEditor.putInt("diag_accessibility_copy_count", copyCount)
                    .putString("diag_last_copy_package",
                            packageName == null ? "غير معروف" : packageName.toString());
        }
        diagnosticEditor.apply();
        if (copyConfirmed && android.provider.Settings.canDrawOverlays(this)) showBubble();
    }

    private boolean hasSystemClipboardUrl() {
        // MIUI may place the clipboard toast in a separate overlay window.
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
                        if (window == null) continue;
                        android.view.accessibility.AccessibilityNodeInfo root = null;
                        try {
                            root = window.getRoot();
                            if (root == null) continue;
                            CharSequence owner = root.getPackageName();
                            String ownerPackage = owner == null ? "" : owner.toString();
                            if (!"com.android.systemui".equals(ownerPackage)
                                    && !"com.mi.android.globallauncher".equals(ownerPackage)) {
                                root.recycle();
                                continue;
                            }
                            if (containsClipboardUrl(root, 120)) return true;
                        } catch (Exception ignored) {
                            if (root != null) try { root.recycle(); } catch (Exception ignoredAgain) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fallback for devices where interactive-window retrieval is unavailable.
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        CharSequence owner = root.getPackageName();
        String ownerPackage = owner == null ? "" : owner.toString();
        if (!"com.android.systemui".equals(ownerPackage)
                && !"com.mi.android.globallauncher".equals(ownerPackage)) {
            root.recycle();
            return false;
        }
        return containsClipboardUrl(root, 120);
    }

    // Takes ownership of root and recycles every inspected node.
    private boolean containsClipboardUrl(
            android.view.accessibility.AccessibilityNodeInfo root, int maxNodes) {
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> queue =
                new java.util.ArrayDeque<>();
        queue.add(root);
        int inspected = 0;
        try {
            while (!queue.isEmpty() && inspected < maxNodes) {
                android.view.accessibility.AccessibilityNodeInfo node = queue.removeFirst();
                inspected++;
                CharSequence text = node.getText();
                CharSequence description = node.getContentDescription();
                if (containsWebUrl(text) || containsWebUrl(description)) {
                    node.recycle();
                    return true;
                }
                for (int i = 0; i < node.getChildCount()
                        && inspected + queue.size() < maxNodes; i++) {
                    android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.addLast(child);
                }
                node.recycle();
            }
        } catch (Exception ignored) {
        } finally {
            while (!queue.isEmpty()) {
                try { queue.removeFirst().recycle(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private void captureWebVideoCasterUrls() {
        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence owner = root.getPackageName();
        if (owner == null || !WEB_VIDEO_CASTER_PACKAGE.equals(owner.toString())) {
            root.recycle();
            return;
        }

        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> queue =
                new java.util.ArrayDeque<>();
        queue.add(root);
        int inspected = 0;
        try {
            while (!queue.isEmpty() && inspected < 180 && found.size() < 12) {
                android.view.accessibility.AccessibilityNodeInfo node = queue.removeFirst();
                inspected++;
                addUrlsFromText(found, node.getText());
                addUrlsFromText(found, node.getContentDescription());
                if (Build.VERSION.SDK_INT >= 26) addUrlsFromText(found, node.getHintText());
                for (int i = 0; i < node.getChildCount()
                        && inspected + queue.size() < 180; i++) {
                    android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.addLast(child);
                }
                node.recycle();
            }
        } catch (Exception ignored) {
        } finally {
            while (!queue.isEmpty()) {
                try { queue.removeFirst().recycle(); } catch (Exception ignored) {}
            }
        }
        if (found.isEmpty()) return;

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        org.json.JSONArray existing;
        try {
            existing = new org.json.JSONArray(prefs.getString("wvc_captured_urls", "[]"));
        } catch (Exception ignored) {
            existing = new org.json.JSONArray();
        }
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        for (int i = 0; i < existing.length(); i++) {
            String value = existing.optString(i, "");
            if (!value.isEmpty()) merged.add(value);
        }
        int before = merged.size();
        merged.addAll(found);
        while (merged.size() > 12) {
            String first = merged.iterator().next();
            merged.remove(first);
        }
        if (merged.size() == before) return;

        org.json.JSONArray saved = new org.json.JSONArray();
        for (String value : merged) saved.put(value);
        prefs.edit()
                .putString("wvc_captured_urls", saved.toString())
                .putLong("wvc_last_capture_time", System.currentTimeMillis())
                .putInt("wvc_capture_count", merged.size())
                .apply();
        if (android.provider.Settings.canDrawOverlays(this)) handler.post(this::showBubble);
    }

    private void addUrlsFromText(java.util.Set<String> target, CharSequence value) {
        if (value == null || target.size() >= 12) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)https?://[^\\\\s<>\\\\\\"']+")
                .matcher(value);
        while (matcher.find() && target.size() < 12) {
            String candidate = matcher.group().replaceAll("[\\\\]),}،.؛!?]+$", "");
            try {
                android.net.Uri uri = android.net.Uri.parse(candidate);
                String host = uri.getHost();
                if (host != null && host.contains(".")
                        && host.matches("(?i)[a-z0-9.-]+")) target.add(candidate);
            } catch (Exception ignored) {}
        }
    }

    private boolean containsWebUrl(CharSequence value) {
        if (value == null) return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)https?://[^\\s<>\\\"']+")
                .matcher(value);
        while (matcher.find()) {
            String candidate = matcher.group().replaceAll("[\\]),}،.؛!?]+$", "");
            try {
                android.net.Uri uri = android.net.Uri.parse(candidate);
                String host = uri.getHost();
                if (host != null && host.contains(".")
                        && host.matches("(?i)[a-z0-9.-]+")) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private String describeEvent(AccessibilityEvent event) {
        StringBuilder out = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence item : event.getText()) {
                if (item != null && item.length() > 0) out.append(item).append(" | ");
            }
        }
        CharSequence description = event.getContentDescription();
        if (description != null) out.append(description).append(" | ");
        CharSequence className = event.getClassName();
        if (className != null) out.append("class=").append(className);
        String value = out.toString().trim();
        if (value.isEmpty()) value = "حدث بلا نص ظاهر";
        return value.length() > 180 ? value.substring(0, 180) : value;
    }

    private boolean isCopyLinkAction(AccessibilityEvent event) {
        if (event == null) return false;
        int type = event.getEventType();
        boolean clickEvent = type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                || (Build.VERSION.SDK_INT >= 23
                && type == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED);
        if (!clickEvent) return false;

        StringBuilder payload = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence item : event.getText()) {
                if (item != null) payload.append(item).append(' ');
            }
        }
        CharSequence description = event.getContentDescription();
        if (description != null) payload.append(description).append(' ');

        // Inspect only the clicked menu item and up to three parent labels.
        android.view.accessibility.AccessibilityNodeInfo node = event.getSource();
        for (int depth = 0; node != null && depth < 4; depth++) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            if (text != null) payload.append(text).append(' ');
            if (desc != null) payload.append(desc).append(' ');
            if (Build.VERSION.SDK_INT >= 26) {
                CharSequence hint = node.getHintText();
                if (hint != null) payload.append(hint).append(' ');
            }
            android.view.accessibility.AccessibilityNodeInfo parent = node.getParent();
            node.recycle();
            node = parent;
        }

        String text = payload.toString().toLowerCase(java.util.Locale.ROOT);
        // Deliberately require an explicit link/URL copy action. A generic
        // "Copy" could copy ordinary text, so it must not show the bubble.
        return text.contains("copy link") || text.contains("copy url")
                || text.contains("copy video link") || text.contains("copy video url")
                || text.contains("copy address") || text.contains("link kopieren")
                || text.contains("リンクをコピー") || text.contains("复制链接")
                || text.contains("نسخ الرابط") || text.contains("نسخ رابط")
                || text.contains("نسخ عنوان الرابط");
    }

    private void showBubble() {
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;
        removeBubble();

        int size = dp(48);
        android.content.SharedPreferences bubblePrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int pendingWvcCount = 0;
        try {
            pendingWvcCount = new org.json.JSONArray(
                    bubblePrefs.getString("wvc_captured_urls", "[]")).length();
        } catch (Exception ignored) {}
        View bubbleContent;
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.rgb(28, 103, 210));
        background.setStroke(dp(2), Color.WHITE);
        if (pendingWvcCount > 0) {
            android.widget.TextView countBadge = new android.widget.TextView(this);
            countBadge.setText(String.valueOf(pendingWvcCount));
            countBadge.setTextColor(Color.WHITE);
            countBadge.setTextSize(18);
            countBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            countBadge.setGravity(Gravity.CENTER);
            countBadge.setBackground(background);
            countBadge.setElevation(dp(8));
            countBadge.setContentDescription("AboEzzat Cast - " + pendingWvcCount + " روابط ملتقطة");
            bubbleContent = countBadge;
        } else {
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_launcher);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setPadding(dp(7), dp(7), dp(7), dp(7));
            icon.setElevation(dp(8));
            icon.setBackground(background);
            icon.setContentDescription("AboEzzat Cast - اضغط لإرسال الرابط");
            bubbleContent = icon;
        }

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

        bubbleContent.setOnTouchListener((view, event) -> {
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
                        launchPendingCaptureListOrClipboard();
                    }
                    return true;
            }
            return true;
        });

        bubble = bubbleContent;
        try {
            windowManager.addView(bubble, bubbleParams);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("diag_last_bubble_result", "نجح عرض الفقاعة")
                    .apply();
            hideRunnable = this::removeBubble;
            handler.postDelayed(hideRunnable, VISIBLE_MS);
        } catch (Exception e) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("diag_last_bubble_result",
                            "فشل عرض الفقاعة: " + e.getClass().getSimpleName())
                    .apply();
            bubble = null;
            bubbleParams = null;
        }
    }

    private void launchPendingCaptureListOrClipboard() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean hasPending = false;
        try {
            hasPending = new org.json.JSONArray(prefs.getString("wvc_captured_urls", "[]")).length() > 0;
        } catch (Exception ignored) {}
        removeBubble();
        Intent launch = new Intent(this, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (hasPending) {
            launch.putExtra("web_video_caster_capture_shortcut", true);
        } else {
            launch.putExtra("clipboard_cast_shortcut", true);
        }
        try { startActivity(launch); } catch (Exception ignored) {}
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("diag_service_connected", false).apply();
        removeBubble();
        if (clipboardManager != null && clipboardListener != null) {
            try { clipboardManager.removePrimaryClipChangedListener(clipboardListener); }
            catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
