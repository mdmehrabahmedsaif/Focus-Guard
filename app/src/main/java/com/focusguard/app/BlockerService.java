package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;
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



    // Our service label as it appears in Android's Accessibility list
    private static final String SERVICE_LABEL       = "FocusGuard Blocker";
    private static final String SERVICE_LABEL_LOWER = "focusguard blocker";

    // ── Timing ────────────────────────────────────────────────────────────────
    private static final long BACK_COOLDOWN      = 50;  // ms — ULTRA FAST (Less than 0.1s)
    private static final long SELF_PROT_COOLDOWN = 10;  // ms — ULTRA FAST

    private SharedPreferences prefs;
    private long lastBackTime     = 0;
    private long lastSelfProtTime = 0;
    private Toast lastToast;

    private static BlockerService instance;

    public static BlockerService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    /**
     * Programmatically disables the accessibility service (Android 7.0+ only).
     */
    public void disableService() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            disableSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_DISABLE_SELF".equals(intent.getAction())) {
            // Android 7.0+ (API 24) support for actual system disabling
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                disableSelf();
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    // =========================================================================
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // 🛑 ABSOLUTE BYPASS: If this is our app, STOP IMMEDIATELY.
        CharSequence pkg = event.getPackageName();
        if (pkg != null && pkg.toString().equalsIgnoreCase(getPackageName())) {
            return;
        }

        // 🚀 EXTREME SPEED TRIGGER: Check protection for other apps
        selfProtect(event);

        // INSTANT STOP CHECK: If user turned off from app, do nothing.
        if (!prefs.getBoolean("is_service_active", true)) {
            return;
        }

        if (pkg == null) return;
        String packageName = pkg.toString();

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
     * Called for every event from a Settings app (when block_accessibility pref is ON).
     *
     * 3-event strategy for maximum reliability on all Android versions / OEMs:
     *
     *  A) TYPE_VIEW_CLICKED   → User just tapped "FocusGuard Blocker" in the list.
     *                           Kick IMMEDIATELY — detail page hasn't loaded yet.
     *                           Fastest possible response.
     *
     *  A) TYPE_VIEW_CLICKED   → User tapped the name in the list.
     *                           We match by the LABEL here.
     *
     *  B) WINDOW_STATE/CONTENT → User is on the detail page.
     *                           We match by the UNIQUE DESCRIPTION here.
     */
    private void selfProtect(AccessibilityEvent event) {
        // 0. QUICK EXIT: Only run if any protection is enabled
        boolean blockAcc = prefs.getBoolean("block_accessibility", false);
        boolean blockAdm = prefs.getBoolean("block_device_admin", false);
        if (!blockAcc && !blockAdm) return;

        // 1. BYPASS CHECK: Never block if the active window belongs to our app!
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try {
                CharSequence rootPkg = root.getPackageName();
                if (rootPkg != null && rootPkg.toString().equalsIgnoreCase(getPackageName())) {
                    return; 
                }
            } finally {
                root.recycle();
            }
        }

        int type = event.getEventType();
        CharSequence eventPkg = event.getPackageName();
        String pkgName = (eventPkg != null) ? eventPkg.toString().toLowerCase() : "";

        // ── A: CLICK-BASED PROTECTION (Strict) ───────────────────────────────
        // Only block if we are EXPLICITLY in a Settings app.
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            if (pkgName.contains("settings") && eventTextContainsFocusGuardKeyword(event)) {
                kickOut();
                return;
            }
        }

        // ── B: PAGE-BASED PROTECTION (The safety fallback) ───────────────────

        // ── B: PAGE-BASED PROTECTION (The safety fallback) ───────────────────
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        // Accessibility Detail Page
        if (prefs.getBoolean("block_accessibility", false)) {
            if (eventTextContainsDescription(event) || rootContainsDescription()) {
                kickOut();
                return;
            }
        }

        // Device Admin Detail Page
        if (prefs.getBoolean("block_device_admin", false)) {
            if (rootIsAdminDeactivationPage()) {
                kickOut();
            }
        }
    }

    private boolean eventTextContainsFocusGuardKeyword(AccessibilityEvent event) {
        // Double check: Never process if the source belongs to our app
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            try {
                CharSequence sourcePkg = source.getPackageName();
                if (sourcePkg != null && sourcePkg.toString().equals(getPackageName())) return false;
                
                List<AccessibilityNodeInfo> hits = source.findAccessibilityNodeInfosByText("FocusGuard");
                if (hits != null && !hits.isEmpty()) {
                    for (AccessibilityNodeInfo n : hits) n.recycle();
                    return true;
                }
            } finally {
                source.recycle();
            }
        }

        // Look for "focusguard" anywhere in the clicked item's text or content description
        String keyword = "focusguard";
        
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null && t.toString().toLowerCase().contains(keyword)) return true;
            }
        }
        
        CharSequence cd = event.getContentDescription();
        if (cd != null && cd.toString().toLowerCase().contains(keyword)) return true;
        
        return false;
    }

    private boolean rootContainsDescription() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg != null && rootPkg.toString().equals(getPackageName())) return false;

            String desc = "monitors and blocks WhatsApp Channels";
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(desc);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo n : hits) if (n != null) n.recycle();
                return true;
            }
            return false;
        } finally {
            root.recycle();
        }
    }

    /**
     * Target ONLY the deactivation page.
     * We look for our app name AND the 'Deactivate' button together.
     */
    private boolean rootIsAdminDeactivationPage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        
        try {
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg == null || !rootPkg.toString().contains("settings")) return false;

            // Find "FocusGuard" on screen
            List<AccessibilityNodeInfo> nameHits = root.findAccessibilityNodeInfosByText("FocusGuard");
            if (nameHits != null && !nameHits.isEmpty()) {
                // Now check for the "Deactivate" string which is unique to the deactivation screen
                // We use multiple keywords to be safe across different Android versions
                String[] deactKeywords = {"Deactivate", "নিষ্ক্রিয়", "বন্ধ করুন", "Cancel", "বাতিল"}; 
                boolean hasDeactivate = false;
                boolean hasCancel = false;

                for (String kw : deactKeywords) {
                    List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(kw);
                    if (hits != null && !hits.isEmpty()) {
                        if (kw.equals("Cancel")) hasCancel = true;
                        else hasDeactivate = true;
                        for (AccessibilityNodeInfo n : hits) n.recycle();
                    }
                }

                for (AccessibilityNodeInfo n : nameHits) n.recycle();
                
                // If we see our name AND the Deactivate/Cancel pair, it's definitely the deactivation page
                return hasDeactivate && hasCancel;
            }
            return false;
        } finally {
            root.recycle();
        }
    }

    private boolean eventTextContainsDescription(AccessibilityEvent event) {
        String desc = "monitors and blocks WhatsApp Channels";
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null && t.toString().contains(desc)) return true;
            }
        }
        CharSequence cd = event.getContentDescription();
        return cd != null && cd.toString().contains(desc);
    }

    private boolean containsFocusGuard(String text) {
        return text != null && text.toLowerCase().contains("focusguard");
    }

    /** Extreme speed ejection with double punch. */
    private void kickOut() {
        // 1. PERFORM DOUBLE ACTION IMMEDIATELY (Zero Delay)
        performGlobalAction(GLOBAL_ACTION_HOME);
        performGlobalAction(GLOBAL_ACTION_BACK);

        // 2. Show the "Blocking Window" Activity in background
        try {
            Intent intent = new Intent(this, BlockActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception ignored) {}
    }



    // =========================================================================
    // WhatsApp Channels blocker
    // =========================================================================
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
        // Multi-language support: English and Bengali
        String[] keywords = {"Updates", "Status", "Channels", "আপডেট", "স্ট্যাটাস", "চ্যানেল"};
        for (String kw : keywords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
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
        
        String[] infoKeywords = {"Channel info", "Follow", "Following", "চ্যানেলের তথ্য", "অনুসরণ করুন"};
        for (String kw : infoKeywords) {
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
            if (cls != null) {
                String className = cls.toString().toLowerCase();
                if (className.contains("shorts")) {
                    goBack();
                    return;
                }
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // Check content description
            if (findNodeWithContentDesc(root, "Shorts") != null || findNodeWithContentDesc(root, "শর্টস") != null) {
                goBack();
                return;
            }
            
            // Check text
            String[] keywords = {"Shorts", "শর্টস"};
            for (String kw : keywords) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
                if (nodes != null) {
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node == null) continue;
                        AccessibilityNodeInfo parent = node.getParent();
                        if (parent != null) {
                            String pc = parent.getClassName() != null ? parent.getClassName().toString() : "";
                            if ((pc.contains("Tab") || pc.contains("Button")) && node.isSelected()) {
                                node.recycle();
                                parent.recycle();
                                goBack();
                                return;
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

    // =========================================================================
    // Instagram Reels blocker
    // =========================================================================
    private void handleInstagramReels() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // Check Content Description
            AccessibilityNodeInfo r = findNodeWithContentDesc(root, "Reels");
            if (r == null) r = findNodeWithContentDesc(root, "রিলস");
            
            if (r != null) {
                r.recycle();
                goBack();
                return;
            }
            
            // Check Text
            String[] keywords = {"Reels", "রিলস"};
            for (String kw : keywords) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
                if (nodes != null) {
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node == null) continue;
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

    // =========================================================================
    // Helpers
    // =========================================================================
    private void goBack() {
        // Extreme speed: perform back twice
        performGlobalAction(GLOBAL_ACTION_BACK);
        performGlobalAction(GLOBAL_ACTION_BACK);
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
