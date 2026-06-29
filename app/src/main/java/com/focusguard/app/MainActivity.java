package com.focusguard.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private PreferenceManager pref;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    // Core views
    private TextView tvOverallStatusText, tvAccessibilityStatusText, tvAdminStatusText;
    private View statusGlowCircle, accessibilityStatusDot, adminStatusDot;
    private View btnEnableAccessibility, btnDisableAccessibility, btnEnableAdmin, btnDisableAdmin;
    private View btnSavePassword;
    private SwitchCompat swGoogleDocs, swBlockAcc, swBlockAdmin;
    private EditText etPassword;

    private static final int REQ_ADMIN = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);

            pref = new PreferenceManager(this);
            dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            adminComponent = new ComponentName(this, FocusGuardDeviceAdmin.class);

            initViews();
            setupListeners();
            syncUIWithState();
        } catch (Exception e) {
            Toast.makeText(this, "Init Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initViews() {
        tvOverallStatusText = findViewById(R.id.tvOverallStatusText);
        statusGlowCircle = findViewById(R.id.statusGlowCircle);

        tvAccessibilityStatusText = findViewById(R.id.tvAccessibilityStatusText);
        accessibilityStatusDot = findViewById(R.id.accessibilityStatusDot);
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);
        btnDisableAccessibility = findViewById(R.id.btnDisableAccessibility);

        tvAdminStatusText = findViewById(R.id.tvAdminStatusText);
        adminStatusDot = findViewById(R.id.adminStatusDot);
        btnEnableAdmin = findViewById(R.id.btnEnableAdmin);
        btnDisableAdmin = findViewById(R.id.btnDisableAdmin);

        swGoogleDocs = findViewById(R.id.swGoogleDocs);
        swBlockAcc = findViewById(R.id.swBlockAccessibility);
        swBlockAdmin = findViewById(R.id.swBlockDeviceAdmin);

        btnSavePassword = findViewById(R.id.btnSavePassword);
        etPassword = findViewById(R.id.etPasscode);

        if (etPassword != null) {
            etPassword.setText(pref.getEmergencyPassword());
        }
    }

    private void setupListeners() {
        // --- Accessibility Service Deployment ---
        btnEnableAccessibility.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Find and enable Focus Guard service", Toast.LENGTH_LONG).show();
        });

        btnDisableAccessibility.setOnClickListener(v -> promptPassword(() -> {
            if (FocusGuardService.getInstance() != null) {
                FocusGuardService.getInstance().disableService();
                Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show();
            }
        }));

        // --- Device Admin Deployment ---
        btnEnableAdmin.setOnClickListener(v -> {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for uninstall protection.");
            startActivityForResult(i, REQ_ADMIN);
        });

        btnDisableAdmin.setOnClickListener(v -> promptPassword(() -> {
            try {
                dpm.removeActiveAdmin(adminComponent);
                Toast.makeText(this, "Admin removed", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }));

        // --- Switches ---
        swGoogleDocs.setOnCheckedChangeListener((b, checked) -> {
            pref.setGoogleDocsBlocked(checked);
            pref.setServiceActive(checked || pref.isAccessibilityProtected() || pref.isDeviceAdminProtected());
        });

        swBlockAcc.setOnCheckedChangeListener((b, checked) -> {
            pref.setAccessibilityProtected(checked);
            pref.setServiceActive(checked || pref.isGoogleDocsBlocked() || pref.isDeviceAdminProtected());
        });

        swBlockAdmin.setOnCheckedChangeListener((b, checked) -> {
            pref.setDeviceAdminProtected(checked);
            pref.setServiceActive(checked || pref.isGoogleDocsBlocked() || pref.isAccessibilityProtected());
        });

        // --- Save Password ---
        btnSavePassword.setOnClickListener(v -> {
            if (etPassword == null) return;
            String pass = etPassword.getText().toString().trim();
            if (pass.isEmpty()) {
                Toast.makeText(this, "❌ Password cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            pref.setEmergencyPassword(pass);
            Toast.makeText(this, "✅ Password saved successfully", Toast.LENGTH_SHORT).show();
        });
    }

    private void promptPassword(Runnable onVerify) {
        String savedPass = pref.getEmergencyPassword();
        if (savedPass == null || savedPass.isEmpty()) {
            Toast.makeText(this, "⚠️ Set a password first for protection", Toast.LENGTH_LONG).show();
            onVerify.run();
            syncUIWithState();
            return;
        }

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter password");
        input.setPadding(48, 24, 48, 24);

        new MaterialAlertDialogBuilder(this)
            .setTitle("🛡️ Verification Required")
            .setMessage("Please enter your emergency password to halt protection.")
            .setView(input)
            .setPositiveButton("VERIFY", (dialog, which) -> {
                String entered = input.getText().toString();
                if (!entered.isEmpty() && entered.equals(savedPass)) {
                    onVerify.run();
                    syncUIWithState();
                } else {
                    Toast.makeText(this, "❌ Incorrect password", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("CANCEL", null)
            .show();
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (services == null) return false;
        for (AccessibilityServiceInfo s : services) {
            if (s.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) return true;
        }
        return false;
    }

    private void syncUIWithState() {
        if (isFinishing()) return;

        boolean adminOn = dpm.isAdminActive(adminComponent);
        boolean serviceOn = isAccessibilityServiceEnabled();

        // 1. Overall Status Header
        if (serviceOn) {
            tvOverallStatusText.setText("PROTECTION ACTIVE");
            tvOverallStatusText.setTextColor(ContextCompat.getColor(this, R.color.success_emerald));
            statusGlowCircle.setBackgroundResource(R.drawable.shape_circle_green);
        } else {
            tvOverallStatusText.setText("PROTECTION OFFLINE");
            tvOverallStatusText.setTextColor(ContextCompat.getColor(this, R.color.danger_rose));
            statusGlowCircle.setBackgroundResource(R.drawable.shape_circle_red);
        }

        // 2. Accessibility Status Card
        if (serviceOn) {
            tvAccessibilityStatusText.setText("ACTIVE");
            tvAccessibilityStatusText.setTextColor(ContextCompat.getColor(this, R.color.success_emerald));
            accessibilityStatusDot.setBackgroundResource(R.drawable.shape_circle_green);
            btnEnableAccessibility.setVisibility(View.GONE);
            btnDisableAccessibility.setVisibility(View.VISIBLE);
        } else {
            tvAccessibilityStatusText.setText("OFFLINE");
            tvAccessibilityStatusText.setTextColor(ContextCompat.getColor(this, R.color.danger_rose));
            accessibilityStatusDot.setBackgroundResource(R.drawable.shape_circle_red);
            btnEnableAccessibility.setVisibility(View.VISIBLE);
            btnDisableAccessibility.setVisibility(View.GONE);
        }

        // 3. Device Admin Status Card
        if (adminOn) {
            tvAdminStatusText.setText("ACTIVE");
            tvAdminStatusText.setTextColor(ContextCompat.getColor(this, R.color.success_emerald));
            adminStatusDot.setBackgroundResource(R.drawable.shape_circle_green);
            btnEnableAdmin.setVisibility(View.GONE);
            btnDisableAdmin.setVisibility(View.VISIBLE);
        } else {
            tvAdminStatusText.setText("OFFLINE");
            tvAdminStatusText.setTextColor(ContextCompat.getColor(this, R.color.danger_rose));
            adminStatusDot.setBackgroundResource(R.drawable.shape_circle_red);
            btnEnableAdmin.setVisibility(View.VISIBLE);
            btnDisableAdmin.setVisibility(View.GONE);
        }

        // 4. Feature Switches
        swGoogleDocs.setChecked(pref.isGoogleDocsBlocked());
        swBlockAcc.setChecked(pref.isAccessibilityProtected());
        swBlockAdmin.setChecked(pref.isDeviceAdminProtected());
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncUIWithState();
    }
}
