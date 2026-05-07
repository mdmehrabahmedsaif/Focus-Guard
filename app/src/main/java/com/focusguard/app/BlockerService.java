package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class BlockerService extends AccessibilityService {

    private static final String WHATSAPP   = "com.whatsapp";
    private static final String YOUTUBE    = "com.google.android.youtube";
    private static final String INSTAGRAM  = "com.instagram.android";

    // Android Settings packages (covers stock + major OEMs)
    private static final String[] SETTINGS_PACKAGES = {
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.settings",
        "com.oneplus.settings",
        "com.oppo.settings",
        "com.realme.settings",
        "com.huawei.settings"
    };

    // The exact label shown in Accessibility list = "FocusGuard Blocker"
    // We check for both parts individually to be resilient
    private static final String[] SELF_KEYWORDS = {
        "FocusGuard Blocker",
        "FocusGuard",
        "focusguard"
    };

    // Accessibility service detail Activity class names (AOSP + common OEMs)
    private static final String[] ACCESSIBILITY_DETAIL_CLASSES = {
        "com.android.settings.accessibility.AccessibilityServiceActivity",
        "com.android.settings.SubSettings",
        "com.android.settings.Settings$AccessibilityServiceDetailsActivity",
        "com.samsung.android.settings.accessibility.AccessibilityDetailsActivity"
    };

    private SharedPreferences prefs;
    private final Handler     handler         = new Handler(Looper.getMainLooper());

    private long lastBackTime     = 0;
    private long lastSelfProtTime = 0;

    private static final long BACK_COOLDOWN      = 500; // 0.5 s — normal blocking
    private static final long SELF_PROT_COOLDOWN = 150; // 0.15 s — self-protection

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String packageName = pkg.toString();

        // ── SELF-PROTECTION (highest priority, fastest path) ─────────────────
        if (isSettingsPackage(packageName)) {
            selfProtect(event);
            return; // Never do other processing inside Settings
        }

        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return;
        }

        if (packageName.equals(WHATSAPP) && prefs.getBoolean("block_whatsapp", true)) {
            handleWhatsApp();
        }
        if (packageName.equals(YOUTUBE) && prefs.getBoolean("block_youtube", true)) {
            handleYouTubeShorts(event);
        }
        if (packageName.equals(INSTAGRAM) && prefs.getBoolean("block_instagram", true)) {
            handleInstagramReels();
        }
    }

    // ─── Self-Protection Core ─────────────────────────────────────────────────
    /**
     * Called whenever any Settings app fires an accessibility event.
     * Strategy (fastest-to-slowest checks):
     *   1. Check event class name for known AccessibilityServiceActivity classes
     *   2. Check event text list for "FocusGuard" keywords
     *   3. Scan root node tree (only on window-change events)
     */
    private void selfProtect(AccessibilityEvent event) {

        // 1. Class-name fast path — no node traversal needed
        CharSequence className = event.getClassName();
        if (className != null && isAccessibilityDetailPage(className.toString())) {
            // On the accessibility detail page — now check if it's OUR service
            // Event texts usually contain the service name on this screen
            if (eventContainsSelfKeyword(event)) {
                kickOut();
                return;
            }
            // Even without text match, scan the root immediately
            kickOutIfRootContainsSelf();
            return;
        }

        // 2. Event-text fast path
        if (eventContainsSelfKeyword(event)) {
            kickOut();
            return;
        }

        // 3. Root-node scan — only on window-change events to avoid overhead
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            kickOutIfRootContainsSelf();
        }
    }

    private boolean isAccessibilityDetailPage(String className) {
        for (String cls : ACCESSIBILITY_DETAIL_CLASSES) {
            if (className.equals(cls)) return true;
        }
        // Generic fallback: any Settings activity that has "Accessibility" in name
        return className.toLowerCase().contains("accessibility");
    }

    private boolean eventContainsSelfKeyword(AccessibilityEvent event) {
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null && containsSelfKeyword(t.toString())) return true;
            }
        }
        CharSequence cd = event.getContentDescription();
        if (cd != null && containsSelfKeyword(cd.toString())) return true;
        return false;
    }

    private void kickOutIfRootContainsSelf() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (rootContainsSelfKeyword(root)) {
                kickOut();
            }
        } finally {
            root.recycle();
        }
    }

    /** Searches UI tree breadth-first for any node whose text/cd contains our keywords */
    private boolean rootContainsSelfKeyword(AccessibilityNodeInfo root) {
        // Direct text search (fastest)
        for (String kw : SELF_KEYWORDS) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(kw);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo n : hits) { if (n != null) n.recycle(); }
                return true;
            }
        }
        return false;
    }

    private boolean containsSelfKeyword(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("focusguard");
    }

    /** Perform GLOBAL_ACTION_BACK with a 150 ms cooldown */
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

    // ─── WhatsApp Channels Blocker ────────────────────────────────────────────
    private void handleWhatsApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (isWhatsAppUpdatesTabActive(root)) goBack();
        } finally {
            root.recycle();
        }
    }

    private boolean isWhatsAppUpdatesTabActive(AccessibilityNodeInfo root) {
        for (String kw : new String[]{"Updates", "Status", "Channels"}) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes != null) {
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
        }
        for (String kw : new String[]{"Channel info", "Follow", "Following"}) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(kw);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo n : hits) { if (n != null) n.recycle(); }
                return true;
            }
        }
        return false;
    }

    // ─── YouTube Shorts Blocker ───────────────────────────────────────────────
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

    // ─── Instagram Reels Blocker ──────────────────────────────────────────────
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

    // ─── Helper Methods ───────────────────────────────────────────────────────
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
