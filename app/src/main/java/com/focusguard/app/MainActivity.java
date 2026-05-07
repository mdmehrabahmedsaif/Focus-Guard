package com.focusguard.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private SharedPreferences prefs;

    private TextView tvServiceStatus;
    private TextView tvAdminStatus;
    private Button btnEnableService;
    private Button btnDisableService;
    private Button btnEnableAdmin;
    private SwitchCompat switchWhatsApp;
    private SwitchCompat switchYouTube;
    private SwitchCompat switchInstagram;
    private SwitchCompat switchBlockAccessibility;

    private static final int REQUEST_ENABLE_ADMIN = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        // Views
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvAdminStatus = findViewById(R.id.tvAdminStatus);
        btnEnableService          = findViewById(R.id.btnEnableService);
        btnDisableService         = findViewById(R.id.btnDisableService);
        btnEnableAdmin            = findViewById(R.id.btnEnableAdmin);
        switchWhatsApp            = findViewById(R.id.switchWhatsApp);
        switchYouTube             = findViewById(R.id.switchYouTube);
        switchInstagram           = findViewById(R.id.switchInstagram);
        switchBlockAccessibility  = findViewById(R.id.switchBlockAccessibility);

        // Load saved settings
        switchWhatsApp.setChecked(prefs.getBoolean("block_whatsapp", true));
        switchYouTube.setChecked(prefs.getBoolean("block_youtube", true));
        switchInstagram.setChecked(prefs.getBoolean("block_instagram", true));
        switchBlockAccessibility.setChecked(prefs.getBoolean("block_accessibility", false));

        // Switch listeners
        switchWhatsApp.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("block_whatsapp", checked).apply());

        switchYouTube.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("block_youtube", checked).apply());

        switchInstagram.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("block_instagram", checked).apply());

        switchBlockAccessibility.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("block_accessibility", checked).apply();
            if (checked) {
                Toast.makeText(this,
                    "🚫 Accessibility protection ON — FocusGuard settings page is now blocked!",
                    Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                    "✅ Accessibility protection OFF",
                    Toast.LENGTH_SHORT).show();
            }
        });

        // Enable Accessibility Service
        btnEnableService.setOnClickListener(v -> {
            openAccessibilitySettings();
        });

        // Disable Accessibility Service (Authorized bypass)
        btnDisableService.setOnClickListener(v -> {
            // 1. Temporarily disable the protection so the user can enter the page
            prefs.edit().putBoolean("block_accessibility", false).apply();
            switchBlockAccessibility.setChecked(false);
            
            Toast.makeText(this, 
                "Protection disabled. You can now turn off the service.", 
                Toast.LENGTH_LONG).show();

            // 2. Open the settings
            openAccessibilitySettings();
        });

        // Enable Device Admin
        btnEnableAdmin.setOnClickListener(v -> {
            if (!dpm.isAdminActive(adminComponent)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Device Admin permission is required to protect FocusGuard from being uninstalled.");
                startActivityForResult(intent, REQUEST_ENABLE_ADMIN);
            } else {
                Toast.makeText(this, "✅ Device Admin is already active!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUI();
    }

    private void updateStatusUI() {
        if (isAccessibilityServiceEnabled()) {
            tvServiceStatus.setText("✅ Accessibility Service is ON");
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_on));
            btnEnableService.setVisibility(android.view.View.GONE);
            btnDisableService.setVisibility(android.view.View.VISIBLE);
        } else {
            tvServiceStatus.setText("❌ Accessibility Service is OFF");
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_off));
            btnEnableService.setVisibility(android.view.View.VISIBLE);
            btnDisableService.setVisibility(android.view.View.GONE);
        }

        if (dpm.isAdminActive(adminComponent)) {
            tvAdminStatus.setText("✅ Protection is ON");
            tvAdminStatus.setTextColor(ContextCompat.getColor(this, R.color.status_on));
            btnEnableAdmin.setText("✅ PROTECTION ON");
        } else {
            tvAdminStatus.setText("❌ Protection is OFF");
            tvAdminStatus.setTextColor(ContextCompat.getColor(this, R.color.status_off));
            btnEnableAdmin.setText("Enable Device Admin");
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + BlockerService.class.getName();
        String enabledServices = Settings.Secure.getString(
            getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        return enabledServices.contains(serviceName);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_ADMIN) {
            updateStatusUI();
        }
    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
        Toast.makeText(this,
            "Find FocusGuard Blocker in the list",
            Toast.LENGTH_LONG).show();
    }
}
