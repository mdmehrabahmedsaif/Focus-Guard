package com.focusguard.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class MainActivity extends AppCompatActivity {
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private SharedPreferences prefs;

    private TextView tvServiceStatus, tvAdminStatus, tvTimerRemaining;
    private Button btnEnableService, btnDisableService, btnEnableAdmin, btnDisableAdmin, btnStartFocus, btnSavePasscode;
    private SwitchCompat swWhatsApp, swYouTube, swInstagram, swBlockAccessibility, swBlockDeviceAdmin;
    private EditText etFocusHours, etFocusMinutes, etPasscode;
    
    private CountDownTimer countDownTimer;
    private static final int REQUEST_ENABLE_ADMIN = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);
        prefs = getSharedPreferences("FocusGuardSettings", MODE_PRIVATE);

        initViews();
        setupListeners();
        checkActiveTimer();
    }

    private void initViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvAdminStatus = findViewById(R.id.tvAdminStatus);
        tvTimerRemaining = findViewById(R.id.tvTimerRemaining);
        btnEnableService = findViewById(R.id.btnEnableService);
        btnDisableService = findViewById(R.id.btnDisableService);
        btnEnableAdmin = findViewById(R.id.btnEnableAdmin);
        btnDisableAdmin = findViewById(R.id.btnDisableAdmin);
        btnStartFocus = findViewById(R.id.btnStartFocus);
        btnSavePasscode = findViewById(R.id.btnSavePasscode);
        swWhatsApp = findViewById(R.id.switchWhatsApp);
        swYouTube = findViewById(R.id.switchYouTube);
        swInstagram = findViewById(R.id.switchInstagram);
        swBlockAccessibility = findViewById(R.id.switchBlockAccessibility);
        swBlockDeviceAdmin = findViewById(R.id.switchBlockDeviceAdmin);
        etFocusHours = findViewById(R.id.etFocusHours);
        etFocusMinutes = findViewById(R.id.etFocusMinutes);
        etPasscode = findViewById(R.id.etPasscode);

        // Load Password
        etPasscode.setText(prefs.getString("emergency_password", ""));
    }

    private void setupListeners() {
        btnEnableService.setOnClickListener(v -> {
            prefs.edit().putBoolean("is_service_active", true).apply();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        btnDisableService.setOnClickListener(v -> promptPasswordIfActive(() -> {
            BlockerService service = BlockerService.getInstance();
            if (service != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                service.disableService();
                updateStatusUI();
            } else {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));

        btnEnableAdmin.setOnClickListener(v -> {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Admin required to prevent uninstallation.");
            startActivityForResult(intent, REQUEST_ENABLE_ADMIN);
        });

        btnDisableAdmin.setOnClickListener(v -> promptPasswordIfActive(() -> {
            dpm.removeActiveAdmin(adminComponent);
            updateStatusUI();
        }));

        btnSavePasscode.setOnClickListener(v -> {
            String pass = etPasscode.getText().toString();
            if (pass.length() >= 4) {
                prefs.edit().putString("emergency_password", pass).apply();
                Toast.makeText(this, "Password Saved 🔐", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Minimum 4 characters required!", Toast.LENGTH_SHORT).show();
            }
        });

        btnStartFocus.setOnClickListener(v -> startFocusSession());

        swWhatsApp.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("block_whatsapp", checked).apply());
        swYouTube.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("block_youtube", checked).apply());
        swInstagram.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("block_instagram", checked).apply());
        swBlockAccessibility.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("block_accessibility", checked).apply());
        swBlockDeviceAdmin.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean("block_device_admin", checked).apply());
    }

    private void promptPasswordIfActive(Runnable onSuccess) {
        long endTime = prefs.getLong("timer_end_time", 0);
        if (System.currentTimeMillis() < endTime) {
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setHint("Enter Emergency Password");
            
            new android.app.AlertDialog.Builder(this)
                .setTitle("🚨 Security Bypass")
                .setMessage("A focus session is active. Enter your PASSWORD to unlock settings.")
                .setView(input)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    String pass = input.getText().toString();
                    if (pass.equals(prefs.getString("emergency_password", ""))) {
                        // End timer immediately
                        prefs.edit().putLong("timer_end_time", 0).apply();
                        if (countDownTimer != null) countDownTimer.cancel();
                        tvTimerRemaining.setVisibility(View.GONE);
                        lockInternalSettings(false);
                        onSuccess.run();
                        Toast.makeText(this, "🔓 Settings Unlocked", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "❌ Incorrect Password!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
        } else {
            onSuccess.run();
        }
    }

    private void startFocusSession() {
        int hours = getInt(etFocusHours);
        int minutes = getInt(etFocusMinutes);
        if (hours == 0 && minutes == 0) return;

        long duration = (hours * 3600000L) + (minutes * 60000L);
        long endTime = System.currentTimeMillis() + duration;
        prefs.edit().putLong("timer_end_time", endTime).apply();
        
        prefs.edit().putBoolean("is_service_active", true).putBoolean("block_accessibility", true).putBoolean("block_device_admin", true).apply();
        swBlockAccessibility.setChecked(true);
        swBlockDeviceAdmin.setChecked(true);
        
        lockInternalSettings(true);
        startTimer(duration);
        updateStatusUI();
        Toast.makeText(this, "🚀 Focus Mode Locked!", Toast.LENGTH_SHORT).show();
    }

    private void lockInternalSettings(boolean locked) {
        boolean enabled = !locked;
        swWhatsApp.setEnabled(enabled);
        swYouTube.setEnabled(enabled);
        swInstagram.setEnabled(enabled);
        swBlockAccessibility.setEnabled(enabled);
        swBlockDeviceAdmin.setEnabled(enabled);
        etFocusHours.setEnabled(enabled);
        etFocusMinutes.setEnabled(enabled);
        btnStartFocus.setEnabled(enabled);
        // Password field stays enabled so user can see what they set, 
        // but they can't start a NEW session if one is active.
    }

    private void startTimer(long duration) {
        if (countDownTimer != null) countDownTimer.cancel();
        tvTimerRemaining.setVisibility(View.VISIBLE);
        countDownTimer = new CountDownTimer(duration, 1000) {
            public void onTick(long ms) {
                long h = ms / 3600000;
                long m = (ms % 3600000) / 60000;
                long s = (ms % 60000) / 1000;
                tvTimerRemaining.setText(String.format("Time Remaining: %02d:%02d:%02d", h, m, s));
            }
            public void onFinish() {
                tvTimerRemaining.setText("Session Finished! 🎉");
                tvTimerRemaining.setTextColor(Color.parseColor("#4CAF50"));
                lockInternalSettings(false);
            }
        }.start();
    }

    private void checkActiveTimer() {
        long endTime = prefs.getLong("timer_end_time", 0);
        long diff = endTime - System.currentTimeMillis();
        if (diff > 0) {
            startTimer(diff);
            lockInternalSettings(true);
        } else {
            lockInternalSettings(false);
        }
    }

    private void updateStatusUI() {
        boolean isServiceOn = isAccessibilityServiceEnabled();
        boolean isAdminOn = dpm.isAdminActive(adminComponent);

        tvServiceStatus.setText(isServiceOn ? "✅ Accessibility Service is ON" : "❌ Accessibility Service is OFF");
        tvServiceStatus.setTextColor(isServiceOn ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
        btnEnableService.setVisibility(isServiceOn ? View.GONE : View.VISIBLE);
        btnDisableService.setVisibility(isServiceOn ? View.VISIBLE : View.GONE);

        tvAdminStatus.setText(isAdminOn ? "✅ Protection is ON" : "❌ Protection is OFF");
        tvAdminStatus.setTextColor(isAdminOn ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
        btnEnableAdmin.setVisibility(isAdminOn ? View.GONE : View.VISIBLE);
        btnDisableAdmin.setVisibility(isAdminOn ? View.VISIBLE : View.GONE);

        swWhatsApp.setChecked(prefs.getBoolean("block_whatsapp", true));
        swYouTube.setChecked(prefs.getBoolean("block_youtube", true));
        swInstagram.setChecked(prefs.getBoolean("block_instagram", true));
        swBlockAccessibility.setChecked(prefs.getBoolean("block_accessibility", false));
        swBlockDeviceAdmin.setChecked(prefs.getBoolean("block_device_admin", false));
    }

    private int getInt(EditText et) {
        String s = et.getText().toString();
        return TextUtils.isEmpty(s) ? 0 : Integer.parseInt(s);
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + BlockerService.class.getName();
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(service);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUI();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_ADMIN) updateStatusUI();
    }
}
