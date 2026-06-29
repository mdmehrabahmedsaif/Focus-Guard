package com.focusguard.app;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {
    private static final String PREF_NAME = "FocusGuardSettings";
    
    // Keys
    private static final String KEY_IS_SERVICE_ACTIVE = "is_service_active";
    private static final String KEY_BLOCK_GOOGLE_DOCS = "block_google_docs";
    private static final String KEY_BLOCK_ACCESSIBILITY = "block_accessibility";
    private static final String KEY_BLOCK_DEVICE_ADMIN = "block_device_admin";
    private static final String KEY_EMERGENCY_PASSWORD = "emergency_password";

    private final SharedPreferences prefs;

    public PreferenceManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void setServiceActive(boolean active) {
        prefs.edit().putBoolean(KEY_IS_SERVICE_ACTIVE, active).apply();
    }

    public boolean isServiceActive() {
        return prefs.getBoolean(KEY_IS_SERVICE_ACTIVE, true);
    }

    public void setGoogleDocsBlocked(boolean blocked) {
        prefs.edit().putBoolean(KEY_BLOCK_GOOGLE_DOCS, blocked).apply();
    }

    public boolean isGoogleDocsBlocked() {
        return prefs.getBoolean(KEY_BLOCK_GOOGLE_DOCS, true);
    }

    public void setAccessibilityProtected(boolean protected_acc) {
        prefs.edit().putBoolean(KEY_BLOCK_ACCESSIBILITY, protected_acc).apply();
    }

    public boolean isAccessibilityProtected() {
        return prefs.getBoolean(KEY_BLOCK_ACCESSIBILITY, false);
    }

    public void setDeviceAdminProtected(boolean protected_admin) {
        prefs.edit().putBoolean(KEY_BLOCK_DEVICE_ADMIN, protected_admin).apply();
    }

    public boolean isDeviceAdminProtected() {
        return prefs.getBoolean(KEY_BLOCK_DEVICE_ADMIN, false);
    }

    public void setEmergencyPassword(String password) {
        prefs.edit().putString(KEY_EMERGENCY_PASSWORD, password).apply();
    }

    public String getEmergencyPassword() {
        return prefs.getString(KEY_EMERGENCY_PASSWORD, "");
    }
}
