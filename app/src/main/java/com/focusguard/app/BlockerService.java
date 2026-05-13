package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * FocusGuard Blocker Service — v1.6.2
 *
 * BUG FIXES in this version:
 *   - FIX #1: OEM compatibility — Samsung/OPPO/Xiaomi settings package detection
 *   - FIX #2: Instagram reels detection — removed unreliable content description check
 *   - FIX #3: WhatsApp Channels — Redesigned to use Chat redirection and strict UI matching
 */
public class BlockerService extends AccessibilityService {

    // BUG FIX #1: Samsung uses "com.samsung.android.settings"
    // OPPO/Realme use "com.coloros.settings"
    // We use contains() instead of equals() to handle ALL OEMs


    private static final String PKG_WHATSAPP  = "com.whatsapp";
    private static final String PKG_YOUTUBE   = "com.google.android.youtube";
    private static final String PKG_INSTAGRAM = "com.instagram.android";

    private static final String OUR_PACKAGE   = "com.focusguard.app";
    private static final String SERVICE_LABEL = "FocusGuard";

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
                handleAccessibilityProtection(event, eventType);
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
    private void handleAccessibilityProtection(AccessibilityEvent event, int eventType) {
        // --- PERFORMANCE OPTIMIZATION ---
        // Only act on Clicks or Window Changes. Ignore content updates (scrolling, etc.)
        if (eventType != AccessibilityEvent.TYPE_VIEW_CLICKED && 
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        // --- CLICK DETECTION (Ultra-Fast) ---
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                // Instantly check if the clicked node mentions FocusGuard
                if (isFocusGuardNode(source)) {
                    source.recycle();
                    triggerKickOut();
                    return;
                }
                source.recycle();
            }
        }

        // --- SCREEN DETECTION (Only on full window change) ---
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                // Ensure we are in an Accessibility-related screen
                boolean isAccessibilityWindow = !root.findAccessibilityNodeInfosByText("Accessibility").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("এক্সেসিবিলিটি").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Use FocusGuard").isEmpty();
                
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
            "App info", "অ্যাপ তথ্য", "Permissions", "Storage"
        };
        for (String title : ignoreTitles) {
            if (!root.findAccessibilityNodeInfosByText(title).isEmpty()) return false;
        }

        // 2. Window must contain our service name
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("FocusGuard");
        if (hits == null || hits.isEmpty()) return false;
        for (AccessibilityNodeInfo n : hits) n.recycle();

        // 3. STRICT ACCESSIBILITY CONTEXT
        // Only block if we see Accessibility-specific detail keywords.
        // This prevents Accessibility Lock from accidentally blocking Admin screens.
        boolean isAccessibilityContext = !root.findAccessibilityNodeInfosByText("Use service").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("Use FocusGuard").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("ব্যবহার").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("Shortcut").isEmpty();
        
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
                    List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("FocusGuard");
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

    /** Helper to check if a node or its children mention FocusGuard */
    private boolean isFocusGuardNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        
        // Check the node itself
        CharSequence txt = node.getText();
        if (txt != null && txt.toString().contains("FocusGuard")) return true;
        
        // Check direct children (for list items)
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                CharSequence ctxt = child.getText();
                if (ctxt != null && ctxt.toString().contains("FocusGuard")) {
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
                        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("FocusGuard");
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
                    List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("FocusGuard");
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
            if (isInsideWhatsAppChannels(root)) {
                // Instantly redirect to the Chats tab to keep the user inside WhatsApp
                boolean switched = switchToWhatsAppChats(root);
                if (!switched) {
                    // Fallback: If Chats tab is not visible (e.g., deep in a channel view), go Back
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            }
        } finally {
            root.recycle();
        }
    }

    private boolean isInsideWhatsAppChannels(AccessibilityNodeInfo root) {
        // STRICT EXACT MATCHING ONLY.
        // We do NOT use .contains() on generic text to prevent blocking normal chat messages.

        // 1. Channel-specific headers and UI elements (Exact Match, No EditTexts)
        String[] channelIndicators = {
            "Channel info", "চ্যানেলের তথ্য", 
            "Find channels", "চ্যানেল খুঁজুন",
            "Channels to follow", "View channel", "চ্যানেল দেখুন",
            "Public channel", "পাবলিক চ্যানেল",
            "Channel link", "চ্যানেলের লিঙ্ক",
            "Channel settings", "চ্যানেল সেটিংস",
            "Report channel", "চ্যানেল সম্পর্কে রিপোর্ট করুন"
        };
        
        for (String indicator : channelIndicators) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(indicator);
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                
                if (isEditableNode(node)) {
                    node.recycle();
                    continue;
                }

                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                boolean matchText = text != null && text.toString().equalsIgnoreCase(indicator);
                boolean matchDesc = desc != null && desc.toString().equalsIgnoreCase(indicator);

                if (matchText || matchDesc) {
                    node.recycle();
                    return true;
                }
                node.recycle();
            }
        }

        // 2. Follow / Unfollow buttons (Exact Match, Must be Clickable or Button)
        String[] actionBtns = {"Follow", "ফলো করুন", "Unfollow", "আনফলো করুন"};
        for (String btn : actionBtns) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(btn);
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                
                if (isEditableNode(node)) {
                    node.recycle();
                    continue;
                }

                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                boolean matchText = text != null && text.toString().equalsIgnoreCase(btn);
                boolean matchDesc = desc != null && desc.toString().equalsIgnoreCase(btn);

                if (matchText || matchDesc) {
                    if (node.isClickable() || "android.widget.Button".equals(node.getClassName())) {
                        node.recycle();
                        return true;
                    }
                }
                node.recycle();
            }
        }

        // 3. Block the Updates/Channels Tab ONLY IF it is currently selected.
        String[] tabNames = {"Updates", "আপডেট", "Channels", "চ্যানেল"};
        for (String tabName : tabNames) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(tabName);
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                
                if (isEditableNode(node) || isInsideChatList(node)) {
                    node.recycle();
                    continue;
                }

                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                
                boolean exactMatchText = text != null && text.toString().equalsIgnoreCase(tabName);
                boolean matchDesc = desc != null && desc.toString().toLowerCase().contains(tabName.toLowerCase());
                
                if (exactMatchText || matchDesc) {
                    // Check if this specific tab is currently active/selected
                    if (node.isSelected() || isAncestorSelected(node) || (desc != null && desc.toString().toLowerCase().contains("selected"))) {
                        node.recycle();
                        return true;
                    }
                }
                node.recycle();
            }
        }

        // 4. Channel Top Bar Subtitle & Verification
        // Often visible inside a channel as "1.2M followers" or subtitle "Channel"
        String[] channelSubtitles = {" followers", " ফলোয়ার", "Channel", "চ্যানেল"};
        for (String subtitle : channelSubtitles) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(subtitle);
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                
                if (isEditableNode(node) || isInsideChatList(node)) {
                    node.recycle();
                    continue;
                }

                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                
                boolean isFollowers = subtitle.startsWith(" ");
                if (isFollowers) {
                    // Partial match for " followers"
                    boolean matchText = text != null && text.toString().toLowerCase().contains(subtitle.toLowerCase());
                    boolean matchDesc = desc != null && desc.toString().toLowerCase().contains(subtitle.toLowerCase());
                    if (matchText || matchDesc) {
                        node.recycle();
                        return true;
                    }
                } else {
                    // Exact match for "Channel"
                    boolean matchText = text != null && text.toString().equalsIgnoreCase(subtitle);
                    boolean matchDesc = desc != null && desc.toString().equalsIgnoreCase(subtitle);
                    if (matchText || matchDesc) {
                        node.recycle();
                        return true;
                    }
                }
                node.recycle();
            }
        }

        return false;
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
