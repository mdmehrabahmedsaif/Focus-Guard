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
    private static final long BACK_COOLDOWN      = 500; // ms — normal blocking
    private static final long SELF_PROT_COOLDOWN = 250; // ms — slightly longer to allow Toast visibility

    private SharedPreferences prefs;
    private long lastBackTime     = 0;
    private long lastSelfProtTime = 0;
    private Toast lastToast;

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

        // SELF-PROTECTION:
        // Trigger if either Accessibility or Device Admin protection is ON.
        boolean blockAcc = prefs.getBoolean("block_accessibility", false);
        boolean blockAdm = prefs.getBoolean("block_device_admin", false);
        if ((blockAcc || blockAdm) && !packageName.equals(getPackageName())) {
            selfProtect(event);
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
     * Called for every event from a Settings app (when block_accessibility pref is ON).
     *
     * 3-event strategy for maximum reliability on all Android versions / OEMs:
     *
     *  A) TYPE_VIEW_CLICKED   → User just tapped "FocusGuard Blocker" in the list.
     *                           Kick IMMEDIATELY — detail page hasn't loaded yet.
     *                           Fastest possible response.
     *
     *  B) TYPE_WINDOW_STATE_CHANGED → New sc    /**
     * 3-event strategy for maximum reliability on Samsung and others:
     *
     *  A) TYPE_VIEW_CLICKED   → User tapped the name in the list.
     *                           We match by the LABEL here.
     *
     *  B) WINDOW_STATE/CONTENT → User is on the detail page.
     *                           We match by the UNIQUE DESCRIPTION here.
     */
    private void selfProtect(AccessibilityEvent event) {
        // 1. Get current package name safely
        CharSequence eventPkg = event.getPackageName();
        String packageName = (eventPkg != null) ? eventPkg.toString().toLowerCase() : "";
        String myPkg = getPackageName().toLowerCase();

        // 2. EXPLICIT BYPASS: Never block our own app's UI
        if (packageName.equals(myPkg)) {
            return;
        }

        // 3. LAUNCHER BYPASS: Allow opening the app from Home Screen.
        // We only bypass if we are sure it's a launcher (to avoid bypassing settings).
        boolean isLauncher = packageName.contains("launcher") || 
                             packageName.contains("trebuchet") || 
                             packageName.equals("android"); // 'android' is often used for home clicks

        // 4. SETTINGS CHECK: Settings and other system apps MUST be protected.
        // If it's NOT a launcher, we proceed with the protection checks.
        if (isLauncher) {
            // Special Case: If it's the 'android' package, we only bypass if it doesn't look like a deactivation page
            if (packageName.equals("android") && (rootIsAdminDeactivationPage() || rootContainsDescription())) {
                // Proceed to kick out even if pkg is 'android'
            } else {
                return; 
            }
        }

        int type = event.getEventType();

        // ── A: CLICK-BASED PROTECTION (The primary trigger) ──────────────────
        // This fires the MOMENT you tap on "FocusGuard" in any list.
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            if (eventTextContainsFocusGuardKeyword(event)) {
                kickOut();
                return;
            }
        }

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
                String[] deactKeywords = {"Deactivate", "নিষ্ক্রিয়", "Cancel"}; 
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

    /** Launches the BlockActivity to show a "Window" and also kicks to Home screen. */
    private void kickOut() {
        long now = System.currentTimeMillis();
        if (now - lastSelfProtTime > SELF_PROT_COOLDOWN) {
            lastSelfProtTime = now;
            
            // 1. Show the "Blocking Window" Activity
            try {
                Intent intent = new Intent(this, BlockActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            } catch (Exception e) {
                // Fallback to back action if activity fails to start
                performGlobalAction(GLOBAL_ACTION_BACK);
            }

            // 2. Also perform HOME action for extra punch
            performGlobalAction(GLOBAL_ACTION_HOME);
            
            // 3. Show a feedback toast
            if (lastToast != null) lastToast.cancel();
            lastToast = Toast.makeText(this, "🛡️ FocusGuard Protected", Toast.LENGTH_SHORT);
            lastToast.show();
        }
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
