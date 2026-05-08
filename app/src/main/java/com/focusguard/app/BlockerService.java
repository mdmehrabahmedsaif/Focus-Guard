package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class BlockerService extends AccessibilityService {

    private static final String PKG_WHATSAPP  = "com.whatsapp";
    private static final String PKG_YOUTUBE   = "com.google.android.youtube";
    private static final String PKG_INSTAGRAM = "com.instagram.android";
    private static final String PKG_SETTINGS  = "com.android.settings";

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
        if (prefManager == null) prefManager = new PreferenceManager(this);
        
        CharSequence eventPkg = event.getPackageName();
        if (eventPkg == null) return;
        String pkgName = eventPkg.toString();

        // --- CORE SELF PROTECTION (Always active if toggled) ---
        if (pkgName.contains("settings") || pkgName.contains("packageinstaller")) {
            handleSelfProtection(event);
        }

        // --- APP CONTENT BLOCKING ---
        if (prefManager.isServiceActive()) {
            switch (pkgName) {
                case PKG_WHATSAPP:
                    if (prefManager.isWhatsAppBlocked()) handleWhatsApp();
                    break;
                case PKG_YOUTUBE:
                    if (prefManager.isYouTubeBlocked()) handleYouTubeShorts(event);
                    break;
                case PKG_INSTAGRAM:
                    if (prefManager.isInstagramBlocked()) handleInstagramReels();
                    break;
            }
        }
    }

    private void handleSelfProtection(AccessibilityEvent event) {
        String text = getEventText(event).toLowerCase();

        // 1. Accessibility Settings Protection
        if (prefManager.isAccessibilityProtected()) {
            if (text.contains("focusguard") || text.contains("blocker")) {
                kickOut();
            }
        }

        // 2. Device Admin / Uninstall Protection
        if (prefManager.isDeviceAdminProtected()) {
            if (text.contains("uninstall") || text.contains("deactivate") || 
                text.contains("admin") || text.contains("আনইনস্টল") || text.contains("বন্ধ")) {
                
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    if (rootContainsText(root, "FocusGuard") || rootContainsText(root, "Blocker")) {
                        kickOut();
                    }
                    root.recycle();
                }
            }
        }
    }

    private void handleWhatsApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // Improved detection for "Updates" / "Channels" tab
            if (isWhatsAppUpdatesTabActive(root)) {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } finally {
            root.recycle();
        }
    }

    private boolean isWhatsAppUpdatesTabActive(AccessibilityNodeInfo root) {
        // Deep search for selected "Updates" tab
        String[] keywords = {"Updates", "Status", "Channels", "আপডেট", "স্ট্যাটাস", "চ্যানেল"};
        for (String kw : keywords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null) {
                        if (node.isSelected() || node.isFocused() || isParentSelected(node)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isParentSelected(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isSelected()) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private void handleYouTubeShorts(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = event.getClassName();
            if (cls != null && cls.toString().toLowerCase().contains("shorts")) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                return;
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (findNodeByContent(root, "Shorts") || findNodeByContent(root, "শর্টস")) {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } finally {
            root.recycle();
        }
    }

    private void handleInstagramReels() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (findNodeByContent(root, "Reels") || findNodeByContent(root, "রিলস")) {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } finally {
            root.recycle();
        }
    }

    private boolean findNodeByContent(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().equalsIgnoreCase(text)) return true;
        
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findNodeByContent(node.getChild(i), text)) return true;
        }
        return false;
    }

    private boolean rootContainsText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(text);
        return hits != null && !hits.isEmpty();
    }

    private void kickOut() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private String getEventText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence t : event.getText()) if (t != null) sb.append(t).append(" ");
        }
        if (event.getContentDescription() != null) sb.append(event.getContentDescription());
        return sb.toString();
    }

    @Override public void onInterrupt() {}
}
