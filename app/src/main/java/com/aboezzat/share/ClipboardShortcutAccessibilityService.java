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
    private View capturePanel;
    private Runnable hideRunnable;
    private float downRawX, downRawY;
    private int downX, downY;
    private boolean dragged;
    private long lastWebVideoCasterScanAt;
    private long lastSystemClipboardScanAt;
    private static final String WEB_VIDEO_CASTER_PACKAGE = "com.instantbits.cast.webvideo";

    private void recordLifecycle(String event) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS);
            String previous = prefs.getString("diag_lifecycle_log", "");
            String stamp = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date());
            String line = stamp + " | " + event + " | pid=" + android.os.Process.myPid();
            String[] old = previous.isEmpty() ? new String[0] : previous.split("\n");
            StringBuilder log = new StringBuilder();
            int start = Math.max(0, old.length - 11);
            for (int i = start; i < old.length; i++) {
                if (!old[i].isEmpty()) log.append(old[i]).append("\n");
            }
            log.append(line);
            prefs.edit().putString("diag_lifecycle_log", log.toString())
                    .putString("diag_last_lifecycle_event", event)
                    .putLong("diag_last_lifecycle_time", System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        recordLifecycle("SERVICE onCreate");
    }

    private android.content.ClipboardManager clipboardManager;
    private android.content.ClipboardManager.OnPrimaryClipChangedListener clipboardListener;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        recordLifecycle("SERVICE onServiceConnected");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
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
                    int count = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS)
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
                    getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
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
                getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
                        .putBoolean("diag_clipboard_listener_registered", true)
                        .putString("diag_clipboard_listener_status", "تم تسجيل مستمع الحافظة بنجاح")
                        .apply();
            } catch (Exception registrationError) {
                getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
                        .putBoolean("diag_clipboard_listener_registered", false)
                        .putString("diag_clipboard_listener_status",
                                "فشل التسجيل: " + registrationError.getClass().getSimpleName())
                        .apply();
            }
        }

        // Restore the persistent Web Video Caster bubble after Android restarts
        // this accessibility service. Existing URLs must remain visible even
        // when the next scan finds only duplicates and therefore adds nothing.
        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                org.json.JSONArray pending = new org.json.JSONArray(
                        getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS)
                                .getString("wvc_captured_urls", "[]"));
                if (pending.length() > 0) {
                    handler.postDelayed(this::showBubble, 350L);
                }
            } catch (Exception ignored) {}
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
                if (now - lastWebVideoCasterScanAt >= 500L) {
                    lastWebVideoCasterScanAt = now;
                    java.util.LinkedHashSet<String> eventUrls = new java.util.LinkedHashSet<>();
                    if (event.getText() != null) {
                        for (CharSequence item : event.getText()) addUrlsFromText(eventUrls, item);
                    }
                    addUrlsFromText(eventUrls, event.getContentDescription());
                    // A new window state means a new WVC page/session: discard the
                    // previous page's links before reading the current page.
                    if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                        getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
                                .putString("wvc_captured_urls", "[]")
                                .putInt("wvc_capture_count", 0).apply();
                    }
                    captureWebVideoCasterUrls(eventUrls);
                }
            }
        }

        // Ignore accessibility events generated by AboEzzat Cast itself.
        // Opening our own screens can expose words such as "copy" and must
        // never be counted as a user's copy action.
        if (getPackageName().equals(eventPackage)) {
            getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
                    .putString("diag_last_ignored_copy_package", eventPackage)
                    .apply();
            return;
        }

        // Keep diagnostics lightweight: writing SharedPreferences for every
        // accessibility event can create unnecessary disk/CPU work and may
        // contribute to the service becoming unresponsive on busy screens.
        boolean systemClipboardHost = "com.android.systemui".equals(eventPackage)
                || "com.mi.android.globallauncher".equals(eventPackage);
        int eventType = event.getEventType();
        boolean clickEvent = eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                || eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                || (Build.VERSION.SDK_INT >= 23
                && eventType == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED);

        if (clickEvent && !systemClipboardHost) {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS);
            prefs.edit()
                    .putInt("diag_source_click_count",
                            prefs.getInt("diag_source_click_count", 0) + 1)
                    .putString("diag_last_source_package", eventPackage)
                    .putString("diag_last_source_text", describeEvent(event))
                    .putString("diag_last_click_package", eventPackage)
                    .putString("diag_last_click_text", describeEvent(event))
                    .apply();
        }

        // First detect an explicit "Copy link/URL" action in the source app.
        // If the app does not expose that action, fall back to a URL shown by
        // Android/MIUI's own clipboard preview window.
        boolean copyConfirmed = clickEvent && isCopyLinkAction(event);
        if (!copyConfirmed && eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && systemClipboardHost) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastSystemClipboardScanAt >= 900L) {
                lastSystemClipboardScanAt = now;
                copyConfirmed = hasSystemClipboardUrl();
                getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
                        .putInt("diag_systemui_event_count",
                                getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS)
                                        .getInt("diag_systemui_event_count", 0) + 1)
                        .putBoolean("diag_last_systemui_url_match", copyConfirmed)
                        .putString("diag_last_clipboard_host", eventPackage)
                        .apply();
            }
        }

        android.content.SharedPreferences.Editor diagnosticEditor =
                getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
                        .putString("diag_last_accessibility_package",
                                packageName == null ? "غير معروف" : packageName.toString())
                        .putInt("diag_last_accessibility_type", event.getEventType());
        if (copyConfirmed) {
            int copyCount = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS)
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

    private void captureWebVideoCasterUrls(java.util.Set<String> eventUrls) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        // Scan every currently exposed interactive window, but accept nodes only
        // when their owning package is Web Video Caster.
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                for (android.view.accessibility.AccessibilityWindowInfo window : getWindows()) {
                    if (window == null) continue;
                    android.view.accessibility.AccessibilityNodeInfo windowRoot = null;
                    try {
                        windowRoot = window.getRoot();
                        if (windowRoot != null) {
                            CharSequence owner = windowRoot.getPackageName();
                            if (owner != null && WEB_VIDEO_CASTER_PACKAGE.equals(owner.toString())) {
                                scanWebVideoCasterTree(windowRoot, found, 240);
                                windowRoot = null; // scan method owns/recycles it
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (windowRoot != null) windowRoot.recycle();
                    }
                    if (found.size() >= 12) break;
                }
            } catch (Exception ignored) {
            }
        }

        // Fallback for Android builds where interactive-window enumeration is restricted.
        if (found.isEmpty()) {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence owner = root.getPackageName();
                if (owner != null && WEB_VIDEO_CASTER_PACKAGE.equals(owner.toString())) {
                    scanWebVideoCasterTree(root, found, 240);
                    root = null;
                }
                if (root != null) root.recycle();
            }
        }
        if (eventUrls != null) found.addAll(eventUrls);
        while (found.size() > 12) {
            String last = null;
            for (String value : found) last = value;
            if (last != null) found.remove(last);
        }

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS);
        org.json.JSONArray saved = new org.json.JSONArray();
        for (String value : found) saved.put(value);
        String nextSnapshot = saved.toString();
        String previousSnapshot = prefs.getString("wvc_captured_urls", "[]");
        if (nextSnapshot.equals(previousSnapshot)) return;

        prefs.edit()
                .putString("wvc_captured_urls", nextSnapshot)
                .putLong("wvc_last_capture_time", System.currentTimeMillis())
                .putInt("wvc_capture_count", found.size())
                .apply();
        if (found.isEmpty()) {
            handler.post(() -> {
                removeBubble();
                removeCaptureOverlay();
            });
        } else if (android.provider.Settings.canDrawOverlays(this)) {
            handler.post(this::showBubble);
        }
    }

    // Takes ownership of root and recycles every visited accessibility node.
    private void scanWebVideoCasterTree(
            android.view.accessibility.AccessibilityNodeInfo root,
            java.util.Set<String> found, int maxNodes) {
        if (root == null) return;
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> queue =
                new java.util.ArrayDeque<>();
        queue.add(root);
        int inspected = 0;
        try {
            while (!queue.isEmpty() && inspected < maxNodes && found.size() < 12) {
                android.view.accessibility.AccessibilityNodeInfo node = queue.removeFirst();
                inspected++;
                addUrlsFromText(found, node.getText());
                addUrlsFromText(found, node.getContentDescription());
                if (Build.VERSION.SDK_INT >= 26) {
                    addUrlsFromText(found, node.getHintText());
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    addUrlsFromText(found, node.getTooltipText());
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    addUrlsFromText(found, node.getStateDescription());
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
    }

    private void addUrlsFromText(java.util.Set<String> target, CharSequence value) {
        if (value == null || target.size() >= 12) return;
        String source = value.toString()
                .replace("\\\\/", "/")
                .replace("\\\\u0026", "&")
                .replace("\\\\u003d", "=")
                .replace("&amp;", "&")
                .replace("&#38;", "&");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)https?://[^\\s<>\\\"']+")
                .matcher(source);
        while (matcher.find() && target.size() < 12) {
            String candidate = matcher.group().replaceAll("[\\]),}،.؛!?]+$", "");
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
        android.content.SharedPreferences bubblePrefs = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS);
        int pendingWvcCount = 0;
        try {
            pendingWvcCount = new org.json.JSONArray(
                    bubblePrefs.getString("wvc_captured_urls", "[]")).length();
        } catch (Exception ignored) {}
        android.widget.FrameLayout bubbleContent = new android.widget.FrameLayout(this);
        GradientDrawable bubbleBackground = new GradientDrawable();
        bubbleBackground.setShape(GradientDrawable.OVAL);
        bubbleBackground.setColor(Color.rgb(19, 35, 58));
        bubbleBackground.setStroke(dp(2), Color.rgb(92, 166, 255));
        bubbleContent.setBackground(bubbleBackground);
        bubbleContent.setElevation(dp(10));

        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.ic_launcher);
        appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appIcon.setPadding(dp(7), dp(7), dp(7), dp(7));
        android.widget.FrameLayout.LayoutParams iconParams =
                new android.widget.FrameLayout.LayoutParams(-1, -1);
        bubbleContent.addView(appIcon, iconParams);

        if (pendingWvcCount > 0) {
            android.widget.TextView countBadge = new android.widget.TextView(this);
            countBadge.setText(String.valueOf(pendingWvcCount));
            countBadge.setTextColor(Color.WHITE);
            countBadge.setTextSize(10);
            countBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            countBadge.setGravity(Gravity.CENTER);
            countBadge.setMinWidth(dp(18));
            countBadge.setPadding(dp(4), 0, dp(4), 0);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(Color.rgb(238, 67, 83));
            badgeBg.setCornerRadius(dp(12));
            badgeBg.setStroke(dp(1), Color.WHITE);
            countBadge.setBackground(badgeBg);
            android.widget.FrameLayout.LayoutParams badgeParams =
                    new android.widget.FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP | Gravity.END);
            bubbleContent.addView(countBadge, badgeParams);
        }
        bubbleContent.setContentDescription("AboEzzat Cast - " + pendingWvcCount + " روابط ملتقطة");

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
        bubbleParams.x = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).getInt("clipboard_bubble_x", dp(16));
        bubbleParams.y = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).getInt("clipboard_bubble_y", dp(220));

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
                        getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
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
            getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
                    .putString("diag_last_bubble_result", "نجح عرض الفقاعة")
                    .apply();
            // Every bubble is a temporary indicator: disappear after exactly
            // three seconds unless the user opens it first. A fresh capture calls
            // showBubble() again and restarts this timeout.
            hideRunnable = this::removeBubble;
            handler.postDelayed(hideRunnable, VISIBLE_MS);
        } catch (Exception e) {
            getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
                    .putString("diag_last_bubble_result",
                            "فشل عرض الفقاعة: " + e.getClass().getSimpleName())
                    .apply();
            bubble = null;
            bubbleParams = null;
        }
    }

    private void launchPendingCaptureListOrClipboard() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS);
        boolean hasPending = false;
        try {
            hasPending = new org.json.JSONArray(prefs.getString("wvc_captured_urls", "[]")).length() > 0;
        } catch (Exception ignored) {}
        removeBubble();
        if (hasPending) {
            showCaptureOverlay();
            return;
        }
        Intent launch = new Intent(this, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        launch.putExtra("clipboard_cast_shortcut", true);
        try { startActivity(launch); } catch (Exception ignored) {}
    }

    // Web Video Caster links are shown in a true system overlay. Opening the
    // captured-link list does not launch an Activity or create a recent-task card.
    private void showCaptureOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;
        removeCaptureOverlay();

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS);
        java.util.ArrayList<String> urls = new java.util.ArrayList<>();
        try {
            org.json.JSONArray array = new org.json.JSONArray(prefs.getString("wvc_captured_urls", "[]"));
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (value.startsWith("http://") || value.startsWith("https://")) urls.add(value);
            }
        } catch (Exception ignored) {}

        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.rgb(15, 23, 38));
        cardBg.setCornerRadius(dp(22));
        cardBg.setStroke(dp(1), Color.rgb(55, 91, 139));
        card.setBackground(cardBg);
        card.setElevation(dp(18));

        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageResource(R.drawable.ic_launcher);
        brandIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(brandIcon, new android.widget.LinearLayout.LayoutParams(dp(42), dp(42)));

        android.widget.LinearLayout heading = new android.widget.LinearLayout(this);
        heading.setOrientation(android.widget.LinearLayout.VERTICAL);
        heading.setPadding(dp(10), 0, dp(4), 0);
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("AboEzzat Cast");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(title);
        android.widget.TextView subtitle = new android.widget.TextView(this);
        subtitle.setText(urls.size() + " رابط ملتقط من Web Video Caster");
        subtitle.setTextColor(Color.rgb(155, 177, 205));
        subtitle.setTextSize(11);
        heading.addView(subtitle);
        header.addView(heading, new android.widget.LinearLayout.LayoutParams(0, -2, 1));

        android.widget.TextView close = new android.widget.TextView(this);
        close.setText("✕");
        close.setTextColor(Color.rgb(205, 218, 236));
        close.setTextSize(18);
        close.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(Color.rgb(35, 48, 68));
        closeBg.setCornerRadius(dp(12));
        close.setBackground(closeBg);
        header.addView(close, new android.widget.LinearLayout.LayoutParams(dp(38), dp(38)));
        close.setOnClickListener(v -> removeCaptureOverlay());
        card.addView(header);

        View separator = new View(this);
        separator.setBackgroundColor(Color.rgb(42, 58, 80));
        android.widget.LinearLayout.LayoutParams separatorParams =
                new android.widget.LinearLayout.LayoutParams(-1, dp(1));
        separatorParams.topMargin = dp(12);
        separatorParams.bottomMargin = dp(10);
        card.addView(separator, separatorParams);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(false);
        android.widget.LinearLayout rows = new android.widget.LinearLayout(this);
        rows.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int i = 0; i < urls.size(); i++) {
            final String selected = urls.get(i);
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.VERTICAL);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(Color.rgb(27, 39, 57));
            rowBg.setCornerRadius(dp(15));
            rowBg.setStroke(dp(1), Color.rgb(43, 61, 86));
            row.setBackground(rowBg);

            android.widget.LinearLayout topLine = new android.widget.LinearLayout(this);
            topLine.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.TextView number = new android.widget.TextView(this);
            number.setText(String.format(java.util.Locale.ROOT, "%02d", i + 1));
            number.setTextColor(Color.rgb(112, 177, 255));
            number.setTextSize(13);
            number.setTypeface(null, android.graphics.Typeface.BOLD);
            topLine.addView(number, new android.widget.LinearLayout.LayoutParams(dp(32), -2));
            android.widget.TextView urlText = new android.widget.TextView(this);
            urlText.setText(selected);
            urlText.setTextColor(Color.rgb(226, 235, 247));
            urlText.setTextSize(11);
            urlText.setMaxLines(1);
            urlText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            topLine.addView(urlText, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
            row.addView(topLine);

            row.setContentDescription("إرسال الرابط رقم " + (i + 1) + " إلى Receiver");
            row.setClickable(true);
            row.setOnClickListener(v -> sendCapturedUrl(selected));

            android.widget.LinearLayout.LayoutParams rowParams =
                    new android.widget.LinearLayout.LayoutParams(-1, dp(48));
            rowParams.bottomMargin = dp(5);
            rows.addView(row, rowParams);
        }
        if (urls.isEmpty()) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText("لسه مفيش روابط ملتقطة");
            empty.setTextColor(Color.rgb(175, 190, 210));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(22), dp(8), dp(22));
            rows.addView(empty);
        }
        scroll.addView(rows);
        int visibleListHeight = Math.min(dp(250), Math.max(dp(48), urls.size() * dp(53)));
        card.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, visibleListHeight));

        android.widget.LinearLayout footer = new android.widget.LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("اختر الرابط لإرساله إلى شاشة التلفزيون");
        hint.setTextColor(Color.rgb(137, 158, 187));
        hint.setTextSize(10);
        footer.addView(hint, new android.widget.LinearLayout.LayoutParams(0, dp(38), 1));

        android.widget.TextView clear = new android.widget.TextView(this);
        clear.setText("مسح الكل");
        clear.setTextColor(Color.rgb(255, 139, 145));
        clear.setTextSize(11);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(dp(10), 0, dp(4), 0);
        footer.addView(clear, new android.widget.LinearLayout.LayoutParams(-2, dp(38)));
        clear.setOnClickListener(v -> {
            prefs.edit().remove("wvc_captured_urls").putInt("wvc_capture_count", 0).apply();
            removeCaptureOverlay();
            // Clearing the final captured URL closes the shortcut as well.
            // Do not recreate an empty bubble that opens a dead/empty list.
            removeBubble();
        });
        card.addView(footer);

        int overlayType = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int panelWidth = Math.min(dp(360), screenWidth - dp(28));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                panelWidth, WindowManager.LayoutParams.WRAP_CONTENT, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(76);
        capturePanel = card;
        try {
            windowManager.addView(capturePanel, params);
        } catch (Exception e) {
            capturePanel = null;
        }
    }

    private void sendCapturedUrl(String mediaUrl) {
        // Close the floating list immediately on selection; network delivery
        // continues in the background.
        removeCaptureOverlay();
        String tvIp = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).getString("tv_ip", "").trim();
        if (tvIp.isEmpty()) {
            android.widget.Toast.makeText(this, "يرجى ضبط عنوان AboEzzat Receiver أولاً",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String endpoint = tvIp;
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://"))
                endpoint = "http://" + endpoint;
            if (!endpoint.matches("(?i)^https?://[^/]+:\\d+.*$")) endpoint += ":8081";
            final String requestUrl = endpoint + "/?url="
                    + java.net.URLEncoder.encode(mediaUrl, "UTF-8");
            new Thread(() -> {
                java.net.HttpURLConnection connection = null;
                boolean ok = false;
                try {
                    connection = (java.net.HttpURLConnection) new java.net.URL(requestUrl).openConnection();
                    connection.setConnectTimeout(7000);
                    connection.setReadTimeout(7000);
                    connection.setRequestMethod("GET");
                    ok = connection.getResponseCode() >= 200 && connection.getResponseCode() < 400;
                } catch (Exception ignored) {
                } finally {
                    if (connection != null) connection.disconnect();
                }
                final boolean accepted = ok;
                handler.post(() -> {
                    android.widget.Toast.makeText(this,
                            accepted ? "تم إرسال الرابط إلى Receiver" : "تعذر إرسال الرابط؛ راجع اتصال Receiver",
                            android.widget.Toast.LENGTH_SHORT).show();
                    if (accepted) {
                        removeCapturedUrl(mediaUrl);
                    }
                });
            }).start();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "الرابط غير صالح للإرسال",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void removeCapturedUrl(String selectedUrl) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS);
        try {
            org.json.JSONArray old = new org.json.JSONArray(prefs.getString("wvc_captured_urls", "[]"));
            org.json.JSONArray updated = new org.json.JSONArray();
            for (int i = 0; i < old.length(); i++) {
                String value = old.optString(i, "");
                if (!selectedUrl.equals(value)) updated.put(value);
            }
            prefs.edit().putString("wvc_captured_urls", updated.toString())
                    .putInt("wvc_capture_count", updated.length()).apply();
        } catch (Exception ignored) {}
    }

    private void openCapturedUrlInBrowser(String url) {
        try {
            Intent intent = new Intent(this, BrowserActivity.class);
            intent.putExtra("url", url);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(intent);
            removeCaptureOverlay();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "تعذر فتح الرابط في المتصفح",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void removeCaptureOverlay() {
        if (capturePanel != null && windowManager != null) {
            try { windowManager.removeView(capturePanel); } catch (Exception ignored) {}
        }
        capturePanel = null;
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
    public void onInterrupt() { recordLifecycle("SERVICE onInterrupt"); removeBubble(); removeCaptureOverlay(); }

    @Override
    public void onDestroy() {
        recordLifecycle("SERVICE onDestroy");
        getSharedPreferences(PREFS, MODE_PRIVATE | MODE_MULTI_PROCESS).edit()
                .putBoolean("diag_service_connected", false).apply();
        removeBubble();
        removeCaptureOverlay();
        if (clipboardManager != null && clipboardListener != null) {
            try { clipboardManager.removePrimaryClipChangedListener(clipboardListener); }
            catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
