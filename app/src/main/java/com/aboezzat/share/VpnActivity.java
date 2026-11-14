package com.aboezzat.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.net.Uri;
import android.provider.Settings;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VpnActivity extends Activity {
    private static final int REQUEST_CONFIG = 7101;
    private static final int REQUEST_VPN_PERMISSION = 7102;
    private static final String CONFIG_FILE = "abo_vpn.conf";

    private static GoBackend backend;
    private static Tunnel tunnel;
    private static volatile boolean connected;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button connectButton;
    private Button importButton;

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
        buildScreen();
        renderState();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(dp(22), dp(26), dp(22), dp(22));
        root.setBackgroundColor(Color.rgb(5, 17, 39));

        TextView title = new TextView(this);
        title.setText("VPN داخل AboEzzat Cast");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, dp(18));
        root.addView(title, matchWrap());

        status = new TextView(this);
        status.setTextColor(Color.rgb(255, 210, 80));
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(18), dp(12), dp(18));
        status.setBackgroundColor(Color.rgb(17, 39, 70));
        root.addView(status, matchWrap());

        TextView help = new TextView(this);
        help.setText("يمكنك إنشاء ملف WireGuard مجاني من Proton VPN بعد إنشاء حساب مجاني. سجّل الدخول في موقع Proton الرسمي، ثم Downloads ثم WireGuard configuration، واختر الخادم المجاني ونزّل ملف .conf. لا تدخل كلمة مرور حسابك داخل AboEzzat Cast.");
        help.setTextColor(Color.rgb(195, 211, 232));
        help.setTextSize(14);
        help.setPadding(dp(4), dp(18), dp(4), dp(18));
        root.addView(help, matchWrap());

        Button providerButton = makeButton("فتح موقع Proton لإنشاء ملف VPN");
        providerButton.setOnClickListener(v -> openProtonConfigPage());
        root.addView(providerButton, matchWrap());

        importButton = makeButton("استيراد ملف VPN (.conf)");
        importButton.setOnClickListener(v -> chooseConfig());
        root.addView(importButton, matchWrap());

        Button leakProtectionButton = makeButton("إعداد منع الاتصال بدون VPN");
        leakProtectionButton.setOnClickListener(v -> openVpnSystemSettings());
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(10);
        root.addView(leakProtectionButton, lp);

        TextView scope = new TextView(this);
        scope.setText("تنبيه: محرك WireGuard الحالي ينشئ نفق Android على مستوى الجهاز، وليس المتصفح وحده. ويمكنك من إعدادات Android تفعيل VPN دائمًا ومنع الاتصالات بدونه إذا كان جهازك يدعم ذلك.");
        scope.setTextColor(Color.rgb(255, 196, 120));
        scope.setTextSize(13);
        scope.setPadding(dp(4), dp(16), dp(4), dp(12));
        root.addView(scope, matchWrap());

        connectButton = makeButton("تشغيل VPN");
        connectButton.setOnClickListener(v -> toggleVpn());
        LinearLayout.LayoutParams cp = matchWrap();
        cp.topMargin = dp(12);
        root.addView(connectButton, cp);

        Button close = makeButton("رجوع");
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bp = matchWrap();
        bp.topMargin = dp(12);
        root.addView(close, bp);

        setContentView(root);
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

    private void openProtonConfigPage() {
        new AlertDialog.Builder(this)
                .setTitle("إنشاء ملف Proton VPN مجاني")
                .setMessage("1) افتح موقع Proton الرسمي وسجّل الدخول أو أنشئ حسابًا مجانيًا.\\n2) افتح Downloads ثم WireGuard configuration.\\n3) اختر خادمًا متاحًا للخطة المجانية واضغط Create ثم Download.\\n4) ارجع إلى AboEzzat Cast واستورد ملف .conf.\\n\\nلن نطلب منك كلمة المرور أو نخزنها. الخطة المجانية لا تضمن اختيار أي دولة بعينها.")
                .setPositiveButton("فتح موقع Proton", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://account.protonvpn.com/downloads")));
                    } catch (Exception e) {
                        showError("تعذر فتح موقع Proton: " + safeMessage(e));
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void openVpnSystemSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (Exception e) {
            showError("تعذر فتح إعدادات VPN في Android: " + safeMessage(e));
        }
    }

    private void chooseConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CONFIG);
    }

    private void toggleVpn() {
        if (connected) {
            executor.execute(() -> {
                try {
                    backend.setState(tunnel, Tunnel.State.DOWN, null);
                    connected = false;
                    runOnUiThread(() -> {
                        renderState();
                        Toast.makeText(this, "تم إيقاف VPN", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    showError("تعذر إيقاف VPN: " + safeMessage(e));
                }
            });
            return;
        }

        File configFile = new File(getFilesDir(), CONFIG_FILE);
        if (!configFile.isFile()) {
            new AlertDialog.Builder(this)
                    .setTitle("ملف VPN مطلوب")
                    .setMessage("استورد ملف WireGuard بصيغة .conf أولاً. يمكنك إنشاء الملف من حساب مزود VPN يدعم WireGuard.")
                    .setPositiveButton("استيراد ملف", (d, w) -> chooseConfig())
                    .setNegativeButton("إغلاق", null)
                    .show();
            return;
        }

        Intent permission = VpnService.prepare(this);
        if (permission != null) {
            startActivityForResult(permission, REQUEST_VPN_PERMISSION);
        } else {
            startConnection();
        }
    }

    private void startConnection() {
        connectButton.setEnabled(false);
        status.setText("جارٍ إنشاء اتصال VPN...");
        executor.execute(() -> {
            try (FileInputStream in = new FileInputStream(new File(getFilesDir(), CONFIG_FILE))) {
                Config config = Config.parse(in);
                backend.setState(tunnel, Tunnel.State.UP, config);
                connected = true;
                runOnUiThread(() -> {
                    renderState();
                    Toast.makeText(this, "تم تشغيل VPN", Toast.LENGTH_SHORT).show();
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
            try (java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                 FileOutputStream out = new FileOutputStream(new File(getFilesDir(), CONFIG_FILE))) {
                if (in == null) throw new java.io.IOException("تعذر فتح الملف");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                Toast.makeText(this, "تم استيراد ملف VPN وحفظه محليًا", Toast.LENGTH_LONG).show();
                renderState();
            } catch (Exception e) {
                showError("تعذر استيراد الملف: " + safeMessage(e));
            }
        } else if (requestCode == REQUEST_VPN_PERMISSION) {
            startConnection();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) renderState();
    }

    private void renderState() {
        boolean hasConfig = new File(getFilesDir(), CONFIG_FILE).isFile();
        try {
            connected = backend != null && tunnel != null && backend.getState(tunnel) == Tunnel.State.UP;
        } catch (Exception ignored) {
            // Keep the last known state if the backend is temporarily unavailable.
        }
        status.setText(connected ? "● VPN متصل" : "○ VPN غير متصل");
        status.setTextColor(connected ? Color.rgb(74, 222, 128) : Color.rgb(255, 210, 80));
        connectButton.setText(connected ? "إيقاف VPN" : "تشغيل VPN");
        connectButton.setEnabled(true);
        importButton.setText(hasConfig ? "استبدال ملف VPN (.conf)" : "استيراد ملف VPN (.conf)");
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            renderState();
            new AlertDialog.Builder(this)
                    .setTitle("مشكلة في VPN")
                    .setMessage(message)
                    .setPositiveButton("حسنًا", null)
                    .show();
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
