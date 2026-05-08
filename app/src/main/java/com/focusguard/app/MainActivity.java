package com.focusguard.app;

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
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class MainActivity extends AppCompatActivity {
    
    private PreferenceManager pref;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    
    private TextView tvServiceStatus, tvAdminStatus, tvTimerRemaining;
    private Button btnEnableService, btnDisableService, btnEnableAdmin, btnDisableAdmin, btnStartFocus, btnSavePass;
    private SwitchCompat swWhatsApp, swYouTube, swInstagram, swBlockAcc, swBlockAdmin;
    private EditText etHours, etMinutes, etPassword;
    
    private CountDownTimer countDownTimer;
    private static final int REQ_ADMIN = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pref = new PreferenceManager(this);
        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);

        initViews();
        setupListeners();
        syncUIWithState();
    }

    private void initViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvAdminStatus   = findViewById(R.id.tvAdminStatus);
        tvTimerRemaining = findViewById(R.id.tvTimerRemaining);
        btnEnableService = findViewById(R.id.btnEnableService);
        btnDisableService = findViewById(R.id.btnDisableService);
        btnEnableAdmin   = findViewById(R.id.btnEnableAdmin);
        btnDisableAdmin  = findViewById(R.id.btnDisableAdmin);
        btnStartFocus    = findViewById(R.id.btnStartFocus);
        btnSavePass      = findViewById(R.id.btnSavePasscode);
        swWhatsApp       = findViewById(R.id.switchWhatsApp);
        swYouTube        = findViewById(R.id.switchYouTube);
        swInstagram      = findViewById(R.id.switchInstagram);
        swBlockAcc       = findViewById(R.id.switchBlockAccessibility);
        swBlockAdmin     = findViewById(R.id.switchBlockDeviceAdmin);
        etHours          = findViewById(R.id.etFocusHours);
        etMinutes        = findViewById(R.id.etFocusMinutes);
        etPassword       = findViewById(R.id.etPasscode);

        // Load saved password
        etPassword.setText(pref.getEmergencyPassword());
    }

    private void setupListeners() {
        btnEnableService.setOnClickListener(v -> {
            pref.setServiceActive(true);
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        btnDisableService.setOnClickListener(v -> promptPassword(() -> {
            BlockerService service = BlockerService.getInstance();
            if (service != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                service.disableService();
                
                // Immediate UI feedback
                tvServiceStatus.setText("❌ Blocker is Inactive");
                tvServiceStatus.setTextColor(getResources().getColor(R.color.danger_red));
                btnEnableService.setVisibility(View.VISIBLE);
                btnDisableService.setVisibility(View.GONE);
            } else {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
            v.postDelayed(this::syncUIWithState, 800);
        }));

        btnEnableAdmin.setOnClickListener(v -> {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Admin required for anti-uninstall protection.");
            startActivityForResult(intent, REQ_ADMIN);
        });

        btnDisableAdmin.setOnClickListener(v -> promptPassword(() -> {
            try {
                // 1. PERFORM REMOVAL
                dpm.removeActiveAdmin(adminComponent);
                
                // 2. OPTIMISTIC UI: Match new design
                tvAdminStatus.setText("Device Admin: OFF");
                tvAdminStatus.setTextColor(getResources().getColor(R.color.danger_red));
                btnEnableAdmin.setVisibility(View.VISIBLE);
                btnDisableAdmin.setVisibility(View.GONE);
                pref.setDeviceAdminProtected(false);
                
                // 3. Delayed sync
                v.postDelayed(this::syncUIWithState, 800);
                
                Toast.makeText(this, "🛡️ Protection Disabled", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                syncUIWithState();
            }
        }));

        btnSavePass.setOnClickListener(v -> {
            String pass = etPassword.getText().toString();
            if (pass.length() >= 4) {
                pref.setEmergencyPassword(pass);
                Toast.makeText(this, "Password Secured 🔐", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Enter at least 4 characters", Toast.LENGTH_SHORT).show();
            }
        });

        btnStartFocus.setOnClickListener(v -> startFocusSession());

        // Switches
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
        Toast.makeText(this, "🚀 Focus Mode Locked!", Toast.LENGTH_SHORT).show();
    }

    private void promptPassword(Runnable onVerify) {
        if (pref.isTimerActive()) {
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            new android.app.AlertDialog.Builder(this)
                .setTitle("🚨 Security Bypass")
                .setMessage("Timer is active. Enter password to unlock.")
                .setView(input)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    if (input.getText().toString().equals(pref.getEmergencyPassword())) {
                        pref.setTimerEndTime(0);
                        if (countDownTimer != null) countDownTimer.cancel();
                        onVerify.run();
                        syncUIWithState();
                    } else {
                        Toast.makeText(this, "❌ Incorrect Password", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
        } else {
            onVerify.run();
        }
    }

    private void syncUIWithState() {
        if (isFinishing()) return;

        boolean serviceOn = isAccessibilityEnabled();
        boolean adminOn   = dpm.isAdminActive(adminComponent);
        boolean timerOn   = pref.isTimerActive();

        // 1. Accessibility Service Status
        if (serviceOn) {
            tvServiceStatus.setText("✅ Blocker is Active");
            tvServiceStatus.setTextColor(getResources().getColor(R.color.success_green));
            btnEnableService.setVisibility(View.GONE);
            btnDisableService.setVisibility(View.VISIBLE);
        } else {
            tvServiceStatus.setText("❌ Blocker is Inactive");
            tvServiceStatus.setTextColor(getResources().getColor(R.color.danger_red));
            btnEnableService.setVisibility(View.VISIBLE);
            btnDisableService.setVisibility(View.GONE);
        }

        // 2. Admin Protection Status
        if (adminOn) {
            tvAdminStatus.setText("Device Admin: ON");
            tvAdminStatus.setTextColor(getResources().getColor(R.color.success_green));
            btnEnableAdmin.setVisibility(View.GONE);
            btnDisableAdmin.setVisibility(View.VISIBLE);
        } else {
            tvAdminStatus.setText("Device Admin: OFF");
            tvAdminStatus.setTextColor(getResources().getColor(R.color.danger_red));
            btnEnableAdmin.setVisibility(View.VISIBLE);
            btnDisableAdmin.setVisibility(View.GONE);
        }

        // 3. Switches State
        swWhatsApp.setChecked(pref.isWhatsAppBlocked());
        swYouTube.setChecked(pref.isYouTubeBlocked());
        swInstagram.setChecked(pref.isInstagramBlocked());
        swBlockAcc.setChecked(pref.isAccessibilityProtected());
        swBlockAdmin.setChecked(pref.isDeviceAdminProtected());

        // 4. Lock internal settings if timer active
        setInternalUIEnabled(!timerOn);

        // 5. Timer Display
        if (timerOn) {
            long remaining = pref.getTimerEndTime() - System.currentTimeMillis();
            startCountdown(remaining);
            tvTimerRemaining.setTextColor(Color.parseColor("#38BDF8")); // Bright focus blue
        } else {
            tvTimerRemaining.setText("READY TO FOCUS");
            tvTimerRemaining.setTextColor(Color.parseColor("#475569")); // Muted slate
        }
    }

    private void setInternalUIEnabled(boolean enabled) {
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
        tvTimerRemaining.setVisibility(View.VISIBLE);
        countDownTimer = new CountDownTimer(ms, 1000) {
            public void onTick(long msRemaining) {
                if (isFinishing()) return;
                long h = msRemaining / 3600000;
                long m = (msRemaining % 3600000) / 60000;
                long s = (msRemaining % 60000) / 1000;
                tvTimerRemaining.setText(String.format("Locked for: %02d:%02d:%02d", h, m, s));
            }
            public void onFinish() {
                if (isFinishing()) return;
                tvTimerRemaining.setText("Focus Finished! 🎉");
                syncUIWithState();
            }
        }.start();
    }

    private int getVal(EditText et) {
        String s = et.getText().toString();
        return (s.isEmpty()) ? 0 : Integer.parseInt(s);
    }

    private boolean isAccessibilityEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/" + BlockerService.class.getName());
    }

    @Override protected void onResume() { super.onResume(); syncUIWithState(); }
    @Override protected void onDestroy() { if (countDownTimer != null) countDownTimer.cancel(); super.onDestroy(); }
    @Override protected void onActivityResult(int i, int j, Intent d) { super.onActivityResult(i, j, d); syncUIWithState(); }
}
