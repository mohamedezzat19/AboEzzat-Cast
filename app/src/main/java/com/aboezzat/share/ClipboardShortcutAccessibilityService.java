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
                .putString("diag_last_bubble_result", "لم تتم محاولة عرض الفقاعة بعد")
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
                            // MIME type alone does not prove that the copied text is a URL.
                            // The SystemUI clipboard preview is checked below instead.
                        }
                    }
                } catch (Exception ignored) {}
            };
            try { clipboardManager.addPrimaryClipChangedListener(clipboardListener); }
            catch (Exception ignored) {}
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence packageName = event.getPackageName();
        String eventPackage = packageName == null ? "" : packageName.toString();

        // Ignore accessibility events generated by AboEzzat Cast itself.
        // Opening our own screens can expose words such as "copy" and must
        // never be counted as a user's copy action.
        if (getPackageName().equals(eventPackage)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("diag_last_ignored_copy_package", eventPackage)
                    .apply();
            return;
        }

        // Do not depend on a localized "Copied" toast. On Android/MIUI,
        // inspect only the SystemUI clipboard-preview window and trigger only
        // when that system-owned window actually exposes an HTTP(S) URL.
        boolean copyConfirmed = false;
        boolean systemClipboardHost = "com.android.systemui".equals(eventPackage)
                || "com.mi.android.globallauncher".equals(eventPackage);
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
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

    private boolean isCopyConfirmation(AccessibilityEvent event) {
        // Inspect only the clicked control label and event text for copy actions.
        // Do not traverse the screen, inspect page contents, or read the clipboard here.
        StringBuilder payload = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence item : event.getText()) {
                if (item != null) payload.append(item).append(' ');
            }
        }
        CharSequence description = event.getContentDescription();
        if (description != null) payload.append(description).append(' ');
        // Some apps expose the clicked context-menu item ("Copy link") only
        // through the event source node, not through event.getText().
        int type = event.getEventType();
        // Read the clicked item and a few parent labels. Some apps (including
        // video apps) expose the copy action on a menu row's parent rather than
        // on the row itself. This is label inspection only, not screen scraping.
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                || (Build.VERSION.SDK_INT >= 23 && type == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED)) {
            android.view.accessibility.AccessibilityNodeInfo node = event.getSource();
            for (int depth = 0; node != null && depth < 4; depth++) {
                CharSequence nodeText = node.getText();
                CharSequence nodeDescription = node.getContentDescription();
                if (nodeText != null) payload.append(nodeText).append(' ');
                if (nodeDescription != null) payload.append(nodeDescription).append(' ');
                if (Build.VERSION.SDK_INT >= 26) {
                    CharSequence hint = node.getHintText();
                    if (hint != null) payload.append(hint).append(' ');
                }
                android.view.accessibility.AccessibilityNodeInfo parent = node.getParent();
                node.recycle();
                node = parent;
            }
        }

        // Some Android/OEM clipboard overlays announce the action using a
        // notification or announcement event instead of a view click.
        if (type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_ANNOUNCEMENT
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                && !(Build.VERSION.SDK_INT >= 23 && type == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED)) {
            return false;
        }

        String text = payload.toString().trim().toLowerCase(java.util.Locale.ROOT);
        boolean isCopyMenuAction = type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                || (Build.VERSION.SDK_INT >= 23 && type == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED);
        boolean copyConfirmed = text.contains("copied to clipboard")
                || text.contains("link copied")
                || text.contains("copied")
                || text.contains("copy link")
                || text.contains("copy video link")
                || text.contains("copy url")
                || text.contains("copy video url")
                || text.contains("copy address")
                || (isCopyMenuAction && (text.contains("copy") || text.contains("نسخ"))) 
                || text.contains("تم النسخ")
                || text.contains("تم نسخ")
                || text.contains("تم النسخ إلى الحافظة")
                || text.contains("تم نسخ الرابط")
                || text.contains("نسخ إلى الحافظة")
                || text.contains("نسخ الرابط")
                || text.contains("نسخ رابط الفيديو")
                || text.contains("نسخ عنوان الرابط")
                || text.contains("copiado")
                || text.contains("enlace copiado")
                || text.contains("copié")
                || text.contains("lien copié")
                || text.contains("kopiert")
                || text.contains("link kopieren")
                || text.contains("リンクをコピー")
                || text.contains("リンクをコピーしました")
                || text.contains("复制链接")
                || text.contains("已复制");
        if (!copyConfirmed) return false;

        // Many apps expose only a short "Copied" toast through Accessibility
        // and omit the copied URL. Do not require the URL in this event: Android
        // restricts background clipboard reads. The URL is checked only after
        // the user taps the bubble and MainActivity is brought to the foreground.
        return true;
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
