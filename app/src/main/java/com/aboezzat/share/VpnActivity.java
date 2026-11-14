package com.aboezzat.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.VpnService;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VpnActivity extends Activity {
    private static final int REQUEST_CONFIG = 7101;
    private static final int REQUEST_VPN_PERMISSION = 7102;
    private static final String PREFS = "abo_vpn_profiles";
    private static final String PROFILE_INDEX = "profiles_json";
    private static final String OLD_CONFIG_FILE = "abo_vpn.conf";
    private static final String APP_SELECTION = "selected_apps_json";

    private static GoBackend backend;
    private static Tunnel tunnel;
    private static volatile boolean connected;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, String> profiles = new LinkedHashMap<>();
    private TextView status;
    private TextView selectedLabel;
    private TextView appSelectionLabel;
    private final ArrayList<String> selectedApps = new ArrayList<>();
    private Button connectButton;
    private Button editButton;
    private String selectedId;
    private boolean importAsNew = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (backend == null) backend = new GoBackend(getApplicationContext());
        if (tunnel == null) {
            tunnel = new Tunnel() {
                @Override public String getName() { return "aboezzat"; }
                @Override public void onStateChange(State newState) {
                    connected = newState == State.UP;
                    runOnUiThread(() -> renderState());
                }
            };
        }
        loadProfiles();
        loadSelectedApps();
        migrateOldConfig();
        if (selectedId == null && !profiles.isEmpty()) selectedId = profiles.keySet().iterator().next();
        buildScreen();
        renderState();
    }

    private void loadSelectedApps() {
        selectedApps.clear();
        String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(APP_SELECTION, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                String pkg = array.optString(i, "");
                if (!pkg.isEmpty() && !selectedApps.contains(pkg)) selectedApps.add(pkg);
            }
        } catch (Exception ignored) { }
    }

    private void saveSelectedApps() {
        JSONArray array = new JSONArray();
        for (String pkg : selectedApps) array.put(pkg);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(APP_SELECTION, array.toString()).apply();
        renderAppSelection();
    }

    private void renderAppSelection() {
        if (appSelectionLabel == null) return;
        appSelectionLabel.setText(selectedApps.isEmpty()
                ? "لم يتم اختيار تطبيقات بعد"
                : "تم اختيار " + selectedApps.size() + " تطبيق — " + selectedApps.toString());
    }

    private static final class AppChoice {
        final String packageName;
        final String label;
        AppChoice(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private void chooseApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(launcher, 0);
        ArrayList<AppChoice> apps = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (ResolveInfo info : activities) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || !seen.add(pkg)) continue;
            CharSequence label = info.loadLabel(pm);
            apps.add(new AppChoice(pkg, label == null ? pkg : label.toString()));
        }
        Collections.sort(apps, Comparator.comparing(a -> a.label.toLowerCase(java.util.Locale.ROOT)));
        if (apps.isEmpty()) {
            Toast.makeText(this, "لم أجد تطبيقات ظاهرة في قائمة التطبيقات", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppChoice app = apps.get(i);
            labels[i] = app.label;
            checked[i] = selectedApps.contains(app.packageName);
        }
        HashSet<String> pending = new HashSet<>(selectedApps);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("حدد التطبيقات التي تستخدم VPN")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    String pkg = apps.get(which).packageName;
                    if (isChecked) pending.add(pkg); else pending.remove(pkg);
                })
                .setPositiveButton("حفظ الاختيار", (d, w) -> {
                    selectedApps.clear();
                    for (AppChoice app : apps) if (pending.contains(app.packageName)) selectedApps.add(app.packageName);
                    saveSelectedApps();
                })
                .setNegativeButton("إلغاء", null)
                .create();
        dialog.show();
    }

    private String configWithSelectedApps(File file) throws Exception {
        if (selectedApps.isEmpty()) throw new IllegalStateException("اختار تطبيقًا واحدًا على الأقل ليعمل VPN عليه.");
        String raw;
        try (FileInputStream in = new FileInputStream(file)) {
            raw = new String(readAll(in), StandardCharsets.UTF_8);
        }
        StringBuilder cleaned = new StringBuilder();
        String[] lines = raw.replace("\r", "").split("\n");
        boolean inInterface = false;
        boolean foundInterface = false;
        boolean inserted = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("(?i)^\\[Interface\\]$")) {
                inInterface = true;
                foundInterface = true;
                cleaned.append(line).append('\n');
                continue;
            }
            if (trimmed.matches("(?i)^\\[.+\\]$")) {
                if (inInterface && !inserted) {
                    appendIncludedApps(cleaned);
                    inserted = true;
                }
                inInterface = false;
            }
            if (inInterface && trimmed.matches("(?i)^(IncludedApplications|ExcludedApplications)\\s*=.*$")) continue;
            cleaned.append(line).append('\n');
        }
        if (!foundInterface) throw new IllegalArgumentException("ملف WireGuard لا يحتوي على [Interface].");
        if (!inserted) appendIncludedApps(cleaned);
        String result = cleaned.toString();
        Config.parse(new ByteArrayInputStream(result.getBytes(StandardCharsets.UTF_8)));
        return result;
    }

    private void appendIncludedApps(StringBuilder target) {
        target.append("IncludedApplications = ");
        for (int i = 0; i < selectedApps.size(); i++) {
            if (i > 0) target.append(", ");
            target.append(selectedApps.get(i));
        }
        target.append('\n');
    }

    private void loadProfiles() {
        profiles.clear();
        String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PROFILE_INDEX, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String id = item.optString("id", "");
                String name = item.optString("name", "VPN");
                if (!id.isEmpty() && profileFile(id).isFile()) profiles.put(id, name);
            }
            selectedId = getSharedPreferences(PREFS, MODE_PRIVATE).getString("selected_id", null);
            if (!profiles.containsKey(selectedId)) selectedId = null;
        } catch (Exception ignored) { }
    }

    private void saveProfiles() {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, String> entry : profiles.entrySet()) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", entry.getKey());
                item.put("name", entry.getValue());
                array.put(item);
            } catch (Exception ignored) { }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PROFILE_INDEX, array.toString())
                .putString("selected_id", selectedId == null ? "" : selectedId)
                .apply();
    }

    private void migrateOldConfig() {
        File old = new File(getFilesDir(), OLD_CONFIG_FILE);
        if (!old.isFile() || !profiles.isEmpty()) return;
        try (FileInputStream in = new FileInputStream(old)) {
            byte[] data = readAll(in);
            Config.parse(new ByteArrayInputStream(data));
            String id = UUID.randomUUID().toString();
            writeProfile(id, data);
            profiles.put(id, "إعداد VPN القديم");
            selectedId = id;
            saveProfiles();
            // Keep the old file until the migrated profile has been saved successfully.
        } catch (Exception e) {
            Toast.makeText(this, "تعذر ترحيل إعداد VPN القديم: " + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(7, 17, 35));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("AboEzzat VPN");
        title.setTextColor(Color.WHITE);
        title.setTextSize(27);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, dp(5));
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("اتصال WireGuard وإدارة السيرفرات والتطبيقات");
        subtitle.setTextColor(Color.rgb(160, 190, 220));
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(18));
        root.addView(subtitle, matchWrap());

        status = new TextView(this);
        status.setTextColor(Color.rgb(255, 210, 80));
        status.setTextSize(19);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(14), dp(22), dp(14), dp(22));
        status.setBackgroundColor(Color.rgb(17, 42, 70));
        root.addView(status, matchWrap());

        selectedLabel = new TextView(this);
        selectedLabel.setTextColor(Color.WHITE);
        selectedLabel.setTextSize(16);
        selectedLabel.setPadding(dp(4), dp(16), dp(4), dp(5));
        root.addView(selectedLabel, matchWrap());

        Button choose = makeButton("🌐  إدارة السيرفرات المحفوظة");
        choose.setOnClickListener(v -> chooseProfile());
        root.addView(choose, spaced());

        Button importButton = makeButton("＋  استيراد سيرفر WireGuard (.conf)");
        importButton.setOnClickListener(v -> { importAsNew = true; chooseConfig(); });
        root.addView(importButton, spaced());

        Button addManual = makeButton("إضافة سيرفر يدويًا");
        addManual.setOnClickListener(v -> showConfigEditor(null));
        root.addView(addManual, spaced());

        editButton = makeButton("تعديل السيرفر المحدد");
        editButton.setOnClickListener(v -> showConfigEditor(selectedId));
        root.addView(editButton, spaced());

        Button rename = makeButton("إعادة تسمية السيرفر");
        rename.setOnClickListener(v -> renameProfile());
        root.addView(rename, spaced());

        Button delete = makeButton("حذف السيرفر المحدد");
        delete.setOnClickListener(v -> deleteProfile());
        root.addView(delete, spaced());

        View gap = new View(this);
        root.addView(gap, new LinearLayout.LayoutParams(1, dp(12)));

        TextView appsTitle = new TextView(this);
        appsTitle.setText("التطبيقات المسموح لها باستخدام VPN");
        appsTitle.setTextColor(Color.WHITE);
        appsTitle.setTextSize(17);
        appsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        appsTitle.setPadding(dp(4), dp(8), dp(4), dp(4));
        root.addView(appsTitle, matchWrap());

        appSelectionLabel = new TextView(this);
        appSelectionLabel.setTextColor(Color.rgb(170, 205, 230));
        appSelectionLabel.setTextSize(14);
        appSelectionLabel.setPadding(dp(4), dp(4), dp(4), dp(8));
        root.addView(appSelectionLabel, matchWrap());

        Button appsButton = makeButton("اختيار التطبيقات");
        appsButton.setOnClickListener(v -> chooseApps());
        root.addView(appsButton, spaced());

        TextView hint = new TextView(this);
        hint.setText("التطبيقات التي تختارها فقط ستمر عبر نفق VPN. باقي تطبيقات الهاتف ستستخدم اتصال الإنترنت العادي. يلزم إيقاف VPN ثم إعادة تشغيله بعد تغيير القائمة.");
        hint.setTextColor(Color.rgb(185, 205, 225));
        hint.setTextSize(13);
        hint.setPadding(dp(4), dp(10), dp(4), dp(14));
        root.addView(hint, matchWrap());

        connectButton = makeButton("تشغيل VPN");
        connectButton.setTextSize(18);
        connectButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(18, 130, 92)));
        root.addView(connectButton, spaced());
        connectButton.setOnClickListener(v -> toggleVpn());

        Button providerButton = makeButton("الحصول على ملف Proton WireGuard");
        providerButton.setOnClickListener(v -> openProtonConfigPage());
        root.addView(providerButton, spaced());

        Button systemSettings = makeButton("إعدادات VPN في Android");
        systemSettings.setOnClickListener(v -> openVpnSystemSettings());
        root.addView(systemSettings, spaced());

        Button close = makeButton("رجوع");
        close.setOnClickListener(v -> finish());
        root.addView(close, spaced());
        setContentView(scroll);
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        return lp;
    }

    private Button makeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(24, 75, 120)));
        return b;
    }

    private void chooseProfile() {
        if (profiles.isEmpty()) {
            Toast.makeText(this, "لا توجد إعدادات محفوظة بعد", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = profiles.values().toArray(new String[0]);
        String[] ids = profiles.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("اختر إعداد VPN")
                .setSingleChoiceItems(names, Math.max(0, java.util.Arrays.asList(ids).indexOf(selectedId)), (dialog, which) -> {
                    if (connected && !ids[which].equals(selectedId)) {
                        Toast.makeText(this, "أوقف VPN قبل تغيير الإعداد المحدد", Toast.LENGTH_LONG).show();
                        return;
                    }
                    selectedId = ids[which];
                    saveProfiles();
                    renderState();
                    dialog.dismiss();
                }).setNegativeButton("إلغاء", null).show();
    }

    private void renameProfile() {
        if (!hasSelectedProfile()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(profiles.get(selectedId));
        new AlertDialog.Builder(this).setTitle("اسم الإعداد").setView(input)
                .setPositiveButton("حفظ", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        profiles.put(selectedId, name);
                        saveProfiles();
                        renderState();
                    }
                }).setNegativeButton("إلغاء", null).show();
    }

    private void deleteProfile() {
        if (!hasSelectedProfile()) return;
        if (connected) {
            Toast.makeText(this, "أوقف VPN قبل حذف الإعداد", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("حذف الإعداد")
                .setMessage("هل تريد حذف " + profiles.get(selectedId) + " من هذا الجهاز؟")
                .setPositiveButton("حذف", (d, w) -> {
                    profileFile(selectedId).delete();
                    profiles.remove(selectedId);
                    selectedId = profiles.isEmpty() ? null : profiles.keySet().iterator().next();
                    saveProfiles();
                    renderState();
                }).setNegativeButton("إلغاء", null).show();
    }

    private void showConfigEditor(String id) {
        if (connected) {
            Toast.makeText(this, "أوقف VPN قبل تعديل الإعداد", Toast.LENGTH_LONG).show();
            return;
        }
        final boolean editing = id != null && profiles.containsKey(id);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("اسم الإعداد مثل Proton US");
        if (editing) name.setText(profiles.get(id));

        EditText configText = new EditText(this);
        configText.setTextColor(Color.WHITE);
        configText.setHintTextColor(Color.LTGRAY);
        configText.setHint("[Interface]\nPrivateKey = ...\nAddress = ...\nDNS = ...\n\n[Peer]\nPublicKey = ...\nEndpoint = host:port\nAllowedIPs = 0.0.0.0/0, ::/0");
        configText.setTextSize(12);
        configText.setGravity(Gravity.TOP | Gravity.START);
        configText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        configText.setMinLines(12);
        configText.setPadding(dp(10), dp(10), dp(10), dp(10));
        configText.setBackgroundColor(Color.rgb(17, 39, 70));
        if (editing) {
            try (FileInputStream in = new FileInputStream(profileFile(id))) {
                configText.setText(new String(readAll(in), StandardCharsets.UTF_8));
            } catch (Exception e) {
                showError("تعذر قراءة الإعداد: " + safeMessage(e));
                return;
            }
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(4), dp(8), dp(4));
        panel.addView(name, matchWrap());
        panel.addView(configText, matchWrap());

        new AlertDialog.Builder(this)
                .setTitle(editing ? "تعديل إعداد WireGuard" : "إضافة إعداد WireGuard")
                .setMessage("أدخل ملف WireGuard كاملًا. احتفظ بالمفتاح الخاص سرًا.")
                .setView(panel)
                .setPositiveButton("حفظ", (d, w) -> {
                    String profileName = name.getText().toString().trim();
                    String raw = configText.getText().toString().trim();
                    if (profileName.isEmpty()) profileName = "WireGuard " + (profiles.size() + 1);
                    saveConfigText(editing ? id : null, profileName, raw);
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private void saveConfigText(String id, String name, String raw) {
        try {
            if (raw.isEmpty()) throw new IllegalArgumentException("اكتب إعداد WireGuard أولاً");
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            Config.parse(new ByteArrayInputStream(bytes));
            String targetId = id == null ? UUID.randomUUID().toString() : id;
            writeProfile(targetId, bytes);
            profiles.put(targetId, name);
            selectedId = targetId;
            saveProfiles();
            renderState();
            Toast.makeText(this, "تم حفظ إعداد WireGuard محليًا", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            showError("الإعداد غير صالح أو تعذر حفظه: " + safeMessage(e));
        }
    }

    private void chooseConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CONFIG);
    }

    private void openProtonConfigPage() {
        new AlertDialog.Builder(this)
                .setTitle("إنشاء ملف Proton VPN")
                .setMessage("افتح موقع Proton وسجّل الدخول، ثم Downloads ثم WireGuard configuration، واختر الخادم ونزّل ملف .conf. بعد الرجوع استورده هنا. لا تدخل كلمة مرور حساب Proton داخل التطبيق.")
                .setPositiveButton("فتح Proton", (d, w) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://account.protonvpn.com/downloads"))); }
                    catch (Exception e) { showError("تعذر فتح Proton: " + safeMessage(e)); }
                }).setNegativeButton("إلغاء", null).show();
    }

    private void openVpnSystemSettings() {
        try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
        catch (Exception e) { showError("تعذر فتح إعدادات VPN: " + safeMessage(e)); }
    }

    private void toggleVpn() {
        if (connected) {
            executor.execute(() -> {
                try {
                    backend.setState(tunnel, Tunnel.State.DOWN, null);
                    connected = false;
                    runOnUiThread(() -> { renderState(); Toast.makeText(this, "تم إيقاف VPN", Toast.LENGTH_SHORT).show(); });
                } catch (Exception e) { showError("تعذر إيقاف VPN: " + safeMessage(e)); }
            });
            return;
        }
        if (!hasSelectedProfile()) {
            new AlertDialog.Builder(this).setTitle("إعداد VPN مطلوب")
                    .setMessage("استورد ملف .conf أو أضف إعداد WireGuard يدويًا أولاً.")
                    .setPositiveButton("إضافة يدويًا", (d, w) -> showConfigEditor(null))
                    .setNeutralButton("استيراد ملف", (d, w) -> chooseConfig())
                    .setNegativeButton("إلغاء", null).show();
            return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, REQUEST_VPN_PERMISSION);
        else startConnection();
    }

    private void startConnection() {
        if (!hasSelectedProfile()) return;
        final String connectingId = selectedId;
        connectButton.setEnabled(false);
        status.setText("جارٍ إنشاء اتصال VPN...");
        executor.execute(() -> {
            try {
                String scopedConfig = configWithSelectedApps(profileFile(connectingId));
                Config config = Config.parse(new ByteArrayInputStream(scopedConfig.getBytes(StandardCharsets.UTF_8)));
                backend.setState(tunnel, Tunnel.State.UP, config);
                connected = true;
                runOnUiThread(() -> {
                    renderState();
                    Toast.makeText(this, "تم تشغيل نفق WireGuard", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                connected = false;
                showError("فشل الاتصال: " + safeMessage(e));
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQUEST_CONFIG && data != null && data.getData() != null) {
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                if (in == null) throw new java.io.IOException("تعذر فتح الملف");
                byte[] bytes = readAll(in);
                Config.parse(new ByteArrayInputStream(bytes));
                String display = data.getData().getLastPathSegment();
                if (display == null || display.trim().isEmpty()) display = "WireGuard " + (profiles.size() + 1);
                final String suggested = display.replaceAll("(?i)\\.conf$", "");
                EditText name = new EditText(this);
                name.setSingleLine(true);
                name.setText(suggested);
                new AlertDialog.Builder(this).setTitle("اسم الإعداد المستورد").setView(name)
                        .setPositiveButton("حفظ", (d, w) -> {
                            String n = name.getText().toString().trim();
                            if (n.isEmpty()) n = "WireGuard " + (profiles.size() + 1);
                            String id = UUID.randomUUID().toString();
                            try {
                                writeProfile(id, bytes);
                                profiles.put(id, n);
                                selectedId = id;
                                saveProfiles();
                                renderState();
                                Toast.makeText(this, "تم استيراد الإعداد وحفظه محليًا", Toast.LENGTH_LONG).show();
                            } catch (Exception e) { showError("تعذر حفظ الإعداد: " + safeMessage(e)); }
                        }).setNegativeButton("إلغاء", null).show();
            } catch (Exception e) { showError("ملف WireGuard غير صالح أو تعذر قراءته: " + safeMessage(e)); }
        } else if (requestCode == REQUEST_VPN_PERMISSION) {
            startConnection();
        }
    }

    private boolean hasSelectedProfile() {
        return selectedId != null && profiles.containsKey(selectedId) && profileFile(selectedId).isFile();
    }

    private File profileFile(String id) {
        File dir = new File(getFilesDir(), "vpn_profiles");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, id + ".conf");
    }

    private void writeProfile(String id, byte[] bytes) throws Exception {
        File target = profileFile(id);
        File temp = new File(target.getParentFile(), id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            try (FileOutputStream out = new FileOutputStream(target)) { out.write(bytes); }
            temp.delete();
        }
    }

    private byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) renderState();
    }

    private void renderState() {
        if (status == null || connectButton == null) return;
        try { connected = backend != null && tunnel != null && backend.getState(tunnel) == Tunnel.State.UP; }
        catch (Exception ignored) { }
        status.setText(connected ? "● VPN туннель включён" : "○ VPN не подключён");
        status.setTextColor(connected ? Color.rgb(74, 222, 128) : Color.rgb(255, 210, 80));
        connectButton.setText(connected ? "إيقاف VPN" : "تشغيل VPN");
        connectButton.setEnabled(true);
        selectedLabel.setText(hasSelectedProfile() ? "السيرفر المحدد: " + profiles.get(selectedId) : "لا يوجد سيرفر محفوظ — استورد ملف WireGuard للبدء");
        renderAppSelection();
        editButton.setEnabled(hasSelectedProfile() && !connected);
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            renderState();
            new AlertDialog.Builder(this).setTitle("مشكلة في VPN")
                    .setMessage(message).setPositiveButton("حسنًا", null).show();
        });
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
