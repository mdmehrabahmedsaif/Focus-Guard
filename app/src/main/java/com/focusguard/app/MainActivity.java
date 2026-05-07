package com.focusguard.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private SharedPreferences prefs;

    private TextView tvServiceStatus;
    private TextView tvAdminStatus;
    private Button btnEnableService;
    private Button btnEnableAdmin;
    private Switch switchWhatsApp;
    private Switch switchYouTube;
    private Switch switchInstagram;

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
        btnEnableService = findViewById(R.id.btnEnableService);
        btnEnableAdmin = findViewById(R.id.btnEnableAdmin);
        switchWhatsApp = findViewById(R.id.switchWhatsApp);
        switchYouTube = findViewById(R.id.switchYouTube);
        switchInstagram = findViewById(R.id.switchInstagram);

        // Load saved settings
        switchWhatsApp.setChecked(prefs.getBoolean("block_whatsapp", true));
        switchYouTube.setChecked(prefs.getBoolean("block_youtube", true));
        switchInstagram.setChecked(prefs.getBoolean("block_instagram", true));

        // Switch listeners
        switchWhatsApp.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("block_whatsapp", checked).apply());

        switchYouTube.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("block_youtube", checked).apply());

        switchInstagram.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("block_instagram", checked).apply());

        // Enable Accessibility Service
        btnEnableService.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this,
                "FocusGuard খুঁজে সেটা ON করো",
                Toast.LENGTH_LONG).show();
        });

        // Enable Device Admin
        btnEnableAdmin.setOnClickListener(v -> {
            if (!dpm.isAdminActive(adminComponent)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "FocusGuard কে আনইন্সটল থেকে রক্ষা করতে Device Admin চালু করো।");
                startActivityForResult(intent, REQUEST_ENABLE_ADMIN);
            } else {
                Toast.makeText(this, "✅ Device Admin ইতিমধ্যে চালু আছে!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusUI();
    }

    private void updateStatusUI() {
        // Check accessibility service
        boolean serviceEnabled = isAccessibilityServiceEnabled();
        if (serviceEnabled) {
            tvServiceStatus.setText("✅ Accessibility Service চালু আছে");
            tvServiceStatus.setTextColor(0xFF4CAF50);
            btnEnableService.setText("✅ চালু আছে");
        } else {
            tvServiceStatus.setText("❌ Accessibility Service বন্ধ আছে");
            tvServiceStatus.setTextColor(0xFFF44336);
            btnEnableService.setText("Accessibility Service চালু করো");
        }

        // Check device admin
        boolean adminEnabled = dpm.isAdminActive(adminComponent);
        if (adminEnabled) {
            tvAdminStatus.setText("✅ আনইন্সটল সুরক্ষা চালু আছে");
            tvAdminStatus.setTextColor(0xFF4CAF50);
            btnEnableAdmin.setText("✅ সুরক্ষা চালু আছে");
        } else {
            tvAdminStatus.setText("❌ সুরক্ষা চালু নেই");
            tvAdminStatus.setTextColor(0xFFF44336);
            btnEnableAdmin.setText("Device Admin চালু করো");
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
    }
}
