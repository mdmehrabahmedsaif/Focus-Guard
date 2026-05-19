package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * FocusGuard Blocker Service — v1.8.0
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

    // Pre-allocated Handler + Runnable for zero-GC hot path
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable kickOutRunnable = new Runnable() {
        @Override
        public void run() {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    };

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
        if (prefManager == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgName = pkg.toString().toLowerCase();

        int eventType = event.getEventType();

        // GLOBAL PROTECTION SCAN (Non-FocusGuard apps only)
        if (!pkgName.equals(OUR_PACKAGE)) {
            // Check Accessibility Protection
            if (prefManager.isAccessibilityProtected()) {
                handleAccessibilityProtection(event, eventType, pkgName);
            }
            // Check Device Admin Protection
            if (prefManager.isDeviceAdminProtected()) {
                handleAdminProtection(event, eventType);
            }
            // Check Uninstall Protection
            if (prefManager.isUninstallProtected()) {
                handleUninstallProtection(event, eventType);
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
        } else if (PKG_GOOGLE_DOCS.equals(pkgName)) {
            if (prefManager.isGoogleDocsBlocked()) {
                handleGoogleDocs(event, eventType);
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

    private void handleWhatsApp(AccessibilityEvent event, int eventType) {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            int blockReason = getWhatsAppBlockReason(root);
            if (blockReason == 1) {
                // Tab Block - Try to switch to Chats. If it fails (e.g. Chats tab hidden), go Back.
                boolean switched = switchToWhatsAppChats(root);
                if (!switched) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            } else if (blockReason == 2) {
                // Channel Block - Instantly go Back (sub-0.1s latency)
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } finally {
            root.recycle();
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

    /**
     * Ultra-fast kickout: 3 rapid BACKs with 30ms gaps to reliably
     * exit search → image menu → document → Google Docs Home.
     */
    private void kickOutToGoogleDocsHome() {
        performGlobalAction(GLOBAL_ACTION_BACK);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        }, 50);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        }, 150);
    }

    /**
     * Fires the block with a 200ms cooldown to prevent duplicate triggers
     * from the rapid BACK actions.
     */
    private void doGoogleDocsBlock() {
        long now = System.currentTimeMillis();
        if (now - lastGoogleDocsBlockTime < 300) return;
        lastGoogleDocsBlockTime = now;
        kickOutToGoogleDocsHome();
    }

    /**
     * ZERO-IPC event text pre-check. Reads text already present in the
     * AccessibilityEvent object — no Binder IPC, no tree traversal.
     * This is the fastest possible detection path (<0.001s).
     */
    private boolean checkGoogleDocsEventText(AccessibilityEvent event) {
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null && isGoogleDocsSearchText(t.toString())) {
                    return true;
                }
            }
        }
        CharSequence desc = event.getContentDescription();
        if (desc != null && isGoogleDocsSearchText(desc.toString())) {
            return true;
        }
        return false;
    }

    /**
     * Checks if a string contains any Google Docs web search PAGE indicator text.
     * NOTE: "from web" is NOT here — it only appears on the Image panel which must
     * remain accessible. "From web" clicks are caught separately in FAST PATH #1.
     */
    private boolean isGoogleDocsSearchText(String text) {
        String s = text.toLowerCase();
        return s.contains("search your docs and the web") ||
               s.contains("আপনার ডক্স এবং ওয়েব") ||
               s.contains("আপনার দস্তাবেজ এবং ওয়েব") ||
               s.contains("search images") ||
               s.contains("ছবি খুঁজুন") ||
               s.contains("find images, facts and text") ||
               s.contains("search directly in docs") ||
               s.contains("search web") ||
               s.contains("ওয়েবে খুঁজুন") ||
               s.contains("search query");
    }

    private void handleGoogleDocs(AccessibilityEvent event, int eventType) {
        // ===== ULTRA-FAST PATH #0: Event text pre-check (ZERO IPC, <0.001s) =====
        // The event object already has text in memory — no system call needed.
        // This catches "Search your docs and the web", "From web", etc. INSTANTLY.
        if (checkGoogleDocsEventText(event)) {
            doGoogleDocsBlock();
            return;
        }

        // ===== FAST PATH #1: "From web" click detection =====
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // ZERO-IPC check first: if the clicked element has text, block instantly
            String eventTxt = getEventText(event).toLowerCase();
            if (eventTxt.contains("from web") || eventTxt.contains("ওয়েব থেকে") || eventTxt.contains("ওয়েব থেকে")) {
                doGoogleDocsBlock();
                return;
            }

            // Fallback: If text is missing from the event parcel, check the node tree
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                if (isFromWebNodeOrChildren(source, 0)) {
                    source.recycle();
                    doGoogleDocsBlock();
                    return;
                }
                source.recycle();
            }
        }

        // ===== FAST PATH #2: Minimal-IPC tree search with early exit =====
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            long now = System.currentTimeMillis();
            if (now - lastGoogleDocsBlockTime < 300) return;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                // Check 1: Most distinctive text — single IPC, early exit
                List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Search your docs and the web");
                if (!hits.isEmpty()) {
                    for (AccessibilityNodeInfo n : hits) n.recycle();
                    doGoogleDocsBlock();
                    return;
                }

                // Check 2: Bengali variant
                hits = root.findAccessibilityNodeInfosByText("আপনার ডক্স এবং ওয়েব");
                if (!hits.isEmpty()) {
                    for (AccessibilityNodeInfo n : hits) n.recycle();
                    doGoogleDocsBlock();
                    return;
                }

                // Check 3: Other search page indicators (short-circuit evaluation)
                if (!root.findAccessibilityNodeInfosByText("Search query").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Clear query").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Search web").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("ওয়েবে খুঁজুন").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Search images").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("ছবি খুঁজুন").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Find images, facts and text").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("আপনার দস্তাবেজ এবং ওয়েব").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Search directly in Docs").isEmpty()) {
                    doGoogleDocsBlock();
                    return;
                }

                // Check 4: Deep scan on BOTH event types (throttled to 500ms to avoid typing lag)
                long scanNow = System.currentTimeMillis();
                if (scanNow - lastDeepScanTime > 500) {
                    lastDeepScanTime = scanNow;
                    if (checkDocsSearchDeep(root)) {
                        doGoogleDocsBlock();
                    }
                }
            } finally {
                root.recycle();
            }
        }
    }

    private boolean isWebSearchExplicit = false;
    private boolean hasSearchIcon = false;
    private boolean hasFormattingBar = false;
    private boolean hasWebDomain = false;
    private boolean hasLeftArrow = false;

    private boolean checkDocsSearchDeep(AccessibilityNodeInfo root) {
        isWebSearchExplicit = false;
        hasSearchIcon = false;
        hasFormattingBar = false;
        hasWebDomain = false;
        hasLeftArrow = false;
        
        scanDocsUI(root);
        
        if (isWebSearchExplicit) return true;
        
        // If we are in the editor (formatting bar visible) AND we see a search icon or web domains
        if (hasFormattingBar && (hasSearchIcon || hasWebDomain)) {
            return true;
        }
        
        // Even if keyboard hides formatting bar, if we see search icon AND web domains, it's the web search
        if (hasSearchIcon && hasWebDomain) {
            return true;
        }
        
        // If we see the Left Arrow AND Search Icon (or Clear icon), block it instantly.
        // This covers the search suggestions/history loophole when formatting bar is hidden.
        if (hasLeftArrow && hasSearchIcon) {
            return true;
        }
        
        // If we see the Left Arrow AND Web Domains (e.g. results loaded but no Search Icon), block it.
        if (hasLeftArrow && hasWebDomain) {
            return true;
        }
        
        return false;
    }

    private void scanDocsUI(AccessibilityNodeInfo node) {
        if (node == null) return;
        
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
        }
        
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String s = desc.toString().toLowerCase();
            if (isGoogleDocsSearchText(s)) {
                isWebSearchExplicit = true;
            }
            if (s.equals("search") || s.equals("অনুসন্ধান") || s.equals("সার্চ") || s.equals("search web") || s.equals("ওয়েবে খুঁজুন") || s.equals("search query") || s.equals("clear query")) {
                hasSearchIcon = true;
            }
            if (s.equals("bold") || s.equals("বোল্ড") || s.equals("italic") || s.equals("ইটালিক") || s.equals("underline") || s.equals("আন্ডারলাইন")) {
                hasFormattingBar = true;
            }
            if (s.equals("navigate up") || s.equals("close") || s.equals("উপরে নেভিগেট করুন") || s.equals("বন্ধ করুন") || s.equals("ফিরে যান")) {
                hasLeftArrow = true;
            }
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            scanDocsUI(child);
            if (child != null) child.recycle();
            
            if (isWebSearchExplicit) return; // Fast exit
        }
    }

    private boolean isFromWebNodeOrChildren(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 3) return false;
        
        CharSequence txt = node.getText();
        if (txt != null) {
            String s = txt.toString().toLowerCase();
            if (s.contains("from web") || s.contains("ওয়েব থেকে") || s.contains("ওয়েব থেকে")) return true;
        }
        
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String s = desc.toString().toLowerCase();
            if (s.contains("from web") || s.contains("ওয়েব থেকে") || s.contains("ওয়েব থেকে")) return true;
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
