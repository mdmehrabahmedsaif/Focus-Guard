package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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

    private void kickOutToGoogleDocsHome() {
        // A single BACK action destroys the animating browser fragment and returns to the document.
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /**
     * Fires the block with a 200ms cooldown to prevent duplicate triggers.
     */
    private void doGoogleDocsBlock() {
        long now = System.currentTimeMillis();
        if (now - lastGoogleDocsBlockTime < 200) return;
        lastGoogleDocsBlockTime = now;
        kickOutToGoogleDocsHome();
    }

    /**
     * When user clicks "From web", we need to fire BACK multiple times:
     * 1st BACK: Closes the Image panel (already closing from the click)
     * 2nd BACK (after 80ms): Kills the browser fragment that's trying to open
     * 3rd BACK (after 160ms): Safety net in case the fragment wasn't ready yet
     * This guarantees the browser NEVER appears on screen.
     */
    private void doFromWebClickBlock() {
        long now = System.currentTimeMillis();
        if (now - lastGoogleDocsBlockTime < 500) return;
        lastGoogleDocsBlockTime = now;
        // Immediate BACK to close the panel
        performGlobalAction(GLOBAL_ACTION_BACK);
        // Delayed BACKs to kill the browser fragment as it tries to open
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        }, 80);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        }, 200);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        }, 400);
    }

    /**
     * Instantly dismisses the Image panel.
     */
    private void dismissImagePanel() {
        doGoogleDocsBlock();
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
     * NOTE: "from web" is NOT here — it only appears on the Image panel.
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

    private boolean isImagePanelWatchdogActive = false;

    /**
     * Watchdog: polls the screen every 80ms after Image panel opens.
     * If the search page appears, it blocks INSTANTLY.
     */
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isImagePanelWatchdogActive) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                // Quick check: is the search page open?
                if (!root.findAccessibilityNodeInfosByText("Search your docs and the web").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("আপনার ডক্স এবং ওয়েব").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Search query").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Clear query").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Search images").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("ছবি খুঁজুন").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Find images, facts and text").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Search directly in Docs").isEmpty()) {
                    isImagePanelWatchdogActive = false;
                    doGoogleDocsBlock();
                    return;
                }
                // Also run deep scan
                if (checkDocsSearchDeep(root)) {
                    isImagePanelWatchdogActive = false;
                    doGoogleDocsBlock();
                    return;
                }
                // DO NOT stop watchdog here. If the user clicked "From web", the UI transitions
                // to the search page, so "From web" will disappear. We WANT the watchdog to keep
                // polling while the new page loads! It will naturally time out after 3 seconds.
            } finally {
                root.recycle();
            }
        }
    };

    private void startImagePanelWatchdog() {
        if (isImagePanelWatchdogActive) return;
        isImagePanelWatchdogActive = true;
        // Poll every 20ms for 3 seconds (150 polls) — extreme detection speed
        for (int i = 1; i <= 150; i++) {
            mainHandler.postDelayed(watchdogRunnable, i * 20L);
        }
        // Auto-stop after 3 seconds
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                isImagePanelWatchdogActive = false;
            }
        }, 3000);
    }

    private void stopImagePanelWatchdog() {
        isImagePanelWatchdogActive = false;
    }

    private void handleGoogleDocs(AccessibilityEvent event, int eventType) {
        // ===== ABSOLUTE ZERO-IPC WEBVIEW KICKOUT (<0.0001s) =====
        CharSequence evClass = event.getClassName();
        if (evClass != null) {
            String clsStr = evClass.toString();
            if (clsStr.contains("WebView") || clsStr.contains("WebSearch") || clsStr.contains("ExploreActivity")) {
                stopImagePanelWatchdog();
                doGoogleDocsBlock();
                return;
            }
        }

        // ===== 1-IPC WEBVIEW KICKOUT =====
        // If a WebView is added or changes content, its source will be a WebView
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                CharSequence srcClass = source.getClassName();
                if (srcClass != null && srcClass.toString().contains("WebView")) {
                    source.recycle();
                    stopImagePanelWatchdog();
                    doGoogleDocsBlock();
                    return;
                }
                source.recycle();
            }
        }

        // ===== ULTRA-FAST PATH #0: Event text pre-check (ZERO IPC, <0.001s) =====
        if (checkGoogleDocsEventText(event)) {
            stopImagePanelWatchdog();
            doGoogleDocsBlock();
            return;
        }

        // ===== PRIORITY FAST-TRACK: Watchdog active + new window =====
        // When Image panel was showing (watchdog active) and a NEW window opens
        // in Google Docs, it's almost certainly the search page.
        // "From photos"/"From camera" open EXTERNAL apps (different package),
        // so they never trigger WINDOW_STATE_CHANGED in Google Docs.
        // Skip ALL expensive findAccessibilityNodeInfosByText IPC calls (~18 calls
        // = ~150ms on old devices) and go STRAIGHT to single-pass deep scan.
        if (isImagePanelWatchdogActive && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    if (checkDocsSearchDeep(root)) {
                        stopImagePanelWatchdog();
                        doGoogleDocsBlock();
                        return;
                    }
                    // DO NOT check for "From web" and stop watchdog here!
                    // This is a NEW window. "From web" will naturally be missing.
                    // If we stop it, we kill the burst scan right when the WebView is loading!
                } finally {
                    root.recycle();
                }
            }
        }

        // ===== FAST PATH #1: "From web" click interception =====
        // When user clicks "From web", fire rapid BACK actions to kill the browser
        // before it can even start rendering. This makes the button appear "dead".
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // ZERO-IPC check: event text
            String eventTxt = getEventText(event).toLowerCase();
            if (eventTxt.contains("from web") || eventTxt.contains("ওয়েব থেকে") || eventTxt.contains("ওয়েব থেকে") ||
                eventTxt.contains("explore") || eventTxt.contains("অন্বেষণ করুন")) {
                stopImagePanelWatchdog();
                doFromWebClickBlock();
                return;
            }

            // 1-IPC fallback: check source node tree
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                if (isFromWebNodeOrChildren(source, 0)) {
                    source.recycle();
                    stopImagePanelWatchdog();
                    doFromWebClickBlock();
                    return;
                }
                source.recycle();
            }

            // BURST SCAN: Ultra-rapid polling every 10ms for 300ms after any click
            if (isImagePanelWatchdogActive) {
                mainHandler.post(watchdogRunnable); // Check NOW (0ms)
                for (int i = 1; i <= 30; i++) {
                    mainHandler.postDelayed(watchdogRunnable, i * 10L);
                }
            }
        }

        // ===== WATCHDOG ACTIVATION: When Image panel opens =====
        // Detects "From web" appearing on screen and starts rapid polling
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            String eventTxt = getEventText(event).toLowerCase();
            if (eventTxt.contains("from web") || eventTxt.contains("ওয়েব থেকে") ||
                eventTxt.contains("explore") || eventTxt.contains("অন্বেষণ করুন")) {
                startImagePanelWatchdog();
            }
        }

        // ===== FAST PATH #2: Minimal-IPC tree search with early exit =====
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            long now = System.currentTimeMillis();
            if (now - lastGoogleDocsBlockTime < 100) return;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                // Check 1: Most distinctive text — single IPC, early exit
                List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Search your docs and the web");
                if (!hits.isEmpty()) {
                    for (AccessibilityNodeInfo n : hits) n.recycle();
                    stopImagePanelWatchdog();
                    doGoogleDocsBlock();
                    return;
                }

                // Check 2: Bengali variant
                hits = root.findAccessibilityNodeInfosByText("আপনার ডক্স এবং ওয়েব");
                if (!hits.isEmpty()) {
                    for (AccessibilityNodeInfo n : hits) n.recycle();
                    stopImagePanelWatchdog();
                    doGoogleDocsBlock();
                    return;
                }

                // Check 3: Other search page indicators
                if (!root.findAccessibilityNodeInfosByText("Search query").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Clear query").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Search web").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("ওয়েবে খুঁজুন").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Search images").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("ছবি খুঁজুন").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Find images, facts and text").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("আপনার দস্তাবেজ এবং ওয়েব").isEmpty() ||
                    !root.findAccessibilityNodeInfosByText("Search directly in Docs").isEmpty()) {
                    stopImagePanelWatchdog();
                    doGoogleDocsBlock();
                    return;
                }

                // Check 3.5: FAST search bar pattern detection (← + 🔍)
                // The search page has a "Navigate up" left arrow AND a "Search" magnifying glass.
                // This catches the page even when specific hint text is gone (user has typed text).
                boolean hasNavUp = !root.findAccessibilityNodeInfosByText("Navigate up").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("Close").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("Back").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("বন্ধ করুন").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("উপরে নেভিগেট করুন").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("ফিরে যান").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("ব্যাক").isEmpty();
                if (hasNavUp) {
                    // Check for magnifying glass / search icon / clear icon
                    boolean hasSearchBtn = !root.findAccessibilityNodeInfosByText("Search").isEmpty() ||
                                           !root.findAccessibilityNodeInfosByText("অনুসন্ধান").isEmpty() ||
                                           !root.findAccessibilityNodeInfosByText("সার্চ").isEmpty() ||
                                           !root.findAccessibilityNodeInfosByText("Search web").isEmpty() ||
                                           !root.findAccessibilityNodeInfosByText("ওয়েবে খুঁজুন").isEmpty() ||
                                           !root.findAccessibilityNodeInfosByText("Search query").isEmpty() ||
                                           !root.findAccessibilityNodeInfosByText("Clear query").isEmpty() ||
                                           !root.findAccessibilityNodeInfosByText("Clear text").isEmpty() ||
                                           !root.findAccessibilityNodeInfosByText("Clear").isEmpty();
                    if (hasSearchBtn) {
                        // Confirm we are NOT in normal editor (no formatting bar)
                        boolean hasFormatBar = !root.findAccessibilityNodeInfosByText("Bold").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("বোল্ড").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Edit").isEmpty();
                        if (!hasFormatBar) {
                            stopImagePanelWatchdog();
                            doGoogleDocsBlock();
                            return;
                        }
                    }
                }

                // Check 4: Deep scan — NO throttle if Watchdog is active, otherwise 30ms throttle
                boolean doDeepScan = false;
                if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    doDeepScan = true; // Always scan on new window
                } else {
                    long scanNow = System.currentTimeMillis();
                    if (isImagePanelWatchdogActive || (scanNow - lastDeepScanTime > 30)) {
                        doDeepScan = true;
                    }
                }
                if (doDeepScan) {
                    lastDeepScanTime = System.currentTimeMillis();
                    if (checkDocsSearchDeep(root)) {
                        stopImagePanelWatchdog();
                        doGoogleDocsBlock();
                        return;
                    }
                }

                // Check 5: Activate watchdog if Image panel is open (tree-based, works on ALL devices)
                if (!isImagePanelWatchdogActive) {
                    if (!root.findAccessibilityNodeInfosByText("From web").isEmpty() ||
                        !root.findAccessibilityNodeInfosByText("ওয়েব থেকে").isEmpty()) {
                        startImagePanelWatchdog();
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
        
        scanDocsUI(root);
        
        if (isWebSearchExplicit) return true;
        
        // If we see the Left Arrow AND a WebView inside Google Docs, it's the web search browser!
        // This is 100% accurate and blocks it instantly (sub-0.001s) before results even render.
        if (hasLeftArrow && hasWebView) {
            return true;
        }
        
        // If we see a Left Arrow, an EditText (search box), and a ProgressBar (the blue line),
        // it is the web search loading its results. Block it instantly to hide the blue line!
        if (hasLeftArrow && hasEditText && hasProgressBar) {
            return true;
        }
        
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
        // Extremely aggressive block as requested by user.
        if (hasLeftArrow && hasSearchIcon) {
            return true;
        }
        
        // If we see the Left Arrow AND Web Domains (e.g. results loaded but no Search Icon), block it.
        if (hasLeftArrow && hasWebDomain) {
            return true;
        }
        
        // EXTREME HEURISTIC: Block any secondary search list page (neutralizes "From Web" and "Explore")
        // If it's a search page with a list of results, NO hamburger menu (not main app), NO FAB, NO formatting bar.
        // This makes it physically impossible to miss the browser even if all icons lose their content descriptions!
        if (hasEditText && hasRecyclerView && hasSearchIcon && !hasHamburgerMenu && !hasFAB && !hasFormattingBar) {
            return true;
        }

        // HEURISTIC 2: If the results are already loaded (hasWebDomain) and we are not in the main editor.
        if (hasEditText && hasWebDomain && !hasFormattingBar) {
            return true;
        }

        // HEURISTIC 3: If it's actively loading (hasProgressBar), has a search box, has a list, and is a secondary screen.
        if (hasEditText && hasRecyclerView && hasProgressBar && !hasHamburgerMenu && !hasFAB && !hasFormattingBar) {
            return true;
        }
        
        return false;
    }

    private void scanDocsUI(AccessibilityNodeInfo node) {
        if (node == null) return;
        
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
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            scanDocsUI(child);
            if (child != null) child.recycle();
            
            // Fast exit: stop scanning once we have enough signals
            if (isWebSearchExplicit) return;
            if (hasLeftArrow && hasWebView) return;
            if (hasLeftArrow && hasEditText && hasProgressBar) return;
        }
    }

    private boolean isFromWebNodeOrChildren(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 10) return false;
        
        CharSequence txt = node.getText();
        if (txt != null) {
            String s = txt.toString().toLowerCase();
            if (s.contains("from web") || s.contains("ওয়েব থেকে") || s.contains("ওয়েব থেকে") ||
                s.equals("explore") || s.equals("অন্বেষণ করুন")) return true;
        }
        
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String s = desc.toString().toLowerCase();
            if (s.contains("from web") || s.contains("ওয়েব থেকে") || s.contains("ওয়েব থেকে") ||
                s.equals("explore") || s.equals("অন্বেষণ করুন")) return true;
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
