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
        if (!prefManager.isServiceActive()) return;

        CharSequence eventPkg = event.getPackageName();
        if (eventPkg == null) return;
        String pkgName = eventPkg.toString();

        // 1. SELF PROTECTION (Settings & Uninstall)
        if (pkgName.contains("settings")) {
            handleSelfProtection(event);
            return;
        }

        // 2. APP BLOCKING
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

    private void handleSelfProtection(AccessibilityEvent event) {
        int type = event.getEventType();
        String text = getEventText(event).toLowerCase();

        // A. Accessibility Page Protection
        if (prefManager.isAccessibilityProtected() && text.contains("focusguard blocker")) {
            kickOut();
            return;
        }

        // B. Uninstall Protection (Surgical)
        if (prefManager.isDeviceAdminProtected() && (text.contains("uninstall") || text.contains("আনইনস্টল"))) {
            if (rootContainsAppTitle("FocusGuard")) {
                kickOut();
            }
        }
    }

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
        String[] keywords = {"Updates", "Status", "Channels", "আপডেট", "স্ট্যাটাস", "চ্যানেল"};
        for (String kw : keywords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null && (node.isSelected() || node.isChecked())) {
                        node.recycle();
                        return true;
                    }
                    if (node != null) node.recycle();
                }
            }
        }
        return false;
    }

    private void handleYouTubeShorts(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = event.getClassName();
            if (cls != null && cls.toString().toLowerCase().contains("shorts")) {
                goBack();
                return;
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            if (findNodeWithContentDesc(root, "Shorts") != null || findNodeWithContentDesc(root, "শর্টস") != null) {
                goBack();
            }
        } finally {
            root.recycle();
        }
    }

    private void handleInstagramReels() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            AccessibilityNodeInfo r = findNodeWithContentDesc(root, "Reels");
            if (r == null) r = findNodeWithContentDesc(root, "রিলস");
            if (r != null) {
                r.recycle();
                goBack();
            }
        } finally {
            root.recycle();
        }
    }

    private void kickOut() {
        performGlobalAction(GLOBAL_ACTION_HOME);
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private void goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private String getEventText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence t : event.getText()) if (t != null) sb.append(t).append(" ");
        }
        if (event.getContentDescription() != null) sb.append(event.getContentDescription());
        return sb.toString();
    }

    private boolean rootContainsAppTitle(String title) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(title);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo n : hits) if (n != null) n.recycle();
                return true;
            }
            return false;
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findNodeWithContentDesc(AccessibilityNodeInfo node, String desc) {
        if (node == null) return null;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().equalsIgnoreCase(desc)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNodeWithContentDesc(node.getChild(i), desc);
            if (found != null) return found;
        }
        return null;
    }

    @Override public void onInterrupt() {}
}
