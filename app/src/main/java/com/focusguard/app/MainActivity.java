package com.focusguard.app;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "focusguard_prefs";
    private static final String KEY_BLOCKING_ENABLED = "blocking_enabled";
    private static final int REQUEST_CODE_ENABLE_ADMIN = 1001;

    // UI Elements
    private LinearLayout accessibilityStatusBadge;
    private View accessibilityStatusDot;
    private TextView accessibilityStatusText;
    private TextView btnOpenAccessibility;

    private Switch switchBlocking;
    private TextView blockingStatusText;

    private LinearLayout adminStatusBadge;
    private View adminStatusDot;
    private TextView adminStatusText;
    private TextView btnToggleAdmin;

    private SharedPreferences prefs;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, FocusGuardDeviceAdmin.class);

        initViews();
        setupListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAllStatuses();
    }

    private void initViews() {
        // Accessibility section
        accessibilityStatusBadge = findViewById(R.id.accessibility_status_badge);
        accessibilityStatusDot = findViewById(R.id.accessibility_status_dot);
        accessibilityStatusText = findViewById(R.id.accessibility_status_text);
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility);

        // Blocking section
        switchBlocking = findViewById(R.id.switch_blocking);
        blockingStatusText = findViewById(R.id.blocking_status_text);

        // Device Admin section
        adminStatusBadge = findViewById(R.id.admin_status_badge);
        adminStatusDot = findViewById(R.id.admin_status_dot);
        adminStatusText = findViewById(R.id.admin_status_text);
        btnToggleAdmin = findViewById(R.id.btn_toggle_admin);
    }

    private void setupListeners() {
        // Open Accessibility Settings
        btnOpenAccessibility.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            }
        });

        // Blocking toggle
        boolean blockingEnabled = prefs.getBoolean(KEY_BLOCKING_ENABLED, true);
        switchBlocking.setChecked(blockingEnabled);

        switchBlocking.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, isChecked).apply();
            updateBlockingStatus(isChecked);
        });

        // Device Admin toggle
        btnToggleAdmin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isDeviceAdminActive()) {
                    // Disable Device Admin
                    devicePolicyManager.removeActiveAdmin(adminComponent);
                    refreshAdminStatus();
                } else {
                    // Enable Device Admin
                    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            getString(R.string.device_admin_explanation));
                    startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_ENABLE_ADMIN) {
            refreshAdminStatus();
        }
    }

    private void refreshAllStatuses() {
        refreshAccessibilityStatus();
        updateBlockingStatus(prefs.getBoolean(KEY_BLOCKING_ENABLED, true));
        refreshAdminStatus();
    }

    private void refreshAccessibilityStatus() {
        boolean isEnabled = isAccessibilityServiceEnabled();

        if (isEnabled) {
            accessibilityStatusDot.setBackgroundResource(R.drawable.shape_circle_green);
            accessibilityStatusText.setText(R.string.status_active);
            accessibilityStatusText.setTextColor(0xFF10B981);
            accessibilityStatusBadge.setBackgroundResource(R.drawable.shape_status_badge_active);
            btnOpenAccessibility.setText("✓ Accessibility Enabled");
            btnOpenAccessibility.setTextColor(0xFF10B981);
            btnOpenAccessibility.setBackgroundResource(R.drawable.shape_button_outline_green);
        } else {
            accessibilityStatusDot.setBackgroundResource(R.drawable.shape_circle_red);
            accessibilityStatusText.setText(R.string.status_inactive);
            accessibilityStatusText.setTextColor(0xFFF43F5E);
            accessibilityStatusBadge.setBackgroundResource(R.drawable.shape_status_badge_inactive);
            btnOpenAccessibility.setText(R.string.btn_open_accessibility);
            btnOpenAccessibility.setTextColor(0xFF38BDF8);
            btnOpenAccessibility.setBackgroundResource(R.drawable.shape_button_outline_blue);
        }
    }

    private void updateBlockingStatus(boolean isEnabled) {
        if (isEnabled) {
            blockingStatusText.setText(R.string.blocking_enabled);
            blockingStatusText.setTextColor(0xFF10B981);
        } else {
            blockingStatusText.setText(R.string.blocking_disabled);
            blockingStatusText.setTextColor(0xFFF43F5E);
        }
    }

    private void refreshAdminStatus() {
        boolean isActive = isDeviceAdminActive();

        if (isActive) {
            adminStatusDot.setBackgroundResource(R.drawable.shape_circle_green);
            adminStatusText.setText(R.string.status_active);
            adminStatusText.setTextColor(0xFF10B981);
            adminStatusBadge.setBackgroundResource(R.drawable.shape_status_badge_active);
            btnToggleAdmin.setText(R.string.btn_disable_admin);
            btnToggleAdmin.setTextColor(0xFFF43F5E);
            btnToggleAdmin.setBackgroundResource(R.drawable.shape_button_outline_red);
        } else {
            adminStatusDot.setBackgroundResource(R.drawable.shape_circle_red);
            adminStatusText.setText(R.string.status_inactive);
            adminStatusText.setTextColor(0xFFF43F5E);
            adminStatusBadge.setBackgroundResource(R.drawable.shape_status_badge_inactive);
            btnToggleAdmin.setText(R.string.btn_enable_admin);
            btnToggleAdmin.setTextColor(0xFF818CF8);
            btnToggleAdmin.setBackgroundResource(R.drawable.shape_button_outline_purple);
        }
    }

    /**
     * Check if our AccessibilityService is enabled
     */
    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + FocusGuardService.class.getCanonicalName();
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices != null) {
                return enabledServices.contains(serviceName);
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /**
     * Check if Device Admin is active
     */
    private boolean isDeviceAdminActive() {
        return devicePolicyManager.isAdminActive(adminComponent);
    }
}
