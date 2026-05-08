package com.focusguard.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    
    private PreferenceManager pref;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    
    private TextView tvAdminStatus, tvAccessibilityStatus, tvTimerRemaining;
    private Button btnEnableAdmin, btnDisableAdmin, btnEnableAccessibility, btnDisableAccessibility, btnStartFocus;
    private SwitchCompat swWhatsApp, swYouTube, swInstagram, swBlockAcc, swBlockAdmin;
    private EditText etHours, etMinutes, etPassword;
    private ProgressBar focusProgress;
    
    private CountDownTimer countDownTimer;
    private static final int REQ_ADMIN = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);

            pref = new PreferenceManager(this);
            dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            adminComponent = new ComponentName(this, AdminReceiver.class);

            initViews();
            setupListeners();
            syncUIWithState();
        } catch (Exception e) {
            Toast.makeText(this, "System Init Error", Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private void initViews() {
        tvAdminStatus    = findViewById(R.id.tvAdminStatus);
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus);
        tvTimerRemaining = findViewById(R.id.tvTimerRemaining);
        btnEnableAdmin   = findViewById(R.id.btnEnableAdmin);
        btnDisableAdmin  = findViewById(R.id.btnDisableAdmin);
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);
        btnDisableAccessibility = findViewById(R.id.btnDisableAccessibility);
        btnStartFocus    = findViewById(R.id.btnStartFocus);
        etHours          = findViewById(R.id.etFocusHours);
        etMinutes        = findViewById(R.id.etFocusMinutes);
        etPassword       = findViewById(R.id.etPasscode);
        focusProgress    = findViewById(R.id.focusProgress);
        swBlockAcc       = findViewById(R.id.switchBlockAccessibility);
        swBlockAdmin     = findViewById(R.id.switchBlockDeviceAdmin);

        setupAppRow(R.id.rowWhatsApp, "💬", "WhatsApp Updates", "Block channels & feeds");
        setupAppRow(R.id.rowYouTube, "▶️", "YouTube Shorts", "Stop scroll addiction");
        setupAppRow(R.id.rowInstagram, "📸", "Instagram Reels", "Master your time");

        swWhatsApp  = findViewById(R.id.rowWhatsApp).findViewById(R.id.itemSwitch);
        swYouTube   = findViewById(R.id.rowYouTube).findViewById(R.id.itemSwitch);
        swInstagram = findViewById(R.id.rowInstagram).findViewById(R.id.itemSwitch);

        if (pref != null && etPassword != null) {
            etPassword.setText(pref.getEmergencyPassword());
        }
    }

    private void setupAppRow(int rowId, String icon, String title, String desc) {
        View row = findViewById(rowId);
        ((TextView)row.findViewById(R.id.itemIcon)).setText(icon);
        ((TextView)row.findViewById(R.id.itemTitle)).setText(title);
        ((TextView)row.findViewById(R.id.itemDesc)).setText(desc);
    }

    private void setupListeners() {
        btnEnableAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Enable FocusGuard Service", Toast.LENGTH_LONG).show();
        });

        btnDisableAccessibility.setOnClickListener(v -> promptPassword(() -> {
            if (BlockerService.getInstance() != null) {
                BlockerService.getInstance().disableService();
                syncUIWithState();
            }
        }));

        btnEnableAdmin.setOnClickListener(v -> {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "System integrity protection.");
            startActivityForResult(intent, REQ_ADMIN);
        });

        btnDisableAdmin.setOnClickListener(v -> promptPassword(() -> {
            try {
                dpm.removeActiveAdmin(adminComponent);
                syncUIWithState();
                Toast.makeText(this, "System Unlocked", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { syncUIWithState(); }
        }));

        btnStartFocus.setOnClickListener(v -> startFocusSession());

        swWhatsApp.setOnCheckedChangeListener((b, checked) -> pref.setWhatsAppBlocked(checked));
        swYouTube.setOnCheckedChangeListener((b, checked) -> pref.setYouTubeBlocked(checked));
        swInstagram.setOnCheckedChangeListener((b, checked) -> pref.setInstagramBlocked(checked));
        swBlockAcc.setOnCheckedChangeListener((b, checked) -> pref.setAccessibilityProtected(checked));
        swBlockAdmin.setOnCheckedChangeListener((b, checked) -> pref.setDeviceAdminProtected(checked));
    }

    private void startFocusSession() {
        int h = getVal(etHours);
        int m = getVal(etMinutes);
        if (h == 0 && m == 0) return;

        long duration = (h * 3600000L) + (m * 60000L);
        pref.setTimerEndTime(System.currentTimeMillis() + duration);
        pref.setServiceActive(true);
        pref.setAccessibilityProtected(true);
        pref.setDeviceAdminProtected(true);
        
        syncUIWithState();
        Toast.makeText(this, "🚀 Deep Focus Initiated", Toast.LENGTH_SHORT).show();
    }

    private void promptPassword(Runnable onVerify) {
        if (pref.isTimerActive()) {
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            new android.app.AlertDialog.Builder(this)
                .setTitle("🛡️ CORE BYPASS")
                .setMessage("Authentication required.")
                .setView(input)
                .setPositiveButton("VERIFY", (dialog, which) -> {
                    if (input.getText().toString().equals(pref.getEmergencyPassword())) {
                        pref.setTimerEndTime(0);
                        if (countDownTimer != null) countDownTimer.cancel();
                        onVerify.run();
                        syncUIWithState();
                    } else {
                        Toast.makeText(this, "❌ AUTH FAILED", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("ABORT", null).show();
        } else {
            onVerify.run();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC);
        for (AccessibilityServiceInfo service : enabledServices) {
            if (service.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) return true;
        }
        return false;
    }

    private void syncUIWithState() {
        if (isFinishing() || tvAdminStatus == null) return;

        boolean adminOn   = dpm.isAdminActive(adminComponent);
        boolean serviceOn = isAccessibilityServiceEnabled();
        boolean timerOn   = pref.isTimerActive();

        // Sync Service UI
        if (serviceOn) {
            tvAccessibilityStatus.setText("CORE SERVICE: ACTIVE");
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.success_emerald));
            btnEnableAccessibility.setVisibility(View.GONE);
            btnDisableAccessibility.setVisibility(View.VISIBLE);
        } else {
            tvAccessibilityStatus.setText("CORE SERVICE: OFFLINE");
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.danger_rose));
            btnEnableAccessibility.setVisibility(View.VISIBLE);
            btnDisableAccessibility.setVisibility(View.GONE);
        }

        // Sync Admin UI
        if (adminOn) {
            tvAdminStatus.setText("CORE ADMIN: ACTIVE");
            tvAdminStatus.setTextColor(ContextCompat.getColor(this, R.color.success_emerald));
            btnEnableAdmin.setVisibility(View.GONE);
            btnDisableAdmin.setVisibility(View.VISIBLE);
        } else {
            tvAdminStatus.setText("CORE ADMIN: OFFLINE");
            tvAdminStatus.setTextColor(ContextCompat.getColor(this, R.color.danger_rose));
            btnEnableAdmin.setVisibility(View.VISIBLE);
            btnDisableAdmin.setVisibility(View.GONE);
        }

        swWhatsApp.setChecked(pref.isWhatsAppBlocked());
        swYouTube.setChecked(pref.isYouTubeBlocked());
        swInstagram.setChecked(pref.isInstagramBlocked());
        swBlockAcc.setChecked(pref.isAccessibilityProtected());
        swBlockAdmin.setChecked(pref.isDeviceAdminProtected());

        setInternalUIEnabled(!timerOn);

        if (timerOn) {
            long remaining = pref.getTimerEndTime() - System.currentTimeMillis();
            startCountdown(remaining);
        } else {
            tvTimerRemaining.setText("00:00:00");
            focusProgress.setProgress(0);
        }
    }

    private void setInternalUIEnabled(boolean enabled) {
        if (swWhatsApp == null) return;
        swWhatsApp.setEnabled(enabled);
        swYouTube.setEnabled(enabled);
        swInstagram.setEnabled(enabled);
        swBlockAcc.setEnabled(enabled);
        swBlockAdmin.setEnabled(enabled);
        etHours.setEnabled(enabled);
        etMinutes.setEnabled(enabled);
        btnStartFocus.setEnabled(enabled);
    }

    private void startCountdown(long ms) {
        if (countDownTimer != null) countDownTimer.cancel();
        final long totalTime = ms;
        countDownTimer = new CountDownTimer(ms, 1000) {
            public void onTick(long msRemaining) {
                if (isFinishing()) return;
                long h = msRemaining / 3600000;
                long m = (msRemaining % 3600000) / 60000;
                long s = (msRemaining % 60000) / 1000;
                tvTimerRemaining.setText(String.format("%02d:%02d:%02d", h, m, s));
                int progress = (int) (100 - (msRemaining * 100 / totalTime));
                focusProgress.setProgress(progress);
            }
            public void onFinish() {
                if (isFinishing()) return;
                tvTimerRemaining.setText("SESSION END");
                focusProgress.setProgress(100);
                syncUIWithState();
            }
        }.start();
    }

    private int getVal(EditText et) {
        if (et == null) return 0;
        String s = et.getText().toString();
        return (s.isEmpty()) ? 0 : Integer.parseInt(s);
    }

    @Override protected void onResume() { super.onResume(); syncUIWithState(); }
    @Override protected void onDestroy() { if (countDownTimer != null) { countDownTimer.cancel(); } super.onDestroy(); }
}
