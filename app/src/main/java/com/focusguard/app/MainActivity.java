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

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private PreferenceManager pref;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    private TextView tvAdminStatus, tvAccessibilityStatus;
    private View btnEnableAdmin, btnDisableAdmin, btnEnableAccessibility, btnDisableAccessibility;
    private View btnSavePassword;
    private SwitchCompat swWhatsApp, swYouTube, swInstagram, swGoogleAssistant, swGoogleDocs, swPrivateDNS, swBlockerHero, swBlockAcc, swBlockAdmin;
    private EditText etPassword;
    private boolean isSyncingUI = false;

    private static final int REQ_ADMIN = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);

            pref = new PreferenceManager(this);
            dpm  = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            adminComponent = new ComponentName(this, AdminReceiver.class);

            initViews();
            setupListeners();
            syncUIWithState();
        } catch (Exception e) {
            Toast.makeText(this, "Init Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initViews() {
        tvAdminStatus          = findViewById(R.id.tvAdminStatus);
        tvAccessibilityStatus  = findViewById(R.id.tvAccessibilityStatus);
        btnEnableAdmin         = findViewById(R.id.btnEnableAdmin);
        btnDisableAdmin        = findViewById(R.id.btnDisableAdmin);
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility);
        btnDisableAccessibility= findViewById(R.id.btnDisableAccessibility);
        btnSavePassword        = findViewById(R.id.btnSavePassword);
        etPassword             = findViewById(R.id.etPasscode);

        setupAppRow(R.id.rowWhatsApp,  "💬", "WhatsApp Updates", "Block channels & feeds");
        setupAppRow(R.id.rowYouTube,   "▶️", "YouTube Shorts",   "Stop scroll addiction");
        setupAppRow(R.id.rowInstagram, "📸", "Instagram Reels",  "Master your time");
        setupAppRow(R.id.rowGoogleAssistant, "🎙️", "Google Assistant & App", "Block Google search & voice assistant");
        setupAppRow(R.id.rowGoogleDocs,"📝", "Google Docs",      "Block web image search");
        setupAppRow(R.id.rowPrivateDNS,"🌐", "Private DNS Settings", "Block access to Private DNS settings");
        setupAppRow(R.id.rowBlockerHero,"🛡️", "Block Blocker Hero", "Block entire Blocker Hero app");

        swWhatsApp  = findViewById(R.id.rowWhatsApp).findViewById(R.id.itemSwitch);
        swYouTube   = findViewById(R.id.rowYouTube).findViewById(R.id.itemSwitch);
        swInstagram = findViewById(R.id.rowInstagram).findViewById(R.id.itemSwitch);
        swGoogleAssistant = findViewById(R.id.rowGoogleAssistant).findViewById(R.id.itemSwitch);
        swGoogleDocs = findViewById(R.id.rowGoogleDocs).findViewById(R.id.itemSwitch);
        swPrivateDNS = findViewById(R.id.rowPrivateDNS).findViewById(R.id.itemSwitch);
        swBlockerHero = findViewById(R.id.rowBlockerHero).findViewById(R.id.itemSwitch);
        swBlockAcc   = findViewById(R.id.switchBlockAccessibility);
        swBlockAdmin = findViewById(R.id.switchBlockDeviceAdmin);

        if (etPassword != null) {
            etPassword.setText(pref.getEmergencyPassword());
        }
    }

    private void setupAppRow(int rowId, String icon, String title, String desc) {
        View row = findViewById(rowId);
        if (row == null) return;
        ((TextView) row.findViewById(R.id.itemIcon)).setText(icon);
        ((TextView) row.findViewById(R.id.itemTitle)).setText(title);
        ((TextView) row.findViewById(R.id.itemDesc)).setText(desc);
    }

    private void setupListeners() {
        // --- Accessibility Service ---
        btnEnableAccessibility.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Find and enable Focus Guard service", Toast.LENGTH_LONG).show();
        });

        btnDisableAccessibility.setOnClickListener(v -> promptPassword(() -> {
            if (BlockerService.getInstance() != null) {
                BlockerService.getInstance().disableService();
                syncUIWithState();
            }
        }));

        // --- Device Admin ---
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
            syncUIWithState();
        }));

        // --- App Blocking Switches ---
        swWhatsApp.setOnCheckedChangeListener((b, checked) -> {
            if (isSyncingUI) return;
            pref.setWhatsAppBlocked(checked);
            pref.setServiceActive(checked || pref.isYouTubeBlocked() || pref.isInstagramBlocked() || pref.isGoogleAssistantBlocked() || pref.isGoogleDocsBlocked() || pref.isPrivateDNSBlocked() || pref.isBlockerHeroBlocked());
        });
        swYouTube.setOnCheckedChangeListener((b, checked) -> {
            if (isSyncingUI) return;
            pref.setYouTubeBlocked(checked);
            pref.setServiceActive(pref.isWhatsAppBlocked() || checked || pref.isInstagramBlocked() || pref.isGoogleAssistantBlocked() || pref.isGoogleDocsBlocked() || pref.isPrivateDNSBlocked() || pref.isBlockerHeroBlocked());
        });
        swInstagram.setOnCheckedChangeListener((b, checked) -> {
            if (isSyncingUI) return;
            pref.setInstagramBlocked(checked);
            pref.setServiceActive(pref.isWhatsAppBlocked() || pref.isYouTubeBlocked() || checked || pref.isGoogleAssistantBlocked() || pref.isGoogleDocsBlocked() || pref.isPrivateDNSBlocked() || pref.isBlockerHeroBlocked());
        });
        swGoogleAssistant.setOnCheckedChangeListener((b, checked) -> {
            if (isSyncingUI) return;
            pref.setGoogleAssistantBlocked(checked);
            pref.setServiceActive(pref.isWhatsAppBlocked() || pref.isYouTubeBlocked() || pref.isInstagramBlocked() || checked || pref.isGoogleDocsBlocked() || pref.isPrivateDNSBlocked() || pref.isBlockerHeroBlocked());
        });
        swGoogleDocs.setOnCheckedChangeListener((b, checked) -> {
            if (isSyncingUI) return;
            pref.setGoogleDocsBlocked(checked);
            pref.setServiceActive(pref.isWhatsAppBlocked() || pref.isYouTubeBlocked() || pref.isInstagramBlocked() || pref.isGoogleAssistantBlocked() || checked || pref.isPrivateDNSBlocked() || pref.isBlockerHeroBlocked());
        });
        swPrivateDNS.setOnCheckedChangeListener((b, checked) -> {
            if (isSyncingUI) return;
            pref.setPrivateDNSBlocked(checked);
            pref.setServiceActive(pref.isWhatsAppBlocked() || pref.isYouTubeBlocked() || pref.isInstagramBlocked() || pref.isGoogleAssistantBlocked() || pref.isGoogleDocsBlocked() || checked || pref.isBlockerHeroBlocked());
        });
        swBlockerHero.setOnCheckedChangeListener((b, checked) -> {
            if (isSyncingUI) return;
            pref.setBlockerHeroBlocked(checked);
            pref.setServiceActive(pref.isWhatsAppBlocked() || pref.isYouTubeBlocked() || pref.isInstagramBlocked() || pref.isGoogleAssistantBlocked() || pref.isGoogleDocsBlocked() || pref.isPrivateDNSBlocked() || checked);
        });

        // Independent protection locks
        swBlockAcc.setOnCheckedChangeListener((b, checked) -> {
            if (isSyncingUI) return;
            pref.setAccessibilityProtected(checked);
        });
        swBlockAdmin.setOnCheckedChangeListener((b, checked) -> {
            if (isSyncingUI) return;
            pref.setDeviceAdminProtected(checked);
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
            Toast.makeText(this, "✅ Password saved", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Password-only protection. No timer dependency.
     * Always requires password to perform sensitive actions.
     */
    private void promptPassword(Runnable onVerify) {
        String savedPass = pref.getEmergencyPassword();
        if (savedPass == null || savedPass.isEmpty()) {
            // No password set — allow action but warn user
            Toast.makeText(this, "⚠️ Set a password first for protection", Toast.LENGTH_LONG).show();
            onVerify.run();
            return;
        }

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter password");

        new android.app.AlertDialog.Builder(this)
            .setTitle("🛡️ Authentication Required")
            .setMessage("Enter your Focus Guard password to continue.")
            .setView(input)
            .setPositiveButton("VERIFY", (dialog, which) -> {
                String entered = input.getText().toString();
                if (!entered.isEmpty() && entered.equals(savedPass)) {
                    onVerify.run();
                    syncUIWithState();
                } else {
                    Toast.makeText(this, "❌ Wrong password", Toast.LENGTH_SHORT).show();
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
        if (isFinishing() || tvAdminStatus == null) return;

        isSyncingUI = true;
        try {
            boolean adminOn   = dpm.isAdminActive(adminComponent);
            boolean serviceOn = isAccessibilityServiceEnabled();

            // Service status
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

            // Admin status
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

            // App blocking switches
            swWhatsApp.setChecked(pref.isWhatsAppBlocked());
            swYouTube.setChecked(pref.isYouTubeBlocked());
            swInstagram.setChecked(pref.isInstagramBlocked());
            swGoogleAssistant.setChecked(pref.isGoogleAssistantBlocked());
            swGoogleDocs.setChecked(pref.isGoogleDocsBlocked());
            swPrivateDNS.setChecked(pref.isPrivateDNSBlocked());
            swBlockerHero.setChecked(pref.isBlockerHeroBlocked());

            // Protection lock switches (independent)
            swBlockAcc.setChecked(pref.isAccessibilityProtected());
            swBlockAdmin.setChecked(pref.isDeviceAdminProtected());
        } finally {
            isSyncingUI = false;
        }
    }

    @Override protected void onResume()  { super.onResume();  syncUIWithState(); }
}
