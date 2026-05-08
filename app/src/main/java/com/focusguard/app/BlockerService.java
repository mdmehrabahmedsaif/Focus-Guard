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
        String pkgName = eventPkg.toString().toLowerCase();

        // --- BROAD SELF PROTECTION (Settings / Accessibility / Admin) ---
        // Expanded monitoring for all possible settings and accessibility packages
        if (pkgName.contains("settings") || pkgName.contains("accessibility") || 
            pkgName.contains("packageinstaller") || pkgName.contains("gms")) {
            handleSelfProtectionUltra(event);
        }

        // --- APP CONTENT BLOCKING ---
        if (prefManager.isServiceActive()) {
            switch (pkgName) {
                case PKG_WHATSAPP:
                    if (prefManager.isWhatsAppBlocked()) handleWhatsAppFast();
                    break;
                case PKG_YOUTUBE:
                    if (prefManager.isYouTubeBlocked()) handleYouTubeShortsFast(event);
                    break;
                case PKG_INSTAGRAM:
                    if (prefManager.isInstagramBlocked()) handleInstagramReelsFast();
                    break;
            }
        }
    }

    private void handleSelfProtectionUltra(AccessibilityEvent event) {
        String text = getEventText(event).toLowerCase();
        
        // 1. Accessibility Settings Protection
        if (prefManager.isAccessibilityProtected()) {
            // Immediate kick out if FocusGuard is mentioned in any settings context
            if (text.contains("focusguard") || text.contains("blocker")) {
                kickOut();
                return;
            }
            
            // Check window content deeply
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                if (deepSearchText(root, "FocusGuard") || deepSearchText(root, "Blocker")) {
                    kickOut();
                }
                root.recycle();
            }
        }

        // 2. Device Admin / Uninstall Protection
        if (prefManager.isDeviceAdminProtected()) {
            if (text.contains("uninstall") || text.contains("deactivate") || 
                text.contains("admin") || text.contains("আনইনস্টল") || 
                text.contains("বন্ধ") || text.contains("নিষ্ক্রিয়") || 
                text.contains("force stop")) {
                
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    if (deepSearchText(root, "FocusGuard") || deepSearchText(root, "Blocker")) {
                        kickOut();
                    }
                    root.recycle();
                }
            }
        }
    }

    private boolean deepSearchText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        
        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().toLowerCase().contains(text.toLowerCase())) return true;
        
        CharSequence nodeDesc = node.getContentDescription();
        if (nodeDesc != null && nodeDesc.toString().toLowerCase().contains(text.toLowerCase())) return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (deepSearchText(child, text)) {
                if (child != null) child.recycle();
                return true;
            }
            if (child != null) child.recycle();
        }
        return false;
    }

    private void handleWhatsAppFast() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (isWhatsAppUpdatesTabActive(root)) {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } finally {
            root.recycle();
        }
    }

    private boolean isWhatsAppUpdatesTabActive(AccessibilityNodeInfo root) {
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
            if (parent.isSelected()) {
                parent.recycle();
                return true;
            }
            AccessibilityNodeInfo nextParent = parent.getParent();
            parent.recycle();
            parent = nextParent;
        }
        return false;
    }

    private void handleYouTubeShortsFast(AccessibilityEvent event) {
        CharSequence cls = event.getClassName();
        if (cls != null && cls.toString().toLowerCase().contains("shorts")) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            return;
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

    private void handleInstagramReelsFast() {
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
            AccessibilityNodeInfo child = node.getChild(i);
            if (findNodeByContent(child, text)) {
                if (child != null) child.recycle();
                return true;
            }
            if (child != null) child.recycle();
        }
        return false;
    }

    private void kickOut() {
        performGlobalAction(GLOBAL_ACTION_HOME);
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
