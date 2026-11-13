package com.aboezzat.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DownloadManagerDialog {
    private static final String PREFS = "AboEzzatSharePrefs";
    private static final String KEY = "in_app_download_items";
    private static final int BG = Color.rgb(10, 16, 29);
    private static final int CARD = Color.rgb(23, 33, 51);
    private static final int MUTED = Color.rgb(163, 178, 199);
    private static final int BLUE = Color.rgb(20, 125, 205);
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private final Activity activity;
    private final String referer;
    private AlertDialog dialog;
    private final LinearLayout rows;
    private final TextView summary;
    private final TextView stats;
    private final EditText search;
    private final ScrollView scroll;
    private final LinearLayout filters;
    private final String[] categories = {"all", "video", "audio", "image", "file"};
    private final String[] categoryLabels = {"الكل", "فيديو", "صوت", "صور", "ملفات"};
    private String category;
    private String query = "";
    private final Map<Long, long[]> samples = new HashMap<>();
    private final Map<String, Bitmap> thumbnails = new HashMap<>();
    private final java.util.Set<String> loadingThumbnails = new java.util.HashSet<>();
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            if (!dialog.isShowing()) return;
            render();
            HANDLER.postDelayed(this, 1000L);
        }
    };

    static void show(Activity activity, String initialCategory, String referer) {
        if (activity == null || activity.isFinishing()) return;
        new DownloadManagerDialog(activity, initialCategory, referer).open();
    }

    private DownloadManagerDialog(Activity activity, String initialCategory, String referer) {
        this.activity = activity;
        this.category = initialCategory == null ? "all" : initialCategory;
        this.referer = referer == null ? "" : referer;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(8));
        root.setBackgroundColor(BG);

        TextView heading = text("تنزيلاتي", 21, Color.WHITE, true);
        root.addView(heading);
        TextView subtitle = text("كل ملفاتك • متابعة مباشرة • إدارة كاملة", 12, MUTED, false);
        LinearLayout.LayoutParams subParams = params(-1, -2);
        subParams.topMargin = dp(2);
        root.addView(subtitle, subParams);

        stats = text("", 12, Color.WHITE, false);
        stats.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable statsBg = rounded(Color.rgb(29, 46, 68), dp(12), 0);
        stats.setBackground(statsBg);
        LinearLayout.LayoutParams statsParams = params(-1, -2);
        statsParams.topMargin = dp(12);
        root.addView(stats, statsParams);

        search = new EditText(activity);
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(MUTED);
        search.setHint("ابحث باسم الملف أو نوعه...");
        search.setTextSize(14);
        search.setPadding(dp(13), 0, dp(13), 0);
        search.setBackground(rounded(CARD, dp(10), Color.rgb(48, 65, 88)));
        LinearLayout.LayoutParams searchParams = params(-1, dp(46));
        searchParams.topMargin = dp(10);
        root.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        android.widget.HorizontalScrollView filterScroll = new android.widget.HorizontalScrollView(activity);
        filterScroll.setHorizontalScrollBarEnabled(false);
        filters = new LinearLayout(activity);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filterScroll.addView(filters);
        LinearLayout.LayoutParams filterParams = params(-1, dp(46));
        filterParams.topMargin = dp(5);
        root.addView(filterScroll, filterParams);
        buildFilters();

        summary = text("", 12, MUTED, false);
        LinearLayout.LayoutParams summaryParams = params(-1, -2);
        summaryParams.topMargin = dp(5);
        summaryParams.bottomMargin = dp(3);
        root.addView(summary, summaryParams);

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(5), 0, dp(12));
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button clearCompleted = new Button(activity);
        clearCompleted.setText("مسح التنزيلات المكتملة");
        clearCompleted.setAllCaps(false);
        clearCompleted.setTextColor(Color.WHITE);
        clearCompleted.setTextSize(13);
        clearCompleted.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(65, 77, 96)));
        LinearLayout.LayoutParams clearParams = params(-1, dp(44));
        clearParams.topMargin = dp(6);
        root.addView(clearCompleted, clearParams);
        clearCompleted.setOnClickListener(v -> clearCompleted());

        dialog = new AlertDialog.Builder(activity).setView(root)
                .setNegativeButton("إغلاق", null).create();
        dialog.setOnDismissListener(d -> HANDLER.removeCallbacks(refreshTask));
    }

    private void open() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.88f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.68f;
            window.setAttributes(lp);
        }
        render();
        HANDLER.postDelayed(refreshTask, 1000L);
    }

    private void buildFilters() {
        filters.removeAllViews();
        for (int i = 0; i < categories.length; i++) {
            final String value = categories[i];
            Button b = new Button(activity);
            b.setText(categoryLabels[i]);
            b.setAllCaps(false);
            b.setTextSize(12);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding(dp(13), 0, dp(13), 0);
            b.setTextColor(Color.WHITE);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    value.equals(category) ? BLUE : Color.rgb(39, 51, 70)));
            LinearLayout.LayoutParams p = params(-2, dp(36));
            p.setMargins(dp(2), dp(4), dp(5), dp(3));
            filters.addView(b, p);
            b.setOnClickListener(v -> {
                category = value;
                buildFilters();
                render();
            });
        }
    }

    private void render() {
        if (activity.isFinishing()) return;
        int oldY = scroll.getScrollY();
        JSONArray data = readItems();
        List<JSONObject> all = new ArrayList<>();
        int downloading = 0, complete = 0, failed = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            all.add(item);
            String status = item.optString("status", "failed");
            if ("downloading".equals(status)) downloading++;
            else if ("complete".equals(status)) complete++;
            else failed++;
        }
        Collections.sort(all, (a, b) -> Long.compare(b.optLong("created", b.optLong("id", 0)),
                a.optLong("created", a.optLong("id", 0))));
        stats.setText("قيد التنزيل  " + downloading + "     •     مكتمل  " + complete
                + "     •     فشل  " + failed + "     •     الإجمالي  " + all.size());

        rows.removeAllViews();
        int shown = 0;
        for (JSONObject item : all) {
            String type = category(item);
            if (!"all".equals(category) && !category.equals(type)) continue;
            String title = item.optString("title", item.optString("file", "ملف بدون اسم"));
            String file = item.optString("file", "");
            if (!query.isEmpty() && !(title + " " + file + " " + type).toLowerCase(Locale.ROOT).contains(query)) continue;
            shown++;
            addCard(item, type);
        }
        summary.setText(shown + " نتيجة ظاهرة من " + all.size() + " تنزيل");
        if (shown == 0) {
            TextView empty = text(all.isEmpty() ? "لا توجد تنزيلات حتى الآن\nابدأ تنزيل ملف من المتصفح وسيظهر هنا." :
                    "لا توجد نتائج مطابقة للبحث أو القسم المحدد.", 14, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(42), dp(16), dp(42));
            rows.addView(empty, params(-1, -2));
        }
        scroll.post(() -> scroll.scrollTo(0, Math.min(oldY, Math.max(0, rows.getHeight() - scroll.getHeight()))));
    }

    private void addCard(JSONObject item, String type) {
        long id = item.optLong("id", 0L);
        String status = item.optString("status", "failed");
        long received = item.optLong("bytes", 0L);
        long total = item.optLong("total", -1L);
        int progressValue = item.optInt("progress", -1);
        String stage = item.optString("stage", "");
        long[] sample = samples.get(id);
        long now = System.currentTimeMillis();
        double speed = 0;
        if (sample != null && now > sample[1] && received >= sample[0]) {
            speed = (received - sample[0]) * 1000.0 / (now - sample[1]);
        }
        samples.put(id, new long[]{received, now});

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(11), dp(12), dp(11));
        card.setBackground(rounded(CARD, dp(14), Color.rgb(45, 61, 83)));
        LinearLayout.LayoutParams cardP = params(-1, -2);
        cardP.bottomMargin = dp(9);
        rows.addView(card, cardP);

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(top);

        ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(rounded(Color.rgb(40, 54, 73), dp(10), 0));
        LinearLayout.LayoutParams thumbP = params(dp(76), dp(76));
        top.addView(thumb, thumbP);
        String poster = item.optString("poster", item.optString("thumbnail", ""));
        if (!poster.isEmpty()) loadThumbnail(poster, thumb);
        else {
            thumb.setImageResource(android.R.drawable.ic_menu_save);
            thumb.setScaleType(ImageView.ScaleType.CENTER);
            thumb.setPadding(dp(20), dp(20), dp(20), dp(20));
            thumb.setColorFilter(MUTED);
        }

        LinearLayout details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(11), 0, dp(5), 0);
        top.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView name = text(item.optString("title", item.optString("file", "ملف بدون اسم")),
                14, Color.WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(name);
        TextView fileName = text(item.optString("file", "اسم الملف غير متاح"), 10, MUTED, false);
        fileName.setSingleLine(true);
        fileName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        details.addView(fileName);
        TextView kind = text(typeLabel(type) + "  •  " + item.optString("mime", "نوع الملف غير معروف"),
                10, MUTED, false);
        kind.setMaxLines(1);
        kind.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(kind);

        TextView menu = text("⋮", 26, Color.WHITE, false);
        menu.setGravity(Gravity.CENTER);
        top.addView(menu, params(dp(32), dp(50)));
        menu.setOnClickListener(v -> showActions(item));

        String state;
        int stateColor;
        if ("complete".equals(status)) {
            state = "اكتمل التنزيل";
            stateColor = Color.rgb(104, 220, 150);
        } else if ("failed".equals(status)) {
            state = "فشل التنزيل • " + item.optString("error", "سبب غير محدد");
            stateColor = Color.rgb(255, 135, 125);
        } else {
            state = stage.isEmpty() ? "جارٍ التنزيل" : stage;
            stateColor = Color.rgb(255, 205, 105);
        }
        TextView stateView = text(state, 12, stateColor, true);
        stateView.setMaxLines(2);
        stateView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams stateP = params(-1, -2);
        stateP.topMargin = dp(9);
        card.addView(stateView, stateP);

        if ("downloading".equals(status)) {
            ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            if (progressValue >= 0) bar.setProgress(Math.min(99, progressValue));
            else bar.setIndeterminate(true);
            LinearLayout.LayoutParams bp = params(-1, dp(5));
            bp.topMargin = dp(7);
            card.addView(bar, bp);
        }

        String progressLine;
        if (total > 0) {
            progressLine = formatSize(received) + " من " + formatSize(total)
                    + (progressValue >= 0 ? "  •  " + progressValue + "%" : "");
        } else {
            progressLine = "تم استلام " + formatSize(received)
                    + (received == 0 && "downloading".equals(status) ? "  •  في انتظار البيانات" : "");
        }
        TextView bytes = text(progressLine, 11, MUTED, false);
        LinearLayout.LayoutParams bytesP = params(-1, -2);
        bytesP.topMargin = dp(6);
        card.addView(bytes, bytesP);

        if ("downloading".equals(status) && speed > 1) {
            String speedLine = "السرعة: " + formatSize((long) speed) + "/ث";
            if (total > received && total > 0) {
                long seconds = (long) ((total - received) / speed);
                speedLine += "  •  المتبقي: " + formatDuration(seconds);
            }
            TextView speedView = text(speedLine, 11, Color.rgb(117, 190, 245), false);
            card.addView(speedView);
        } else if ("complete".equals(status)) {
            TextView done = text("الحجم النهائي: " + formatSize(received), 11, MUTED, false);
            card.addView(done);
        }

        TextView date = text(formatDate(item.optLong("created", item.optLong("id", 0))),
                10, MUTED, false);
        LinearLayout.LayoutParams dateP = params(-1, -2);
        dateP.topMargin = dp(4);
        card.addView(date, dateP);

        card.setOnClickListener(v -> showActions(item));
    }

    private void showActions(JSONObject item) {
        String status = item.optString("status", "");
        String uri = item.optString("uri", "");
        boolean complete = "complete".equals(status) && item.optLong("bytes", 0) > 0 && !uri.isEmpty();
        boolean active = "downloading".equals(status);
        List<String> options = new ArrayList<>();
        if (complete) {
            options.add("فتح الملف");
            options.add("مشاركة الملف");
            options.add("حذف الملف من الجهاز والقائمة");
            options.add("عرض التفاصيل");
        } else if (active) {
            options.add("عرض التفاصيل");
        } else {
            options.add("إعادة المحاولة");
            options.add("عرض التفاصيل");
            options.add("إزالة من القائمة");
        }
        new AlertDialog.Builder(activity).setTitle(item.optString("title", "التنزيل"))
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    String choice = options.get(which);
                    if ("فتح الملف".equals(choice)) openFile(item);
                    else if ("مشاركة الملف".equals(choice)) shareFile(item);
                    else if ("حذف الملف من الجهاز والقائمة".equals(choice)) remove(item, true);
                    else if ("إزالة من القائمة".equals(choice)) remove(item, false);
                    else if ("إعادة المحاولة".equals(choice)) retry(item);
                    else showDetails(item);
                }).setNegativeButton("إلغاء", null).show();
    }

    private void openFile(JSONObject item) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(item.optString("uri")), item.optString("mime", "*/*"));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "لا يوجد تطبيق مناسب لفتح هذا الملف", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareFile(JSONObject item) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(item.optString("mime", "*/*"));
            intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(item.optString("uri")));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, "مشاركة الملف"));
        } catch (Exception e) {
            Toast.makeText(activity, "تعذرت مشاركة الملف", Toast.LENGTH_SHORT).show();
        }
    }

    private void retry(JSONObject item) {
        String url = item.optString("url", "");
        if (url.isEmpty()) {
            Toast.makeText(activity, "لا يوجد رابط مباشر محفوظ لإعادة المحاولة", Toast.LENGTH_LONG).show();
            return;
        }
        removeRecord(item.optLong("id", -1));
        BrowserFileDownloader.start(activity, url, item.optString("type", "video"),
                item.optString("mime", "video/mp4"), referer,
                item.optString("title", ""), item.optString("poster", item.optString("thumbnail", "")));
        render();
    }

    private void remove(JSONObject item, boolean deletePhysicalFile) {
        if (deletePhysicalFile) BrowserFileDownloader.deleteFile(activity, item);
        removeRecord(item.optLong("id", -1));
        render();
    }

    private void removeRecord(long id) {
        JSONArray old = readItems();
        JSONArray updated = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null && item.optLong("id", -1) != id) updated.put(item);
        }
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putString(KEY, updated.toString()).apply();
    }

    private void clearCompleted() {
        JSONArray old = readItems();
        JSONArray updated = new JSONArray();
        int removed = 0;
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null) continue;
            if ("complete".equals(item.optString("status", ""))) {
                BrowserFileDownloader.deleteFile(activity, item);
                removed++;
            } else updated.put(item);
        }
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putString(KEY, updated.toString()).apply();
        Toast.makeText(activity, "تم حذف " + removed + " تنزيل مكتمل", Toast.LENGTH_SHORT).show();
        render();
    }

    private void showDetails(JSONObject item) {
        String details = "الاسم: " + item.optString("title", "غير متاح")
                + "\nاسم الملف: " + item.optString("file", "غير متاح")
                + "\nالنوع: " + item.optString("mime", "غير معروف")
                + "\nالحالة: " + item.optString("status", "غير معروفة")
                + "\nالحجم المستلم: " + formatSize(item.optLong("bytes", 0))
                + "\nالحجم الكلي: " + (item.optLong("total", -1) > 0 ? formatSize(item.optLong("total")) : "غير معروف")
                + "\nنسبة التقدم: " + item.optInt("progress", -1) + "%"
                + "\nالمرحلة: " + item.optString("stage", "—")
                + "\nتاريخ الإضافة: " + formatDate(item.optLong("created", item.optLong("id", 0)))
                + (item.optString("error", "").isEmpty() ? "" : "\nسبب الفشل: " + item.optString("error"));
        new AlertDialog.Builder(activity).setTitle("تفاصيل التنزيل").setMessage(details)
                .setPositiveButton("حسنًا", null).show();
    }

    private void loadThumbnail(String url, ImageView target) {
        Bitmap cached = thumbnails.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageResource(android.R.drawable.ic_menu_gallery);
        target.setTag(url);
        if (!loadingThumbnails.add(url)) return;
        android.os.AsyncTask.execute(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setInstanceFollowRedirects(true);
                connection.connect();
                try (InputStream in = connection.getInputStream()) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    bitmap = BitmapFactory.decodeStream(in, null, options);
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
            final Bitmap result = bitmap;
            activity.runOnUiThread(() -> {
                loadingThumbnails.remove(url);
                if (result != null) {
                    thumbnails.put(url, result);
                    if (url.equals(target.getTag())) target.setImageBitmap(result);
                }
            });
        });
    }

    private JSONArray readItems() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
            return new JSONArray(prefs.getString(KEY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private String category(JSONObject item) {
        String type = item.optString("type", "").toLowerCase(Locale.ROOT);
        String mime = item.optString("mime", "").toLowerCase(Locale.ROOT);
        String file = item.optString("file", "").toLowerCase(Locale.ROOT);
        if ("audio".equals(type) || mime.startsWith("audio/") || file.matches(".*\\.(mp3|m4a|aac|wav|ogg|flac|opus)$")) return "audio";
        if ("image".equals(type) || mime.startsWith("image/") || file.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|svg)$")) return "image";
        if ("video".equals(type) || mime.startsWith("video/") || file.matches(".*\\.(mp4|mkv|webm|mov|avi|3gp|m4v)$")) return "video";
        return "file";
    }

    private String typeLabel(String type) {
        if ("video".equals(type)) return "فيديو";
        if ("audio".equals(type)) return "صوت";
        if ("image".equals(type)) return "صورة";
        return "ملف";
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + " ثانية";
        if (seconds < 3600) return (seconds / 60) + " دقيقة";
        return (seconds / 3600) + " س " + ((seconds % 3600) / 60) + " د";
    }

    private String formatDate(long millis) {
        if (millis <= 0) return "وقت غير معروف";
        return new java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault())
                .format(new java.util.Date(millis));
    }
}
