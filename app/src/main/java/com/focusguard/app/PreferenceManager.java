package com.focusguard.app;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {
    private static final String PREF_NAME = "FocusGuardSettings";
    
    private static final String KEY_IS_SERVICE_ACTIVE = "is_service_active";
    private static final String KEY_BLOCK_GOOGLE_DOCS = "block_google_docs";
    private static final String KEY_BLOCK_DEVICE_ADMIN = "block_device_admin";
    private static final String KEY_BLOCK_UNINSTALL = "block_uninstall";
    private static final String KEY_EMERGENCY_PASSWORD = "emergency_password";
    private static final String KEY_TIMER_END_TIME = "timer_end_time";

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

    public void setDeviceAdminProtected(boolean protected_admin) {
        prefs.edit().putBoolean(KEY_BLOCK_DEVICE_ADMIN, protected_admin).apply();
    }

    public boolean isDeviceAdminProtected() {
        return prefs.getBoolean(KEY_BLOCK_DEVICE_ADMIN, false);
    }

    public void setUninstallProtected(boolean protected_un) {
        prefs.edit().putBoolean(KEY_BLOCK_UNINSTALL, protected_un).apply();
    }

    public boolean isUninstallProtected() {
        return prefs.getBoolean(KEY_BLOCK_UNINSTALL, false);
    }

    public void setEmergencyPassword(String password) {
        prefs.edit().putString(KEY_EMERGENCY_PASSWORD, password).apply();
    }

    public String getEmergencyPassword() {
        return prefs.getString(KEY_EMERGENCY_PASSWORD, "");
    }

    public void setTimerEndTime(long endTime) {
        prefs.edit().putLong(KEY_TIMER_END_TIME, endTime).apply();
    }

    public long getTimerEndTime() {
        return prefs.getLong(KEY_TIMER_END_TIME, 0);
    }

    public boolean isTimerActive() {
        return System.currentTimeMillis() < getTimerEndTime();
    }

    // Dummy methods for backward compatibility/quick compilation
    public boolean isWhatsAppBlocked() { return false; }
    public boolean isGoogleAssistantBlocked() { return false; }
    public boolean isBlockerHeroBlocked() { return false; }
    public boolean isAccessibilityProtected() { return false; }
}
