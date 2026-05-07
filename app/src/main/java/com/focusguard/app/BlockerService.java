package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * BlockerService
 *
 * Features:
 *  1. Block WhatsApp Updates/Channels tab
 *  2. Block YouTube Shorts
 *  3. Block Instagram Reels
 *  4. SELF-PROTECTION: immediately eject user from
 *     Settings → Accessibility → FocusGuard Blocker (detail page)
 *
 * NOTE: accessibility_service_config.xml must have NO packageNames
 *       restriction so this service receives events from ALL apps
 *       (including com.android.settings).
 */
public class BlockerService extends AccessibilityService {

    // ── Target apps ───────────────────────────────────────────────────────────
    private static final String WHATSAPP  = "com.whatsapp";
    private static final String YOUTUBE   = "com.google.android.youtube";
    private static final String INSTAGRAM = "com.instagram.android";

    // ── Settings package names (stock AOSP + major OEMs) ─────────────────────
    private static final String[] SETTINGS_PACKAGES = {
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.settings",
        "com.oneplus.settings",
        "com.oppo.settings",
        "com.realme.settings",
        "com.huawei.settings",
        "com.lge.settings"
    };

    /**
     * Activity class names that are SPECIFICALLY the accessibility-service
     * DETAIL page (not the generic list page).
     * Only these trigger self-protection to avoid kicking the user out of
     * the general Accessibility list where our app appears as a list item.
     */
    private static final String[] ACCESSIBILITY_SERVICE_DETAIL_CLASSES = {
        // AOSP / Pixel
        "com.android.settings.accessibility.AccessibilityServiceActivity",
        // Android 12+ AOSP
        "com.android.settings.accessibility.AccessibilityDetailsPreferenceFragment",
        // Samsung
        "com.samsung.android.settings.accessibility.AccessibilityDetailsActivity",
        // Many OEMs wrap sub-pages in SubSettings — handled separately below
        "com.android.settings.SubSettings"
    };

    // Our service label as it appears in Android's Accessibility list
    private static final String SERVICE_LABEL      = "FocusGuard Blocker";
    private static final String SERVICE_LABEL_LOWER = "focusguard";

    // ── Timing ────────────────────────────────────────────────────────────────
    private static final long BACK_COOLDOWN      = 500; // ms — normal blocking
    private static final long SELF_PROT_COOLDOWN = 150; // ms — self-protection (< 0.2 s)

    private SharedPreferences prefs;
    private long lastBackTime     = 0;
    private long lastSelfProtTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
    }

    // =========================================================================
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String packageName = pkg.toString();

        // SELF-PROTECTION has absolute highest priority
        if (isSettingsPackage(packageName)) {
            selfProtect(event);
            return;
        }

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_CLICKED) return;

        if (packageName.equals(WHATSAPP)  && prefs.getBoolean("block_whatsapp",  true)) handleWhatsApp();
        if (packageName.equals(YOUTUBE)   && prefs.getBoolean("block_youtube",   true)) handleYouTubeShorts(event);
        if (packageName.equals(INSTAGRAM) && prefs.getBoolean("block_instagram", true)) handleInstagramReels();
    }

    // =========================================================================
    // SELF-PROTECTION
    // =========================================================================

    /**
     * Called for every event originating in a Settings app.
     *
     * Strategy:
     *   Step 1 – Check if the current Activity class name belongs to the
     *            specific Accessibility SERVICE DETAIL page.
     *            If NOT, bail out immediately (let the user use Settings normally).
     *   Step 2 – We are on a service-detail page. Check event texts (cheapest).
     *   Step 3 – Fall back to a root-node scan (guarantees correctness even when
     *            the event texts are empty).
     *
     * This approach ensures we only kick when the user has actually opened the
     * FocusGuard Blocker detail page, not when it's merely visible as a list item.
     */
    private void selfProtect(AccessibilityEvent event) {
        String className = event.getClassName() != null ? event.getClassName().toString() : "";

        // ── Step 1: Is this the accessibility service DETAIL page? ────────────
        boolean onServiceDetailPage = isServiceDetailClass(className);

        if (!onServiceDetailPage) {
            // Not on a service detail page — leave Settings alone.
            return;
        }

        // ── Step 2: Fast path — event texts / content description ─────────────
        if (eventTextContainsFocusGuard(event)) {
            kickOut();
            return;
        }

        // ── Step 3: Root scan — definitive check ──────────────────────────────
        // TYPE_WINDOW_STATE_CHANGED = new window opened (most reliable)
        // TYPE_WINDOW_CONTENT_CHANGED = content updated (catches delayed renders)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (rootContainsFocusGuard()) {
                kickOut();
            }
        }
    }

    /**
     * Returns true ONLY for class names that represent the accessibility
     * SERVICE DETAIL screen (not the generic service list).
     */
    private boolean isServiceDetailClass(String className) {
        for (String cls : ACCESSIBILITY_SERVICE_DETAIL_CLASSES) {
            if (className.equals(cls)) return true;
        }
        // Additional heuristic: class name ends in "ServiceActivity" or
        // "DetailsActivity" / "DetailActivity" — covers undocumented OEM variants
        String lower = className.toLowerCase();
        return lower.endsWith("serviceactivity") ||
               lower.endsWith("detailsactivity") ||
               lower.endsWith("detailactivity")  ||
               lower.endsWith("servicedetail");
    }

    /** Check event-level texts without touching the node tree (very fast). */
    private boolean eventTextContainsFocusGuard(AccessibilityEvent event) {
        // Window / view title
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null && containsFocusGuard(t.toString())) return true;
            }
        }
        // Content description
        CharSequence cd = event.getContentDescription();
        return cd != null && containsFocusGuard(cd.toString());
    }

    /** Walk the live UI tree looking for "FocusGuard" text anywhere on screen. */
    private boolean rootContainsFocusGuard() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            // findAccessibilityNodeInfosByText is the fastest built-in search
            for (String kw : new String[]{SERVICE_LABEL, "FocusGuard"}) {
                List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(kw);
                if (hits != null && !hits.isEmpty()) {
                    for (AccessibilityNodeInfo n : hits) if (n != null) n.recycle();
                    return true;
                }
            }
            return false;
        } finally {
            root.recycle();
        }
    }

    private boolean containsFocusGuard(String text) {
        return text != null && text.toLowerCase().contains(SERVICE_LABEL_LOWER);
    }

    /** Perform BACK with self-protection cooldown (150 ms). */
    private void kickOut() {
        long now = System.currentTimeMillis();
        if (now - lastSelfProtTime > SELF_PROT_COOLDOWN) {
            lastSelfProtTime = now;
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    private boolean isSettingsPackage(String pkg) {
        for (String s : SETTINGS_PACKAGES) {
            if (s.equals(pkg)) return true;
        }
        return false;
    }

    // =========================================================================
    // WhatsApp Channels blocker
    // =========================================================================
    private void handleWhatsApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (isWhatsAppUpdatesTabActive(root)) goBack();
        } finally { root.recycle(); }
    }

    private boolean isWhatsAppUpdatesTabActive(AccessibilityNodeInfo root) {
        for (String kw : new String[]{"Updates", "Status", "Channels"}) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                if (node.isSelected() || node.isChecked()) { node.recycle(); return true; }
                AccessibilityNodeInfo parent = node.getParent();
                if (parent != null) {
                    if (parent.isSelected() || parent.isChecked()) {
                        parent.recycle(); node.recycle(); return true;
                    }
                    parent.recycle();
                }
                node.recycle();
            }
        }
        for (String kw : new String[]{"Channel info", "Follow", "Following"}) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(kw);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo n : hits) if (n != null) n.recycle();
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // YouTube Shorts blocker
    // =========================================================================
    private void handleYouTubeShorts(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = event.getClassName();
            if (cls != null && cls.toString().toLowerCase().contains("shorts")) {
                goBack(); return;
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (findNodeWithContentDesc(root, "Shorts") != null) { goBack(); return; }
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Shorts");
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null) continue;
                    AccessibilityNodeInfo parent = node.getParent();
                    if (parent != null) {
                        String pc = parent.getClassName() != null ? parent.getClassName().toString() : "";
                        if ((pc.contains("Tab") || pc.contains("Button")) && node.isSelected()) {
                            node.recycle(); parent.recycle(); goBack(); return;
                        }
                        parent.recycle();
                    }
                    node.recycle();
                }
            }
        } finally { root.recycle(); }
    }

    // =========================================================================
    // Instagram Reels blocker
    // =========================================================================
    private void handleInstagramReels() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            AccessibilityNodeInfo r = findNodeWithContentDesc(root, "Reels");
            if (r != null) { r.recycle(); goBack(); return; }
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Reels");
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null) continue;
                    if (node.isSelected() || node.isChecked() || node.isFocused()) {
                        node.recycle(); goBack(); return;
                    }
                    node.recycle();
                }
            }
        } finally { root.recycle(); }
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private void goBack() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime > BACK_COOLDOWN) {
            lastBackTime = now;
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    private AccessibilityNodeInfo findNodeWithContentDesc(AccessibilityNodeInfo node, String desc) {
        if (node == null) return null;
        if (node.getContentDescription() != null &&
            node.getContentDescription().toString().contains(desc)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = findNodeWithContentDesc(child, desc);
            if (result != null) {
                if (!result.equals(child)) child.recycle();
                return result;
            }
            child.recycle();
        }
        return null;
    }

    @Override
    public void onInterrupt() {}
}
