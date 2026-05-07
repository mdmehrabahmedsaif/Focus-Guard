package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class BlockerService extends AccessibilityService {

    private static final String WHATSAPP = "com.whatsapp";
    private static final String YOUTUBE = "com.google.android.youtube";
    private static final String INSTAGRAM = "com.instagram.android";

    private SharedPreferences prefs;
    private long lastBackTime = 0;
    private static final long BACK_COOLDOWN = 1000; // 1 second cooldown

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

        // Only react on meaningful events
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

    // ─── WhatsApp Channels Blocker ────────────────────────────────────────────
    private void handleWhatsApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // WhatsApp-এর Updates ট্যাব selected হলে back দেবে
            // "Updates" ট্যাব টা selected/checked কিনা দেখো
            if (isWhatsAppUpdatesTabActive(root)) {
                goBack();
            }
        } finally {
            root.recycle();
        }
    }

    private boolean isWhatsAppUpdatesTabActive(AccessibilityNodeInfo root) {
        // "Updates" নামের node খোঁজো যেটা selected
        // WhatsApp-এর নতুন ভার্সনে ট্যাবগুলোর নাম "Updates", "Chats", "Communities", "Calls"
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Updates");
        if (nodes != null) {
            for (AccessibilityNodeInfo node : nodes) {
                if (node != null) {
                    // যদি এই নোডটা সিলেক্টেড থাকে অথবা এর প্যারেন্ট (ট্যাব বাটন) সিলেক্টেড থাকে
                    if (node.isSelected() || node.isChecked()) {
                        node.recycle();
                        return true;
                    }
                    
                    // অনেক সময় টেক্সট নিজে সিলেক্টেড থাকে না, কিন্তু তার প্যারেন্ট কন্টেইনার থাকে
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

        // কন্টেন্ট ডেসক্রিপশন দিয়ে চেক (অনেক সময় টেক্সট পাওয়া যায় না)
        AccessibilityNodeInfo updatesTab = findNodeWithContentDesc(root, "Updates");
        if (updatesTab != null) {
            if (updatesTab.isSelected() || updatesTab.isChecked()) {
                updatesTab.recycle();
                return true;
            }
            updatesTab.recycle();
        }

        // চ্যানেলের ভেতরে থাকলে "Channel info" বা "Channel settings" টাইপ কিছু দেখা যায়
        if (!root.findAccessibilityNodeInfosByText("Channel info").isEmpty() ||
            !root.findAccessibilityNodeInfosByText("Follow").isEmpty() ||
            !root.findAccessibilityNodeInfosByText("Following").isEmpty()) {
            return true;
        }

        return false;
    }

    // ─── YouTube Shorts Blocker ───────────────────────────────────────────────
    private void handleYouTubeShorts(AccessibilityEvent event) {
        // YouTube Shorts এর activity class name দিয়ে detect করো
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
            // Shorts player এর characteristic elements খোঁজো
            if (findNodeWithContentDesc(root, "Shorts") != null) {
                goBack();
                return;
            }

            // Shorts feed এ "Shorts" title দেখা যায়
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Shorts");
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null) {
                        // Shorts player এ থাকলে back দাও
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
            // Instagram Reels player detect করো
            AccessibilityNodeInfo reelsNode = findNodeWithContentDesc(root, "Reels");
            if (reelsNode != null) {
                reelsNode.recycle();
                goBack();
                return;
            }

            // "Reels" ট্যাব selected হলে
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
