package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * FocusGuard Blocker Service — v1.6.1 Bug-Fixed
 *
 * BUG FIXES in this version:
 *   - FIX #1: OEM compatibility — Samsung/OPPO/Xiaomi settings package detection
 *   - FIX #2: Instagram reels detection — removed unreliable content description check
 *   - FIX #3: Removed unused ComponentName import
 */
public class BlockerService extends AccessibilityService {

    // BUG FIX #1: Samsung uses "com.samsung.android.settings"
    // OPPO/Realme use "com.coloros.settings"
    // We use contains() instead of equals() to handle ALL OEMs
    private static final String PKG_SETTINGS_KEYWORD = "settings"; // matches all OEM variants

    private static final String PKG_WHATSAPP  = "com.whatsapp";
    private static final String PKG_YOUTUBE   = "com.google.android.youtube";
    private static final String PKG_INSTAGRAM = "com.instagram.android";

    private static final String OUR_PACKAGE   = "com.focusguard.app";
    private static final String SERVICE_LABEL = "FocusGuard";

    // Pre-allocated Handler + Runnable for zero-GC hot path
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable kickOutRunnable = new Runnable() {
        @Override
        public void run() {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    };

    private PreferenceManager prefManager;
    private static BlockerService instance;

    public static BlockerService getInstance() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefManager = new PreferenceManager(this);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    public void disableService() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            disableSelf();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefManager == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgName = pkg.toString().toLowerCase();

        int eventType = event.getEventType();

        // BUG FIX #1: Use contains() instead of equals()
        // This matches: com.android.settings, com.samsung.android.settings,
        // com.coloros.settings, com.miui.settings, com.oppo.settings, etc.
        if (pkgName.contains(PKG_SETTINGS_KEYWORD)) {
            handleSettingsEvent(event, eventType);
            return;
        }

        // Always-on blocking — no timer dependency, just check user preference switches
        if (PKG_WHATSAPP.equals(pkg.toString())) {
            if (prefManager.isWhatsAppBlocked()
                    && (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                     || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
                handleWhatsApp();
            }
        } else if (PKG_YOUTUBE.equals(pkg.toString())) {
            if (prefManager.isYouTubeBlocked()) {
                handleYouTube(event, eventType);
            }
        } else if (PKG_INSTAGRAM.equals(pkg.toString())) {
            if (prefManager.isInstagramBlocked()
                    && (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                     || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
                handleInstagram();
            }
        }
    }

    // =========================================================================
    // SETTINGS PROTECTION (Surgical — FocusGuard entries only)
    // TWO INDEPENDENT HANDLERS: Accessibility ≠ Device Admin
    // =========================================================================

    private void handleSettingsEvent(AccessibilityEvent event, int eventType) {
        // Only act on: click (user tapped), or window change (new screen opened)
        if (eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        // --- ACCESSIBILITY PROTECTION (independent) ---
        // Only triggers in accessibility settings screens
        if (prefManager.isAccessibilityProtected()) {
            handleAccessibilityProtection(event, eventType);
        }

        // --- DEVICE ADMIN PROTECTION (independent) ---
        // Only triggers in device admin screens
        if (prefManager.isDeviceAdminProtected()) {
            handleAdminProtection(event, eventType);
        }
    }

    /**
     * ACCESSIBILITY PROTECTION
     * Triggers ONLY when user taps FocusGuard in Accessibility Settings.
     * Does NOT interfere with Device Admin screens.
     *
     * FIX: Many OEMs do NOT put item text in event.getText() for list clicks.
     * Instead, the text is in event.getSource() — the actual clicked node.
     * We check BOTH for maximum OEM compatibility.
     */
    private void handleAccessibilityProtection(AccessibilityEvent event, int eventType) {
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {

            // PRIMARY CHECK: getSource() — the actually tapped node (most reliable)
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                boolean found = nodeContainsFocusGuardText(source);
                source.recycle();
                if (found) {
                    triggerKickOut();
                    return;
                }
            }

            // FALLBACK: check event text (works on AOSP)
            String tapped = getEventText(event).toLowerCase();
            if (tapped.contains("focusguard") || tapped.contains("blocker")) {
                triggerKickOut();
                return;
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = event.getClassName();
            if (cls == null) return;
            String clsName = cls.toString().toLowerCase();

            // Detect the accessibility DETAIL/TOGGLE screen (not the list page)
            // These class names appear when a specific service's page opens
            boolean isDetailScreen =
                clsName.contains("toggleaccessibilityservice") ||
                clsName.contains("accessibilityservicewarning") ||
                clsName.contains("accessibilitydetails") ||
                clsName.contains("accessibilityindividualsettings") ||
                (clsName.contains("accessibility") && clsName.contains("fragment")) ||
                (clsName.contains("accessibility") && clsName.contains("dialog"));

            if (isDetailScreen) {
                checkWindowForFocusGuard();
            }
        }
    }

    /** Check if a node or its immediate children contain FocusGuard text */
    private boolean nodeContainsFocusGuardText(AccessibilityNodeInfo node) {
        if (node == null) return false;
        // Check the node itself
        CharSequence text = node.getText();
        if (text != null && (text.toString().toLowerCase().contains("focusguard")
                || text.toString().toLowerCase().contains("blocker"))) {
            return true;
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && (desc.toString().toLowerCase().contains("focusguard")
                || desc.toString().toLowerCase().contains("blocker"))) {
            return true;
        }
        // Check direct children (list items often have icon + text as children)
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            CharSequence childText = child.getText();
            if (childText != null && (childText.toString().toLowerCase().contains("focusguard")
                    || childText.toString().toLowerCase().contains("blocker"))) {
                child.recycle();
                return true;
            }
            child.recycle();
        }
        return false;
    }

    /**
     * DEVICE ADMIN PROTECTION
     * Triggers ONLY when user taps FocusGuard in Device Admin settings.
     * Does NOT interfere with Accessibility screens.
     */
    private void handleAdminProtection(AccessibilityEvent event, int eventType) {
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Fast path: user tapped — check if it's our admin item
            String tapped = getEventText(event).toLowerCase();
            if (tapped.contains("focusguard") || tapped.contains("blocker")) {
                triggerKickOut();
                return;
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // A new screen opened — check if it's the device admin confirmation screen
            CharSequence cls = event.getClassName();
            if (cls == null) return;
            String clsName = cls.toString().toLowerCase();

            boolean isAdminScreen =
                clsName.contains("deviceadminadd") ||
                clsName.contains("deviceadmin") && !clsName.contains("accessibility");

            if (isAdminScreen) {
                checkWindowForFocusGuard();
            }
        }
    }

    private void checkWindowForFocusGuard() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (nodeTreeContainsFocusGuard(root)) {
                triggerKickOut();
            }
        } finally {
            root.recycle();
        }
    }

    private boolean nodeTreeContainsFocusGuard(AccessibilityNodeInfo root) {
        // Android's built-in text search is hardware-accelerated and faster than manual DFS
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(SERVICE_LABEL);
        if (hits != null && !hits.isEmpty()) {
            for (AccessibilityNodeInfo n : hits) { if (n != null) n.recycle(); }
            return true;
        }
        List<AccessibilityNodeInfo> pkgHits = root.findAccessibilityNodeInfosByText(OUR_PACKAGE);
        if (pkgHits != null && !pkgHits.isEmpty()) {
            for (AccessibilityNodeInfo n : pkgHits) { if (n != null) n.recycle(); }
            return true;
        }
        return false;
    }

    private void triggerKickOut() {
        mainHandler.removeCallbacks(kickOutRunnable);
        mainHandler.post(kickOutRunnable);
    }

    // =========================================================================
    // WHATSAPP UPDATES BLOCKING
    // =========================================================================

    private void handleWhatsApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (isWhatsAppUpdatesVisible(root)) {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } finally {
            root.recycle();
        }
    }

    private boolean isWhatsAppUpdatesVisible(AccessibilityNodeInfo root) {
        String[] tabLabels = {"Updates", "Status", "Channels", "আপডেট", "স্ট্যাটাস", "চ্যানেল"};
        for (String label : tabLabels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                boolean active = node.isSelected() || node.isFocused() || isAncestorSelected(node);
                node.recycle();
                if (active) return true;
            }
        }
        return false;
    }

    private boolean isAncestorSelected(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node.getParent();
        int depth = 0;
        while (current != null && depth < 6) {
            if (current.isSelected()) {
                current.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
            depth++;
        }
        if (current != null) current.recycle();
        return false;
    }

    // =========================================================================
    // YOUTUBE SHORTS BLOCKING
    // =========================================================================

    private void handleYouTube(AccessibilityEvent event, int eventType) {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = event.getClassName();
            if (cls != null && cls.toString().toLowerCase().contains("shorts")) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                return;
            }
        }
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Shorts");
                if (hits == null || hits.isEmpty()) {
                    hits = root.findAccessibilityNodeInfosByText("শর্টস");
                }
                if (hits != null && !hits.isEmpty()) {
                    for (AccessibilityNodeInfo n : hits) { if (n != null) n.recycle(); }
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            } finally {
                root.recycle();
            }
        }
    }

    // =========================================================================
    // INSTAGRAM REELS BLOCKING
    // =========================================================================

    private void handleInstagram() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // BUG FIX #2: Removed unreliable content description check.
            // Previously required cd != null which caused many misses.
            // Now blocks as soon as Reels node is found in the tree.
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Reels");
            if (hits == null || hits.isEmpty()) {
                hits = root.findAccessibilityNodeInfosByText("রিলস");
            }
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo n : hits) { if (n != null) n.recycle(); }
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } finally {
            root.recycle();
        }
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    private String getEventText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null) sb.append(t).append(' ');
            }
        }
        CharSequence desc = event.getContentDescription();
        if (desc != null) sb.append(desc);
        return sb.toString();
    }

    @Override
    public void onInterrupt() {}
}
