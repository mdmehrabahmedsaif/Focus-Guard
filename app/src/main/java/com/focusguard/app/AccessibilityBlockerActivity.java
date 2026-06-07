package com.focusguard.app;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class AccessibilityBlockerActivity extends AppCompatActivity {

    private PreferenceManager pref;
    private SwitchCompat swBlockerHeroAcc;
    private Button btnBack;
    private boolean isSyncingUI = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_accessibility_blocker);

            pref = new PreferenceManager(this);

            initViews();
            setupListeners();
            syncUIWithState();
        } catch (Exception e) {
            Toast.makeText(this, "Init Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initViews() {
        swBlockerHeroAcc = findViewById(R.id.switchBlockerHeroAcc);
        btnBack          = findViewById(R.id.btnBack);
    }

    private void setupListeners() {
        if (swBlockerHeroAcc != null) {
            swBlockerHeroAcc.setOnCheckedChangeListener((b, checked) -> {
                if (isSyncingUI) return;
                pref.setBlockerHeroAccessibilityBlocked(checked);
                // Ensure service is active if anything is blocked
                pref.setServiceActive(pref.isWhatsAppBlocked() || 
                                      pref.isYouTubeBlocked() || 
                                      pref.isInstagramBlocked() || 
                                      pref.isGoogleAssistantBlocked() || 
                                      pref.isGoogleDocsBlocked() || 
                                      pref.isPrivateDNSBlocked() || 
                                      pref.isBlockerHeroBlocked() || 
                                      checked);
            });
        }

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }
    }

    private void syncUIWithState() {
        if (isFinishing() || swBlockerHeroAcc == null) return;

        isSyncingUI = true;
        try {
            swBlockerHeroAcc.setChecked(pref.isBlockerHeroAccessibilityBlocked());
        } finally {
            isSyncingUI = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncUIWithState();
    }
}
