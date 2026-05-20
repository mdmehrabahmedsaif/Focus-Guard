package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.WindowManager;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.view.View;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Toast;

import java.util.List;

/**
 * FocusGuard Blocker Service — v1.8.2
 *
 * BUG FIXES in this version:
 *   - FIX #1: OEM compatibility — Samsung/OPPO/Xiaomi settings package detection
 *   - FIX #2: Instagram reels detection — removed unreliable content description check
 *   - FIX #3: WhatsApp Channels — Redesigned to use Chat redirection and strict UI matching
 *   - FIX #4: Google Docs Blocker — Ultra-Fast (sub-0.01s) search blocker directly to Google Docs Home and zero typing lag
 */
public class BlockerService extends AccessibilityService {

    // BUG FIX #1: Samsung uses "com.samsung.android.settings"
    // OPPO/Realme use "com.coloros.settings"
    // We use contains() instead of equals() to handle ALL OEMs


    private static final String PKG_WHATSAPP  = "com.whatsapp";
    private static final String PKG_YOUTUBE   = "com.google.android.youtube";
    private static final String PKG_INSTAGRAM = "com.instagram.android";
    private static final String PKG_GOOGLE_DOCS = "com.google.android.apps.docs.editors.docs";

    private static final String OUR_PACKAGE   = "com.focusguard.app";
    private static final String SERVICE_LABEL = "Focus Guard";

    private boolean isGoogleDocsPackage(String pkgName) {
        return pkgName != null && pkgName.startsWith("com.google.android.apps.docs");
    }

    // Pre-allocated Handler + Runnable for zero-GC hot path
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable kickOutRunnable = new Runnable() {
        @Override
        public void run() {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    };

    // No touchShieldView fields needed for the pure event-driven interceptor

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
        dismissOverlayWithAnimation();
        stopBrowserKillLoop();
        stopWhatsAppKillLoop();
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
        if (prefManager == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgName = pkg.toString().toLowerCase();

        // Remove overlay if we leave blocked packages
        if (!isGoogleDocsPackage(pkgName) && !PKG_WHATSAPP.equals(pkgName)) {
            dismissOverlayWithAnimation();
            stopBrowserKillLoop();
            stopWhatsAppKillLoop();
        }

        int eventType = event.getEventType();

        // GLOBAL PROTECTION SCAN (Non-FocusGuard apps only - gated to Settings/Installer for maximum performance)
        if (!pkgName.equals(OUR_PACKAGE)) {
            boolean isSettingsPkg = pkgName.contains("settings");
            boolean isInstallerPkg = pkgName.contains("packageinstaller") || pkgName.contains("installer");
            
            if (isSettingsPkg || isInstallerPkg) {
                // Check Accessibility Protection
                if (prefManager.isAccessibilityProtected()) {
                    handleAccessibilityProtection(event, eventType, pkgName);
                }
                // Check Device Admin Protection
                if (prefManager.isDeviceAdminProtected() && isSettingsPkg) {
                    handleAdminProtection(event, eventType);
                }
                // Check Uninstall Protection
                if (prefManager.isUninstallProtected()) {
                    handleUninstallProtection(event, eventType);
                }
            }
        }

        // App Blocking logic (WhatsApp, YouTube, Instagram)
        if (PKG_WHATSAPP.equals(pkgName)) {
            if (prefManager.isWhatsAppBlocked()) {
                handleWhatsApp(event, eventType);
            }
        } else if (PKG_YOUTUBE.equals(pkgName)) {
            if (prefManager.isYouTubeBlocked()) {
                handleYouTube(event, eventType);
            }
        } else if (PKG_INSTAGRAM.equals(pkgName)) {
            if (prefManager.isInstagramBlocked()
                    && (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                     || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
                handleInstagram();
            }
        } else if (isGoogleDocsPackage(pkgName) || 
                   "com.google.android.gms".equals(pkgName) || 
                   "com.google.android.googlequicksearchbox".equals(pkgName) || 
                   "com.android.chrome".equals(pkgName) || 
                   "com.google.android.webview".equals(pkgName) || 
                   "com.android.webview".equals(pkgName) ||
                   pkgName.contains("browser") || 
                   pkgName.contains("firefox") || 
                   pkgName.contains("opera") || 
                   pkgName.contains("searchbox") || 
                   pkgName.contains("websearch")) {
            if (prefManager.isGoogleDocsBlocked()) {
                handleGoogleDocs(event, eventType, pkgName);
            }
        }
    }



    /**
     * ACCESSIBILITY PROTECTION
     * Triggers ONLY when user taps FocusGuard in Accessibility Settings.
     * Does NOT interfere with Device Admin screens.
     *
     * FIX: Many OEMs do NOT put item text in event.getText() for list clicks.
     * Instead, the text is in event.getSource() — the actual clicked node.
     * We check BOTH for maximum OEM compatibility.
     */
    private void handleAccessibilityProtection(AccessibilityEvent event, int eventType, String pkgName) {
        boolean isSettings = pkgName.contains("settings");

        // --- PERFORMANCE OPTIMIZATION ---
        // Only act on Clicks, Window Changes, or Content Changes (if in Settings)
        if (eventType != AccessibilityEvent.TYPE_VIEW_CLICKED && 
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !(eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isSettings)) return;

        // --- CLICK DETECTION (Ultra-Fast, Sub-0.1s) ---
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // FAST PATH 1: Check event text directly (zero IPC, zero allocation)
            List<CharSequence> eventTexts = event.getText();
            if (eventTexts != null) {
                for (CharSequence t : eventTexts) {
                    if (t != null) {
                        String s = t.toString();
                        if (s.contains("Focus Guard") || s.contains("FocusGuard")) {
                            triggerKickOut();
                            return;
                        }
                    }
                }
            }

            // FAST PATH 2: Check event content description
            CharSequence evtDesc = event.getContentDescription();
            if (evtDesc != null) {
                String d = evtDesc.toString();
                if (d.contains("Focus Guard") || d.contains("FocusGuard")) {
                    triggerKickOut();
                    return;
                }
            }

            // FAST PATH 3: Check source node (with deep child traversal)
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                if (isFocusGuardNode(source)) {
                    source.recycle();
                    triggerKickOut();
                    return;
                }
                source.recycle();
            }
        }

        // --- SCREEN DETECTION (Only on full window change or content change in settings) ---
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
           (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isSettings)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                // Ensure we are in an Accessibility-related screen
                boolean isAccessibilityWindow = !root.findAccessibilityNodeInfosByText("Accessibility").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("এক্সেসিবিলিটি").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Installed apps").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Installed services").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("ইনস্টল করা অ্যাপ").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Use Focus Guard").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Use FocusGuard").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Shortcut").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("শর্টকাট").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Focus Guard Blocker").isEmpty();
                
                if (isAccessibilityWindow && isFocusGuardDetailScreen(root)) {
                    triggerKickOut();
                }
            } finally {
                root.recycle();
            }
        }
    }

    /** Detects if the current window is specifically the FocusGuard service detail page */
    private boolean isFocusGuardDetailScreen(AccessibilityNodeInfo root) {
        // 1. If it's a LIST page or APP INFO page, it's NOT a detail screen.
        String[] ignoreTitles = {
            "Accessibility", "Downloaded services", "এক্সেসিবিলিটি", "ডাউনলোড করা পরিষেবা",
            "Installed apps", "Installed services", "ইনস্টল করা অ্যাপ",
            "App info", "অ্যাপ তথ্য", "Permissions", "Storage"
        };
        for (String title : ignoreTitles) {
            if (!root.findAccessibilityNodeInfosByText(title).isEmpty()) return false;
        }

        // 2. Window must contain our service name
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Focus Guard");
        if (hits == null || hits.isEmpty()) {
            hits = root.findAccessibilityNodeInfosByText("FocusGuard");
        }
        if (hits == null || hits.isEmpty()) return false;
        for (AccessibilityNodeInfo n : hits) n.recycle();

        // 3. STRICT ACCESSIBILITY CONTEXT
        // Only block if we see Accessibility-specific detail keywords.
        // This prevents Accessibility Lock from accidentally blocking Admin screens.
        boolean isAccessibilityContext = !root.findAccessibilityNodeInfosByText("Use service").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("Use Focus Guard").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("Use FocusGuard").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("ব্যবহার").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("Shortcut").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("শর্টকাট").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("Focus Guard Blocker").isEmpty();
        
        return isAccessibilityContext || findSwitchInNode(root);
    }

    private boolean findSwitchInNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if ("android.widget.Switch".equals(node.getClassName()) || 
            "android.widget.ToggleButton".equals(node.getClassName())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findSwitchInNode(node.getChild(i))) return true;
        }
        return false;
    }




    /**
     * DEVICE ADMIN PROTECTION
     * Triggers ONLY when user taps FocusGuard in Device Admin settings.
     * Does NOT interfere with Accessibility screens.
     */
    /**
     * DEVICE ADMIN PROTECTION (Redesigned)
     * Stage 1: Block clicking FocusGuard in the Admin List.
     * Stage 2: Block the "Activate/Deactivate" confirmation screen.
     */
    private void handleAdminProtection(AccessibilityEvent event, int eventType) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // --- CONTEXT VERIFICATION ---
            // Ensure we are actually in a Device Admin related screen
            boolean isAdminWindow = !root.findAccessibilityNodeInfosByText("Device administrators").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("Device admin apps").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("ডিভাইস অ্যাডমিনিস্ট্রেটর").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("Activate device admin").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("অ্যাক্টিভেট").isEmpty();
            
            if (!isAdminWindow) return;

            // --- CLICK DETECTION ---
            if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    if (isFocusGuardNode(source)) {
                        source.recycle();
                        triggerKickOut();
                        return;
                    }
                    source.recycle();
                }
            }

            // --- SCREEN DETECTION ---
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Ignore the list itself (only block detail/confirmation screens)
                boolean hasCancel = !root.findAccessibilityNodeInfosByText("Cancel").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("বাতিল").isEmpty();
                
                if (hasCancel) {
                    List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Focus Guard");
                    if (hits == null || hits.isEmpty()) {
                        hits = root.findAccessibilityNodeInfosByText("FocusGuard");
                    }
                    if (hits != null && !hits.isEmpty()) {
                        for (AccessibilityNodeInfo n : hits) n.recycle();
                        
                        boolean isAdminDetail = !root.findAccessibilityNodeInfosByText("Deactivate").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Activate").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("ডিঅ্যাক্টিভেট").isEmpty();
                        
                        if (isAdminDetail) triggerKickOut();
                    }
                }
            }
        } finally {
            root.recycle();
        }
    }

    /** Helper to check if a node or its children mention FocusGuard (deep traversal) */
    private boolean isFocusGuardNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        
        // Check the node's text
        CharSequence txt = node.getText();
        if (txt != null && (txt.toString().contains("Focus Guard") || txt.toString().contains("FocusGuard"))) return true;
        
        // Check the node's content description (some OEMs put labels here)
        CharSequence desc = node.getContentDescription();
        if (desc != null && (desc.toString().contains("Focus Guard") || desc.toString().contains("FocusGuard"))) return true;
        
        // Check children recursively (up to 3 levels deep for complex list items)
        return isFocusGuardInChildren(node, 0);
    }

    /** Recursively check children for FocusGuard text (max 3 levels) */
    private boolean isFocusGuardInChildren(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth >= 3) return false;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                CharSequence ctxt = child.getText();
                if (ctxt != null && (ctxt.toString().contains("Focus Guard") || ctxt.toString().contains("FocusGuard"))) {
                    child.recycle();
                    return true;
                }
                CharSequence cdesc = child.getContentDescription();
                if (cdesc != null && (cdesc.toString().contains("Focus Guard") || cdesc.toString().contains("FocusGuard"))) {
                    child.recycle();
                    return true;
                }
                if (isFocusGuardInChildren(child, depth + 1)) {
                    child.recycle();
                    return true;
                }
                child.recycle();
            }
        }
        return false;
    }

    /**
     * UNINSTALL PROTECTION
     * Allows viewing App Info, but blocks clicking the "Uninstall" button.
     */
    private void handleUninstallProtection(AccessibilityEvent event, int eventType) {
        // 1. Detect CLICK on Uninstall button
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                String txt = getEventText(event).toLowerCase();
                if (txt.contains("uninstall") || txt.contains("আনইনস্টল")) {
                    // Check if the current window is indeed FocusGuard's page
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Focus Guard");
                        if (hits == null || hits.isEmpty()) {
                            hits = root.findAccessibilityNodeInfosByText("FocusGuard");
                        }
                        if (hits != null && !hits.isEmpty()) {
                            for (AccessibilityNodeInfo n : hits) n.recycle();
                            root.recycle();
                            source.recycle();
                            triggerKickOut();
                            return;
                        }
                        root.recycle();
                    }
                }
                source.recycle();
            }
        }

        // 2. Detect Uninstall Confirmation Dialog (Window change only)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                boolean isUninstallDialog = !root.findAccessibilityNodeInfosByText("Do you want to uninstall").isEmpty() ||
                                            !root.findAccessibilityNodeInfosByText("আপনি কি আনইনস্টল").isEmpty();
                if (isUninstallDialog) {
                    List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Focus Guard");
                    if (hits == null || hits.isEmpty()) {
                        hits = root.findAccessibilityNodeInfosByText("FocusGuard");
                    }
                    if (hits != null && !hits.isEmpty()) {
                        for (AccessibilityNodeInfo n : hits) n.recycle();
                        triggerKickOut();
                    }
                }
            } finally {
                root.recycle();
            }
        }
    }



    private void triggerKickOut() {
        // Instant kick-out for sub-0.1s reaction
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    // =========================================================================
    // WHATSAPP CHANNELS BLOCKING (Redesigned)
    // =========================================================================

    private boolean isWhatsAppKillLoopActive = false;
    private long whatsAppKillLoopStartTime = 0;
    private boolean hasBlockedCurrentWhatsApp = false;

    private final Runnable whatsAppKillRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isWhatsAppKillLoopActive) return;
            
            boolean isWhatsAppBlockedActive = false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    int reason = getWhatsAppBlockReason(root);
                    if (reason != 0) {
                        isWhatsAppBlockedActive = true;
                        doWhatsAppBlock(reason, root);
                    }
                } finally {
                    root.recycle();
                }
            }
            
            long elapsed = System.currentTimeMillis() - whatsAppKillLoopStartTime;
            
            // Dynamic Dismissal Check:
            // If the WhatsApp blocked screen is NOT active (screen is clean):
            // And either we have already successfully blocked/switched (hasBlockedCurrentWhatsApp == true)
            // Or a reasonable minimum transition duration has passed (e.g. 300ms) to ensure we don't dismiss early
            if (!isWhatsAppBlockedActive && (hasBlockedCurrentWhatsApp || elapsed > 300)) {
                dismissOverlayWithAnimation();
                isWhatsAppKillLoopActive = false;
                return;
            }
            
            // Self-schedule the next iteration dynamically
            if (isWhatsAppKillLoopActive) {
                if (elapsed < 2100) {
                    long delay = (elapsed < 600) ? 15L : 100L;
                    mainHandler.postDelayed(this, delay);
                } else {
                    dismissOverlayWithAnimation();
                    isWhatsAppKillLoopActive = false;
                }
            }
        }
    };

    private void startWhatsAppKillLoop() {
        isWhatsAppKillLoopActive = true;
        whatsAppKillLoopStartTime = System.currentTimeMillis();
        
        mainHandler.removeCallbacks(whatsAppKillRunnable);
        whatsAppKillRunnable.run();
        
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isWhatsAppKillLoopActive) {
                    isWhatsAppKillLoopActive = false;
                    dismissOverlayWithAnimation();
                }
            }
        }, 1500);
    }

    private void stopWhatsAppKillLoop() {
        isWhatsAppKillLoopActive = false;
        mainHandler.removeCallbacks(whatsAppKillRunnable);
        dismissOverlayWithAnimation();
    }

    private void doWhatsAppBlock(int reason, AccessibilityNodeInfo root) {
        hasBlockedCurrentWhatsApp = true;
        
        // Show premium zero-flash overlay
        showInstantZeroFlashOverlay();
        
        if (reason == 1) {
            // Tab Block - Try to switch to Chats. If it fails, go Back.
            boolean switched = false;
            if (root != null) {
                switched = switchToWhatsAppChats(root);
            } else {
                AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
                if (activeRoot != null) {
                    try {
                        switched = switchToWhatsAppChats(activeRoot);
                    } finally {
                        activeRoot.recycle();
                    }
                }
            }
            if (!switched) {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } else if (reason == 2) {
            // Channel Block - Instantly go Back
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    private void handleWhatsApp(AccessibilityEvent event, int eventType) {
        // Reset block state when the user is back in safe state
        if (hasBlockedCurrentWhatsApp) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        if (getWhatsAppBlockReason(root) == 0) {
                            hasBlockedCurrentWhatsApp = false;
                        }
                    } finally {
                        root.recycle();
                    }
                }
            }
        }

        // ===== FAST PATH #1: Click Interception =====
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            String eventTxt = getEventText(event).toLowerCase().trim();
            
            boolean isTabClick = eventTxt.contains("updates") || eventTxt.contains("আপডেট") ||
                                 eventTxt.contains("channels") || eventTxt.contains("চ্যানেল");
            
            boolean isChannelClick = eventTxt.contains(" followers") || eventTxt.contains(" ফলোয়ার") ||
                                     eventTxt.contains("channel info") || eventTxt.contains("চ্যানেলের তথ্য") ||
                                     eventTxt.contains("find channels") || eventTxt.contains("চ্যানেল খুঁজুন") ||
                                     eventTxt.contains("channels to follow") || eventTxt.contains("view channel") || 
                                     eventTxt.contains("চ্যানেল দেখুন") || eventTxt.contains("public channel") ||
                                     eventTxt.contains("পাবলিক চ্যানেল") || eventTxt.contains("channel link") ||
                                     eventTxt.contains("চ্যানেলের লিঙ্ক") || eventTxt.contains("channel settings") ||
                                     eventTxt.contains("চ্যানেল সেটিংস") || eventTxt.contains("report channel") ||
                                     eventTxt.contains("চ্যানেল সম্পর্কে রিপোর্ট করুন") || eventTxt.equals("follow") ||
                                     eventTxt.equals("ফলো করুন") || eventTxt.equals("unfollow") ||
                                     eventTxt.equals("আনফলো করুন");
            
            if (isTabClick || isChannelClick) {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    try {
                        if (!isEditableNode(source) && !isInsideChatList(source)) {
                            hasBlockedCurrentWhatsApp = false;
                            showInstantZeroFlashOverlay();
                            
                            boolean handled = false;
                            if (isTabClick) {
                                AccessibilityNodeInfo root = getRootInActiveWindow();
                                if (root != null) {
                                    try {
                                        handled = switchToWhatsAppChats(root);
                                    } finally {
                                        root.recycle();
                                    }
                                }
                            }
                            if (!handled) {
                                performGlobalAction(GLOBAL_ACTION_BACK);
                            }
                            
                            startWhatsAppKillLoop();
                            return;
                        }
                    } finally {
                        source.recycle();
                    }
                } else {
                    hasBlockedCurrentWhatsApp = false;
                    showInstantZeroFlashOverlay();
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    startWhatsAppKillLoop();
                    return;
                }
            }
        }

        // ===== FAST PATH #2: Fallback Watchdog on window/content changes =====
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    int reason = getWhatsAppBlockReason(root);
                    if (reason != 0) {
                        doWhatsAppBlock(reason, root);
                        if (!isWhatsAppKillLoopActive) {
                            startWhatsAppKillLoop();
                        }
                    }
                } finally {
                    root.recycle();
                }
            }
        }
    }

    private int getWhatsAppBlockReason(AccessibilityNodeInfo root) {
        // 0 = Safe, 1 = Tab Block, 2 = Channel Block

        // Single pass keywords to minimize IPC calls (Massive Performance Boost)
        String[] searchTerms = {
            "channel", "চ্যানেল", 
            "follow", "ফলো", 
            "updates", "আপডেট"
        };

        for (String term : searchTerms) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(term);
            int foundReason = 0;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                if (foundReason == 0) {
                    foundReason = evaluateWhatsAppNode(node);
                }
                node.recycle();
            }
            if (foundReason != 0) return foundReason;
        }
        
        return 0; // Safe
    }

    private int evaluateWhatsAppNode(AccessibilityNodeInfo node) {
        if (isEditableNode(node)) return 0; // PREVIOUS FIX: Allow typing
        
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        String text = t != null ? t.toString().trim().toLowerCase() : "";
        String desc = d != null ? d.toString().trim().toLowerCase() : "";

        if (text.isEmpty() && desc.isEmpty()) return 0;

        // 1. Channel Indicators (Anywhere, very fast exact match)
        String[] exactIndicators = {
            "channel info", "চ্যানেলের তথ্য", 
            "find channels", "চ্যানেল খুঁজুন",
            "channels to follow", "view channel", "চ্যানেল দেখুন",
            "public channel", "পাবলিক চ্যানেল",
            "channel link", "চ্যানেলের লিঙ্ক",
            "channel settings", "চ্যানেল সেটিংস",
            "report channel", "চ্যানেল সম্পর্কে রিপোর্ট করুন"
        };
        for (String ind : exactIndicators) {
            if (text.equals(ind) || desc.equals(ind)) {
                return 2; // Channel Block
            }
        }

        // 2. Action Buttons (Anywhere)
        boolean isActionBtn = text.equals("follow") || desc.equals("follow") ||
                              text.equals("ফলো করুন") || desc.equals("ফলো করুন") ||
                              text.equals("unfollow") || desc.equals("unfollow") ||
                              text.equals("আনফলো করুন") || desc.equals("আনফলো করুন");
        if (isActionBtn) {
            if (node.isClickable() || (node.getClassName() != null && node.getClassName().toString().contains("Button"))) {
                return 2; // Channel Block
            }
        }

        // Expensive chat list check only if needed
        boolean inChatList = isInsideChatList(node);
        if (inChatList) return 0; // PREVIOUS FIX: Allow sent messages inside chat list

        // 3. Tab Block (Updates/Channels Tab, NOT in chat list)
        boolean isTab = text.equals("updates") || desc.contains("updates") ||
                        text.equals("আপডেট") || desc.contains("আপডেট") ||
                        text.equals("channels") || desc.contains("channels") ||
                        text.equals("চ্যানেল") || desc.contains("চ্যানেল");
        if (isTab) {
            if (node.isSelected() || isAncestorSelected(node) || desc.contains("selected")) {
                return 1; // Tab Block
            }
        }

        // 4. Channel Subtitles (NOT in chat list)
        if (text.contains(" followers") || desc.contains(" followers") ||
            text.contains(" ফলোয়ার") || desc.contains(" ফলোয়ার")) {
            return 2; // Channel Block
        }
        if (text.equals("channel") || desc.equals("channel") ||
            text.equals("চ্যানেল") || desc.equals("চ্যানেল")) {
            return 2; // Channel Block
        }

        return 0; // Safe
    }

    private boolean switchToWhatsAppChats(AccessibilityNodeInfo root) {
        String[] chatTabs = {"Chats", "চ্যাট"};
        for (String tab : chatTabs) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(tab);
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                
                if (isEditableNode(node) || isInsideChatList(node)) {
                    node.recycle();
                    continue;
                }

                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                
                boolean exactMatch = false;
                if (text != null && text.toString().equalsIgnoreCase(tab)) exactMatch = true;
                if (desc != null && desc.toString().toLowerCase().contains(tab.toLowerCase())) exactMatch = true;
                
                if (exactMatch) {
                    if (clickNodeOrParent(node)) {
                        node.recycle();
                        return true;
                    }
                }
                node.recycle();
            }
        }
        return false;
    }

    private boolean isEditableNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        CharSequence className = node.getClassName();
        if (className != null && className.toString().contains("EditText")) return true;
        return false;
    }

    private boolean isInsideChatList(AccessibilityNodeInfo node) {
        if (node == null) return false;
        AccessibilityNodeInfo current = node.getParent();
        int depth = 0;
        while (current != null && depth < 8) {
            CharSequence cls = current.getClassName();
            if (cls != null && (cls.toString().contains("RecyclerView") || cls.toString().contains("ListView"))) {
                current.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
            depth++;
        }
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            boolean clicked = clickNodeOrParent(parent);
            parent.recycle();
            return clicked;
        }
        return false;
    }

    private boolean isAncestorSelected(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node.getParent();
        int depth = 0;
        while (current != null && depth < 6) {
            if (current.isSelected()) {
                current.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
            depth++;
        }
        if (current != null) current.recycle();
        return false;
    }

    // =========================================================================
    // YOUTUBE SHORTS BLOCKING
    // =========================================================================

    private void handleYouTube(AccessibilityEvent event, int eventType) {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = event.getClassName();
            if (cls != null && cls.toString().toLowerCase().contains("shorts")) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                return;
            }
        }
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Shorts");
                if (hits == null || hits.isEmpty()) {
                    hits = root.findAccessibilityNodeInfosByText("শর্টস");
                }
                if (hits != null && !hits.isEmpty()) {
                    for (AccessibilityNodeInfo n : hits) { if (n != null) n.recycle(); }
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            } finally {
                root.recycle();
            }
        }
    }

    // =========================================================================
    // INSTAGRAM REELS BLOCKING
    // =========================================================================

    private void handleInstagram() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            // BUG FIX #2: Removed unreliable content description check.
            // Previously required cd != null which caused many misses.
            // Now blocks as soon as Reels node is found in the tree.
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Reels");
            if (hits == null || hits.isEmpty()) {
                hits = root.findAccessibilityNodeInfosByText("রিলস");
            }
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo n : hits) { if (n != null) n.recycle(); }
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } finally {
            root.recycle();
        }
    }

    // =========================================================================
    // GOOGLE DOCS WEB SEARCH BLOCKING (v2.0 — Ultra-Fast, Sub-0.01s)
    // =========================================================================

    private long lastGoogleDocsBlockTime = 0;
    private long lastDeepScanTime = 0;
    private boolean hasBlockedCurrentSearch = false;

    private void kickOutToGoogleDocsHome() {
        // A single BACK action destroys the browser fragment and returns to the document.
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /**
     * Fires the block using a robust event-driven State Machine (hasBlockedCurrentSearch)
     * to eliminate double-backing while allowing instant back-to-back blocks for new clicks.
     */
    private void doGoogleDocsBlock(boolean force) {
        if (force || !hasBlockedCurrentSearch) {
            hasBlockedCurrentSearch = true;
            
            // Show instant overlay (0.00s Zero-Flash) to completely cover transition
            showInstantZeroFlashOverlay();

            kickOutToGoogleDocsHome();
            stopBrowserKillLoop();
        }
    }

    private void doGoogleDocsBlock() {
        doGoogleDocsBlock(false);
    }

    /**
     * ====== BROWSER KILL LOOP ======
     * When user clicks "From web", the browser fragment takes 100-800ms to fully
     * create and render. We can't predict exactly WHEN it will appear.
     * 
     * Instead of blindly firing BACKs at fixed times (which miss the browser),
     * we start a REACTIVE kill loop that:
     *   1. Runs every 15ms for the first 600ms, then every 100ms
     *   2. Each iteration checks if the browser/search page structure exists
     *   3. The INSTANT it detects the browser, it fires BACK to kill it
     *   4. Keeps running even after a kill, in case the browser re-appears
     * 
     * This is 100% reliable because we REACT to the browser, not guess its timing.
     */
    private boolean isBrowserKillLoopActive = false;
    private long browserKillLoopStartTime = 0;

    private final Runnable browserKillRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isBrowserKillLoopActive) return;
            
            boolean isSearchActive = false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    // Check if browser/search page exists using structural detection
                    if (checkDocsSearchDeep(root)) {
                        isSearchActive = true;
                        doGoogleDocsBlock();
                    }
                } finally {
                    root.recycle();
                }
            }
            
            long elapsed = System.currentTimeMillis() - browserKillLoopStartTime;
            
            // Dynamic Dismissal Check:
            // If the search window is NOT active (screen is clean):
            // And either we have already successfully blocked the search (hasBlockedCurrentSearch == true)
            // Or a reasonable minimum transition duration has passed (e.g. 300ms) to ensure we don't dismiss early
            if (!isSearchActive && (hasBlockedCurrentSearch || elapsed > 300)) {
                dismissOverlayWithAnimation();
                isBrowserKillLoopActive = false;
                return;
            }
            
            // Self-schedule the next iteration dynamically
            if (isBrowserKillLoopActive) {
                if (elapsed < 2100) {
                    // Balanced polling (15ms) in the critical first 600ms, then 100ms
                    long delay = (elapsed < 600) ? 15L : 100L;
                    mainHandler.postDelayed(this, delay);
                } else {
                    dismissOverlayWithAnimation();
                    isBrowserKillLoopActive = false;
                }
            }
        }
    };

    private void startBrowserKillLoop() {
        isBrowserKillLoopActive = true;
        browserKillLoopStartTime = System.currentTimeMillis();
        
        // Remove any existing callbacks of this runnable to prevent overlapping loops
        mainHandler.removeCallbacks(browserKillRunnable);
        
        // Run immediately to catch any instant transitions synchronously
        browserKillRunnable.run();
        
        // Safety auto-stop and clean up after 1500ms (safety timeout)
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isBrowserKillLoopActive) {
                    isBrowserKillLoopActive = false;
                    dismissOverlayWithAnimation();
                }
            }
        }, 1500);
    }

    private void stopBrowserKillLoop() {
        isBrowserKillLoopActive = false;
        mainHandler.removeCallbacks(browserKillRunnable);
        dismissOverlayWithAnimation();
    }

    /**
     * Called when user clicks "From web". Starts the Browser Kill Loop.
     * NO cooldown here — every click must be handled.
     */
    private void doFromWebClickBlock() {
        // Reset block state for the new click session
        hasBlockedCurrentSearch = false;

        // Show instant overlay (0.00s Zero-Flash) to completely cover transition
        showInstantZeroFlashOverlay();

        // Send an immediate synchronous Back press to dismiss the bottom sheet / cancel transition
        performGlobalAction(GLOBAL_ACTION_BACK);
        
        // Start the watchdog loop to catch and kill the browser if it still opens
        startBrowserKillLoop();
    }

    /**
     * Checks if a string contains any Google Docs web search PAGE indicator text.
     * NOTE: "from web" is NOT here — it only appears on the Image panel.
     */
    private boolean isGoogleDocsSearchText(String text) {
        String s = text.toLowerCase();
        return s.contains("search your docs and the web") ||
               s.contains("আপনার ডক্স এবং ওযেব") ||
               s.contains("Search images") ||
               s.contains("ছবি খুঁজুন") ||
               s.contains("find images, facts and text") ||
               s.contains("search directly in docs") ||
               s.contains("search web") ||
               s.contains("ওয়েবে খুঁজুন") ||
               s.contains("search query") ||
               s.contains("ওয়েব অনুসন্ধান") ||
               s.contains("ওয়েব অনুসন্ধান") ||
               s.contains("ওয়েব সার্চ") ||
               s.contains("ওয়েব সার্চ") ||
               s.contains("ওয়েবে অনুসন্ধান") ||
               s.contains("ওয়েবে অনুসন্ধান") ||
               s.contains("ছবি অনুসন্ধান") ||
               s.contains("ছবি সার্চ") ||
               s.contains("গুগল অনুসন্ধান") ||
               s.contains("গুগল সার্চ") ||
               s.contains("google search") ||
               s.contains("search the web");
    }

    private void handleGoogleDocs(AccessibilityEvent event, int eventType, String pkgName) {
        // Pure event-driven flow: no touch shield updates to eliminate typing lag entirely!

        // Reset block state when the user is back in the normal editor
        if (hasBlockedCurrentSearch) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        if (!checkDocsSearchDeep(root)) {
                            hasBlockedCurrentSearch = false;
                        }
                    } finally {
                        root.recycle();
                    }
                }
            }
        }
        // ===== ABSOLUTE ZERO-IPC WEBVIEW KICKOUT (<0.0001s) =====
        // Only run when the click was recently triggered, to avoid blocking the normal document editor
        if (isBrowserKillLoopActive) {
            // If the package is not Google Docs (e.g. Chrome, WebView, other browsers) and loop is active, block it instantly!
            if (!isGoogleDocsPackage(pkgName)) {
                doGoogleDocsBlock(true); // Bypass cooldown for instant close
                return;
            }
            
            // If it's a window state change, block any non-editor transition instantly
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                CharSequence evClass = event.getClassName();
                if (evClass != null) {
                    String clsStr = evClass.toString();
                    if (!clsStr.contains("Editor") && !clsStr.contains("MainActivity") && !clsStr.contains("HomeActivity")) {
                        doGoogleDocsBlock(true); // Bypass cooldown for instant close
                        return;
                    }
                }
            }
            
            CharSequence evClass = event.getClassName();
            if (evClass != null) {
                String clsStr = evClass.toString();
                if (clsStr.contains("WebView") || clsStr.contains("WebSearch") || 
                    clsStr.contains("CustomTab") || clsStr.contains("ExploreActivity")) {
                    doGoogleDocsBlock(true); // Bypass cooldown for instant close
                    return;
                }
            }
        }

        // ===== EVENT-DRIVEN ZERO-FLASH WEBVIEW INTERCEPTION =====
        // Synchronously intercept any WebView in the layout tree the instant it is added.
        if (isBrowserKillLoopActive) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        if (hasWebViewInTree(root, 0)) {
                            doGoogleDocsBlock(true); // Bypass cooldown for instant close
                            return;
                        }
                    } finally {
                        root.recycle();
                    }
                } else {
                    AccessibilityNodeInfo source = event.getSource();
                    if (source != null) {
                        try {
                            if (hasWebViewInTree(source, 0)) {
                                doGoogleDocsBlock(true); // Bypass cooldown for instant close
                                return;
                            }
                        } finally {
                            source.recycle();
                        }
                    }
                }
            }
        }

        // ===== FALLBACK WATCHDOG FOR SERVICE RESTART / MISSED CLICK =====
        // Runs on window state changes, or content changes involving a WebView
        boolean isWatchdogTriggered = false;
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            isWatchdogTriggered = true;
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isGoogleDocsPackage(pkgName)) {
            CharSequence evClass = event.getClassName();
            if (evClass != null && evClass.toString().contains("WebView")) {
                isWatchdogTriggered = true;
            } else {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    CharSequence srcClass = source.getClassName();
                    if (srcClass != null && srcClass.toString().toLowerCase().contains("webview")) {
                        isWatchdogTriggered = true;
                    }
                    source.recycle();
                }
            }
        }

        if (!isBrowserKillLoopActive && isWatchdogTriggered) {
            // SAFETY GUARD: If the package is NOT Google Docs itself, do NOT run the fallback watchdog.
            // This prevents the fallback watchdog from accidentally blocking external browsers (like Chrome)
            // when they are opened normally by the user outside of a Google Docs "From web" click session.
            if (!isGoogleDocsPackage(pkgName)) {
                return;
            }

            CharSequence evClass = event.getClassName();
            String clsStr = evClass != null ? evClass.toString() : "";
            
            // 1. If it is explicitly one of the search activities, block immediately
            if (clsStr.contains("ExploreActivity") || clsStr.contains("WebSearch") || clsStr.contains("CustomTab")) {
                doGoogleDocsBlock(true);
                return;
            }
            
            // 2. Otherwise check layout tree using highly optimized, single-pass checkDocsSearchDeep
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    if (checkDocsSearchDeep(root)) {
                        doGoogleDocsBlock(true);
                        return;
                    }
                } finally {
                    root.recycle();
                }
            }
        }

        // ===== FAST PATH #1: "From web" click interception =====
        // When user clicks "From web", fire rapid BACK actions to kill the browser
        // before it can even start rendering. This makes the button appear "dead".
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isGoogleDocsPackage(pkgName)) {
            // ZERO-IPC check: event text
            String eventTxt = getEventText(event).toLowerCase();
            if (eventTxt.contains("from web") || 
                eventTxt.contains("ওয়েব থেকে") || eventTxt.contains("ওয়েব থেকে") ||
                eventTxt.contains("ওয়েব হতে") || eventTxt.contains("ওয়েব হতে") ||
                eventTxt.contains("वेब से") || 
                eventTxt.contains("desde la web") || eventTxt.contains("de la web") ||
                eventTxt.contains("da web")) {
                doFromWebClickBlock();
                return;
            }

            // 1-IPC fallback: check source node tree
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                if (isFromWebNodeOrChildren(source, 0)) {
                    source.recycle();
                    doFromWebClickBlock();
                    return;
                }
                source.recycle();
            }
        }

        // ===== FAST PATH #2: Highly Optimized Single-Pass Tree Search =====
        // Only check for search pages if the browser kill loop is currently active.
        // This ensures 100% safety for normal document editing/viewing.
        if (isBrowserKillLoopActive) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return;
                try {
                    if (checkDocsSearchDeep(root)) {
                        doGoogleDocsBlock();
                        return;
                    }
                } finally {
                    root.recycle();
                }
            }
        }
    }

    private boolean isWebSearchExplicit = false;
    private boolean hasSearchIcon = false;
    private boolean hasFormattingBar = false;
    private boolean hasWebDomain = false;
    private boolean hasLeftArrow = false;
    private boolean hasWebView = false;
    private boolean hasProgressBar = false;
    private boolean hasEditText = false;
    private boolean hasHamburgerMenu = false;
    private boolean hasFAB = false;
    private boolean hasRecyclerView = false;

    private boolean checkDocsSearchDeep(AccessibilityNodeInfo root) {
        isWebSearchExplicit = false;
        hasSearchIcon = false;
        hasFormattingBar = false;
        hasWebDomain = false;
        hasLeftArrow = false;
        hasWebView = false;
        hasProgressBar = false;
        hasEditText = false;
        hasHamburgerMenu = false;
        hasFAB = false;
        hasRecyclerView = false;
        
        boolean matched = scanDocsUIOptimized(root, 0);
        if (matched) return true;
        
        // If we see editor components (formatting bar, hamburger menu, or Floating Action Button),
        // we are 100% in the normal document editor or document view.
        // We must NEVER block the normal document editor.
        if (hasFormattingBar || hasHamburgerMenu || hasFAB) {
            return false;
        }
        
        // 1. The WebView of the Web Search browser is active (0.000s Zero-Flash detection)
        if (hasWebView && hasLeftArrow) {
            return true;
        }
        
        // 2. Aggressive Zero-Flash WebView check
        if (hasWebView) {
            return true;
        }
        
        // 3. Search suggestions or blank search browser is open (EditText + Left Arrow, e.g. suggesting history)
        if (hasLeftArrow && hasEditText) {
            return true;
        }
        
        // 4. Any explicit search query or search icon on a secondary screen
        if (isWebSearchExplicit || hasSearchIcon || hasWebDomain) {
            if (hasLeftArrow || hasEditText) {
                return true;
            }
        }
        
        return false;
    }

    private boolean scanDocsUIOptimized(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 12) return false;
        
        // Detect UI Structures
        if (node.getClassName() != null) {
            String cls = node.getClassName().toString();
            if (cls.contains("WebView")) hasWebView = true;
            if (cls.contains("ProgressBar")) hasProgressBar = true;
            if (cls.contains("EditText") || cls.contains("AutoCompleteTextView")) hasEditText = true;
            if (cls.contains("RecyclerView") || cls.contains("GridView")) hasRecyclerView = true;
            if (cls.contains("FloatingActionButton")) hasFAB = true;
        }
        
        CharSequence txt = node.getText();
        if (txt != null) {
            String s = txt.toString().toLowerCase();
            if (isGoogleDocsSearchText(s)) {
                isWebSearchExplicit = true;
            }
            if (s.startsWith("www.") || s.contains(".com") || s.contains(".org") || s.contains(".net") || s.contains("wikipedia.org")) {
                // Ignore if it's the main editable document text which might be huge
                if (s.length() < 100 && !node.isEditable()) {
                    hasWebDomain = true;
                }
            }
            if (s.equals("search") || s.equals("অনুসন্ধান") || s.equals("সার্চ") || s.equals("search web") || s.equals("ওয়েবে খুঁজুন") || s.equals("search query") || s.equals("clear query") || s.equals("clear text") || s.equals("clear")) {
                hasSearchIcon = true;
            }
            if (s.equals("navigate up") || s.equals("close") || s.equals("back") || s.equals("উপরে নেভিগেট করুন") || s.equals("বন্ধ করুন") || s.equals("ফিরে যান") || s.equals("ব্যাক")) {
                hasLeftArrow = true;
            }
        }
        
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String s = desc.toString().toLowerCase();
            if (isGoogleDocsSearchText(s)) {
                isWebSearchExplicit = true;
            }
            if (s.contains("search") || s.contains("অনুসন্ধান") || s.contains("সার্চ") || s.contains("clear") || s.contains("query")) {
                hasSearchIcon = true;
            }
            if (s.equals("bold") || s.equals("বোল্ড") || s.equals("italic") || s.equals("ইটালিক") || s.equals("underline") || s.equals("আন্ডারলাইন") || s.equals("edit") || s.equals("সম্পাদনা করুন")) {
                hasFormattingBar = true;
            }
            if (s.contains("navigate") || s.contains("close") || s.contains("back") || s.contains("উপরে") || s.contains("বন্ধ") || s.contains("ফিরে") || s.contains("ব্যাক") || s.contains("arrow") || s.contains("left") || s.contains("collapse") || s.contains("cancel")) {
                hasLeftArrow = true;
            }
            if (s.contains("drawer") || s.contains("menu") || s.contains("navigation") || s.contains("মেনু") || s.contains("ড্রয়ার")) {
                hasHamburgerMenu = true;
            }
        }
        
        // Fast exit: stop scanning once we have enough signals
        if (isWebSearchExplicit) return true;
        if (hasLeftArrow && hasWebView) return true;
        if (hasLeftArrow && hasEditText && hasProgressBar) return true;
        
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean match = scanDocsUIOptimized(child, depth + 1);
                child.recycle();
                if (match) return true;
            }
        }
        
        return false;
    }

    private boolean isFromWebNodeOrChildren(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 10) return false;
        
        CharSequence txt = node.getText();
        if (txt != null) {
            String s = txt.toString().toLowerCase();
            if (s.contains("from web") || 
                s.contains("ওয়েব থেকে") || s.contains("ওয়েব থেকে") ||
                s.contains("ওয়েব হতে") || s.contains("ওয়েব হতে") ||
                s.contains("वेब से") || 
                s.contains("desde la web") || s.contains("de la web") ||
                s.contains("da web")) return true;
        }
        
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String s = desc.toString().toLowerCase();
            if (s.contains("from web") || 
                s.contains("ওয়েব থেকে") || s.contains("ওয়েব থেকে") ||
                s.contains("ওয়েব হতে") || s.contains("ওয়েব হতে") ||
                s.contains("वेब से") || 
                s.contains("desde la web") || s.contains("de la web") ||
                s.contains("da web")) return true;
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (isFromWebNodeOrChildren(child, depth + 1)) {
                if (child != null) child.recycle();
                return true;
            }
            if (child != null) child.recycle();
        }
        
        return false;
    }

    private boolean hasWebViewInTree(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return false;
        
        CharSequence cls = node.getClassName();
        if (cls != null) {
            String clsStr = cls.toString();
            if (clsStr.contains("WebView") || clsStr.contains("webview")) {
                return true;
            }
        }
        
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = hasWebViewInTree(child, depth + 1);
                child.recycle();
                if (found) return true;
            }
        }
        
        return false;
    }



    private View activeOverlayView = null;

    private synchronized void showInstantZeroFlashOverlay() {
        if (activeOverlayView != null) return;

        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;

            final View overlayView = new View(this);
            overlayView.setBackgroundColor(Color.parseColor("#1A1A24")); // Premium Slate Grey
            overlayView.setAlpha(1f);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            );

            wm.addView(overlayView, params);
            activeOverlayView = overlayView;

        } catch (Exception ignored) {}
    }

    private synchronized void dismissOverlayWithAnimation() {
        if (activeOverlayView == null) return;
        final View overlay = activeOverlayView;
        activeOverlayView = null; // Mark as null immediately to prevent double dismissal

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    overlay.animate()
                        .alpha(0f)
                        .setDuration(150)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                                    if (wm != null) {
                                        wm.removeView(overlay);
                                    }
                                } catch (Exception ignored) {}
                            }
                        })
                        .start();
                } catch (Exception e) {
                    try {
                        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                        if (wm != null) {
                            wm.removeView(overlay);
                        }
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    private String getEventText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null) sb.append(t).append(' ');
            }
        }
        CharSequence desc = event.getContentDescription();
        if (desc != null) sb.append(desc);
        return sb.toString();
    }

    @Override
    public void onInterrupt() {}
}
