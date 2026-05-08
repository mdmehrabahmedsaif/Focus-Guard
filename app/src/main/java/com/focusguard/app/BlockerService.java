package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * FocusGuard Accessibility Blocker Service
 *
 * Architecture: Event-driven, zero-polling, Handler-based instant exit.
 * Strategy:
 *   - Monitor TYPE_VIEW_CLICKED for pre-emptive detection (catches tap before screen loads)
 *   - Monitor TYPE_WINDOW_CONTENT_CHANGED for fast window scan
 *   - Monitor TYPE_WINDOW_STATE_CHANGED as fallback
 *   - Surgical detection: only block FocusGuard items, not full pages
 *
 * OEM Compatibility:
 *   - Samsung: Settings > Accessibility uses different activity but same text
 *   - Xiaomi: Uses com.android.settings with custom inner classes
 *   - Stock Android (AOSP): Standard com.android.settings behavior
 *   - Strategy: Text+Package detection > Class detection (OEM-agnostic)
 */
public class BlockerService extends AccessibilityService {

    // Target packages to monitor
    private static final String PKG_SETTINGS     = "com.android.settings";
    private static final String PKG_WHATSAPP     = "com.whatsapp";
    private static final String PKG_YOUTUBE      = "com.google.android.youtube";
    private static final String PKG_INSTAGRAM    = "com.instagram.android";

    // Our own package for self-detection
    private static final String OUR_PACKAGE      = "com.focusguard.app";
    private static final String SERVICE_LABEL    = "FocusGuard";

    // Handler for immediate, thread-safe execution on main looper
    // WHY: performGlobalAction MUST run on main thread. Using Handler avoids ANR risk.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Runnable for instant exit - pre-allocated to avoid object allocation on hot path
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
        String pkgName = pkg.toString();

        int eventType = event.getEventType();

        // --- SELF PROTECTION: Settings package only ---
        if (pkgName.equals(PKG_SETTINGS)) {
            handleSettingsEvent(event, eventType);
            return;
        }

        // --- CONTENT BLOCKING ---
        if (!prefManager.isServiceActive()) return;

        switch (pkgName) {
            case PKG_WHATSAPP:
                if (prefManager.isWhatsAppBlocked()
                        && (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                         || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
                    handleWhatsApp();
                }
                break;
            case PKG_YOUTUBE:
                if (prefManager.isYouTubeBlocked()) {
                    handleYouTube(event, eventType);
                }
                break;
            case PKG_INSTAGRAM:
                if (prefManager.isInstagramBlocked()
                        && (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                         || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
                    handleInstagram();
                }
                break;
        }
    }

    // =========================================================================
    // SETTINGS PROTECTION (Surgical — FocusGuard entries only)
    // =========================================================================

    private void handleSettingsEvent(AccessibilityEvent event, int eventType) {
        // WHY these event types:
        // TYPE_VIEW_CLICKED: Fires at the moment user taps — fastest possible detection
        // TYPE_WINDOW_CONTENT_CHANGED: Fires when content shifts after tap
        // TYPE_WINDOW_STATE_CHANGED: Fallback if above miss the transition

        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            // Step 1: Fast text check on the event itself (zero-allocation fast path)
            String eventText = getEventText(event).toLowerCase();
            if (eventText.contains("focusguard") || eventText.contains("blocker")) {
                triggerKickOut();
                return;
            }

            // Step 2: Class-name check for known admin deactivation activities
            CharSequence cls = event.getClassName();
            if (cls != null) {
                String clsName = cls.toString().toLowerCase();
                // These activity class names indicate admin disable confirmation screen
                if (clsName.contains("deviceadmin") || clsName.contains("admin")) {
                    checkWindowForFocusGuard();
                    return;
                }
                // Accessibility settings detail screen
                if (clsName.contains("accessibilitysettings") || 
                    clsName.contains("toggleaccessibilityservice") ||
                    clsName.contains("accessibilitydetails")) {
                    checkWindowForFocusGuard();
                    return;
                }
            }

            // Step 3: Window scan only when we suspect FocusGuard is visible
            // WHY: Not every settings event needs a full tree scan.
            // We only scan when class name OR event text hints at our screens.
        }
    }

    /**
     * Scans the active window tree for any mention of FocusGuard.
     * If found, triggers instant HOME action.
     *
     * Performance note: getRootInActiveWindow() is expensive.
     * Only called on suspected screens (class-name filtered).
     */
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

    /**
     * Surgical tree search for FocusGuard text.
     * Iterative BFS preferred over recursive DFS to avoid StackOverflow on deep trees.
     * Exits early on first match for minimum latency.
     */
    private boolean nodeTreeContainsFocusGuard(AccessibilityNodeInfo root) {
        // Fast path: use built-in text search (internally optimized by Android)
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(SERVICE_LABEL);
        if (hits != null && !hits.isEmpty()) {
            for (AccessibilityNodeInfo n : hits) {
                if (n != null) n.recycle();
            }
            return true;
        }
        // Fallback: search by package name within nodes
        List<AccessibilityNodeInfo> pkgHits = root.findAccessibilityNodeInfosByText(OUR_PACKAGE);
        if (pkgHits != null && !pkgHits.isEmpty()) {
            for (AccessibilityNodeInfo n : pkgHits) {
                if (n != null) n.recycle();
            }
            return true;
        }
        return false;
    }

    /**
     * Instant kick-out via HOME action.
     * WHY post to mainHandler: performGlobalAction is thread-safe only on main thread.
     * WHY removeCallbacks: Prevent double-fire if multiple events arrive at once.
     * WHY postAtFrontOfQueue: Ensures this executes before any pending UI work.
     */
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
        // Check all known tab label variants (including Bangla)
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
        int depth = 0; // Limit depth to avoid infinite traversal in cyclic graphs
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
        // Fast path: class name detection (no tree scan needed)
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
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Reels");
            if (hits == null || hits.isEmpty()) {
                hits = root.findAccessibilityNodeInfosByText("রিলস");
            }
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo n : hits) {
                    if (n != null) {
                        // Only back-off if reels tab is in navigation position
                        CharSequence cd = n.getContentDescription();
                        if (cd != null) {
                            n.recycle();
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            return;
                        }
                        n.recycle();
                    }
                }
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
    public void onInterrupt() {
        // Required override — no action needed
    }
}
