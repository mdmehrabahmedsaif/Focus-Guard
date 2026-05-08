package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
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
        String pkgName = eventPkg.toString();

        // --- FAST-PATH SELF PROTECTION (Blocking within 0.1s) ---
        if (pkgName.contains("settings") || pkgName.contains("packageinstaller") || pkgName.contains("gms")) {
            handleSelfProtectionFast(event);
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

    private void handleSelfProtectionFast(AccessibilityEvent event) {
        // Immediate check on text content of the event
        String text = getEventText(event).toLowerCase();
        
        boolean needsProtection = prefManager.isAccessibilityProtected() || prefManager.isDeviceAdminProtected();
        if (!needsProtection) return;

        // Detect click or focus on "FocusGuard" related nodes immediately
        if (text.contains("focusguard") || text.contains("blocker")) {
            kickOut();
            return;
        }

        // Deep window check for specific keywords
        if (text.contains("uninstall") || text.contains("deactivate") || 
            text.contains("admin") || text.contains("accessibility") ||
            text.contains("আনইনস্টল") || text.contains("বন্ধ") || text.contains("নিষ্ক্রিয়")) {
            
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                if (deepSearchText(root, "FocusGuard") || deepSearchText(root, "Blocker")) {
                    kickOut();
                }
                root.recycle();
            }
        }
    }

    private void handleWhatsAppFast() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // Optimized: Immediate exit if "Updates" node is found selected
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
                        // Priority check: Is the dangerous tab actually selected?
                        if (node.isSelected() || node.isFocused() || isParentSelected(node)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void handleYouTubeShortsFast(AccessibilityEvent event) {
        // Fast class-name detection
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

    private boolean deepSearchText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().toLowerCase().contains(text.toLowerCase())) return true;
        
        for (int i = 0; i < node.getChildCount(); i++) {
            if (deepSearchText(node.getChild(i), text)) return true;
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

    private boolean findNodeByContent(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().equalsIgnoreCase(text)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findNodeByContent(node.getChild(i), text)) return true;
        }
        return false;
    }

    private void kickOut() {
        // Multiple home actions to ensure instant exit
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
