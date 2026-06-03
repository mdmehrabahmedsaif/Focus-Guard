package com.focusguard.app;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {
    private static final String PREF_NAME = "FocusGuardSettings";
    
    // Keys
    private static final String KEY_IS_SERVICE_ACTIVE = "is_service_active";
    private static final String KEY_BLOCK_WHATSAPP = "block_whatsapp";
    private static final String KEY_BLOCK_YOUTUBE = "block_youtube";
    private static final String KEY_BLOCK_INSTAGRAM = "block_instagram";
    private static final String KEY_BLOCK_GOOGLE_DOCS = "block_google_docs";
    private static final String KEY_BLOCK_GOOGLE_ASSISTANT = "block_google_assistant";
    private static final String KEY_BLOCK_ACCESSIBILITY = "block_accessibility";
    private static final String KEY_BLOCK_DEVICE_ADMIN = "block_device_admin";
    private static final String KEY_BLOCK_UNINSTALL = "block_uninstall";
    private static final String KEY_BLOCK_PRIVATE_DNS = "block_private_dns";
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

    public void setWhatsAppBlocked(boolean blocked) {
        prefs.edit().putBoolean(KEY_BLOCK_WHATSAPP, blocked).apply();
    }

    public boolean isWhatsAppBlocked() {
        return prefs.getBoolean(KEY_BLOCK_WHATSAPP, true);
    }

    public void setYouTubeBlocked(boolean blocked) {
        prefs.edit().putBoolean(KEY_BLOCK_YOUTUBE, blocked).apply();
    }

    public boolean isYouTubeBlocked() {
        return prefs.getBoolean(KEY_BLOCK_YOUTUBE, true);
    }

    public void setInstagramBlocked(boolean blocked) {
        prefs.edit().putBoolean(KEY_BLOCK_INSTAGRAM, blocked).apply();
    }

    public boolean isInstagramBlocked() {
        return prefs.getBoolean(KEY_BLOCK_INSTAGRAM, true);
    }

    public void setGoogleDocsBlocked(boolean blocked) {
        prefs.edit().putBoolean(KEY_BLOCK_GOOGLE_DOCS, blocked).apply();
    }

    public boolean isGoogleDocsBlocked() {
        return prefs.getBoolean(KEY_BLOCK_GOOGLE_DOCS, true);
    }

    public void setGoogleAssistantBlocked(boolean blocked) {
        prefs.edit().putBoolean(KEY_BLOCK_GOOGLE_ASSISTANT, blocked).apply();
    }

    public boolean isGoogleAssistantBlocked() {
        return prefs.getBoolean(KEY_BLOCK_GOOGLE_ASSISTANT, true);
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

    public void setUninstallProtected(boolean protected_un) {
        prefs.edit().putBoolean(KEY_BLOCK_UNINSTALL, protected_un).apply();
    }

    public boolean isUninstallProtected() {
        return prefs.getBoolean(KEY_BLOCK_UNINSTALL, false);
    }

    public void setPrivateDNSBlocked(boolean blocked) {
        prefs.edit().putBoolean(KEY_BLOCK_PRIVATE_DNS, blocked).apply();
    }

    public boolean isPrivateDNSBlocked() {
        return prefs.getBoolean(KEY_BLOCK_PRIVATE_DNS, true);
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
}
