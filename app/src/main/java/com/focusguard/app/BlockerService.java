package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class BlockerService extends AccessibilityService {

    private static final String WHATSAPP   = "com.whatsapp";
    private static final String YOUTUBE    = "com.google.android.youtube";
    private static final String INSTAGRAM  = "com.instagram.android";

    // Android Settings packages (varies by OEM)
    private static final String[] SETTINGS_PACKAGES = {
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.settings",
        "com.oneplus.settings",
        "com.oppo.settings",
        "com.realme.settings"
    };

    // Our own package name — used to detect when user is viewing FocusGuard's accessibility page
    private static final String OUR_PACKAGE = "com.focusguard.app";

    private SharedPreferences prefs;

    private long lastBackTime     = 0;
    private long lastSelfProtTime = 0;

    private static final long BACK_COOLDOWN      = 500;  // 0.5s for normal blocking
    private static final long SELF_PROT_COOLDOWN = 150;  // 0.15s — ultra-fast self-protection

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

        int eventType = event.getEventType();

        // ── Self-Protection: react to ALL event types for speed ──────────────
        if (isSettingsPackage(packageName)) {
            handleSelfProtection(event);
            return; // Don't do further processing inside Settings
        }

        // Only react on meaningful UI events for other apps
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

    // ─── Self-Protection: Block access to FocusGuard Accessibility Settings ──
    private void handleSelfProtection(AccessibilityEvent event) {
        // Fast-path: check event text first (cheapest check)
        List<CharSequence> eventTexts = event.getText();
        if (eventTexts != null) {
            for (CharSequence text : eventTexts) {
                if (text != null && isFocusGuardRelated(text.toString())) {
                    goBackFast();
                    return;
                }
            }
        }

        // Check content description of the event source
        CharSequence contentDesc = event.getContentDescription();
        if (contentDesc != null && isFocusGuardRelated(contentDesc.toString())) {
            goBackFast();
            return;
        }

        // Deeper check: scan the root node for FocusGuard text
        // Only do this on window-change events to avoid constant scanning
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                if (isViewingFocusGuardAccessibility(root)) {
                    goBackFast();
                }
            } finally {
                root.recycle();
            }
        }
    }

    private boolean isFocusGuardRelated(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("focusguard") ||
               lower.contains("focus guard");
    }

    private boolean isViewingFocusGuardAccessibility(AccessibilityNodeInfo root) {
        // Look for "FocusGuard" text anywhere on the screen
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("FocusGuard");
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo n : nodes) {
                if (n != null) n.recycle();
            }
            return true;
        }

        // Also check content descriptions recursively
        return containsFocusGuardNode(root);
    }

    private boolean containsFocusGuardNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence cd = node.getContentDescription();
        if (cd != null && isFocusGuardRelated(cd.toString())) return true;
        CharSequence txt = node.getText();
        if (txt != null && isFocusGuardRelated(txt.toString())) return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean found = containsFocusGuardNode(child);
            child.recycle();
            if (found) return true;
        }
        return false;
    }

    // Ultra-fast back for self-protection (150ms cooldown)
    private void goBackFast() {
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
            if (isWhatsAppUpdatesTabActive(root)) {
                goBack();
            }
        } finally {
            root.recycle();
        }
    }

    private boolean isWhatsAppUpdatesTabActive(AccessibilityNodeInfo root) {
        String[] keywords = {"Updates", "Status", "Channels"};
        for (String kw : keywords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null) {
                        if (node.isSelected() || node.isChecked()) {
                            node.recycle();
                            return true;
                        }
                        AccessibilityNodeInfo parent = node.getParent();
                        if (parent != null) {
                            if (parent.isSelected() || parent.isChecked()) {
                                parent.recycle();
                                node.recycle();
                                return true;
                            }
                            parent.recycle();
                        }
                        node.recycle();
                    }
                }
            }
        }

        // Inside a channel detection
        String[] channelKeywords = {"Channel info", "Follow", "Following"};
        for (String kw : channelKeywords) {
            if (!root.findAccessibilityNodeInfosByText(kw).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ─── YouTube Shorts Blocker ───────────────────────────────────────────────
    private void handleYouTubeShorts(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence className = event.getClassName();
            if (className != null) {
                String cls = className.toString().toLowerCase();
                if (cls.contains("shorts") || cls.contains("reel")) {
                    goBack();
                    return;
                }
            }
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            if (findNodeWithContentDesc(root, "Shorts") != null) {
                goBack();
                return;
            }

            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Shorts");
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null) {
                        AccessibilityNodeInfo parent = node.getParent();
                        if (parent != null) {
                            String parentClass = parent.getClassName() != null ?
                                parent.getClassName().toString() : "";
                            if (parentClass.contains("Tab") || parentClass.contains("Button")) {
                                if (node.isSelected()) {
                                    node.recycle();
                                    parent.recycle();
                                    goBack();
                                    return;
                                }
                            }
                            parent.recycle();
                        }
                        node.recycle();
                    }
                }
            }
        } finally {
            root.recycle();
        }
    }

    // ─── Instagram Reels Blocker ──────────────────────────────────────────────
    private void handleInstagramReels() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            AccessibilityNodeInfo reelsNode = findNodeWithContentDesc(root, "Reels");
            if (reelsNode != null) {
                reelsNode.recycle();
                goBack();
                return;
            }

            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Reels");
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null) {
                        if (node.isSelected() || node.isChecked() || node.isFocused()) {
                            node.recycle();
                            goBack();
                            return;
                        }
                        node.recycle();
                    }
                }
            }
        } finally {
            root.recycle();
        }
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
            node.getContentDescription().toString().contains(desc)) {
            return node;
        }
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
