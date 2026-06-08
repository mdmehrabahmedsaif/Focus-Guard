package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Path;
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
import android.widget.Toast;

import java.util.List;

/**
 * FocusGuard Blocker Service — v1.9.0
 * 
 * Optimized for size and speed:
 * - Retains ONLY Google Docs Web Image Search blocking and Device Admin/Uninstall protection.
 * - All other modules (WhatsApp, Assistant, Blocker Hero, YouTube, Instagram, DNS, Accessibility locks) are completely removed.
 */
public class BlockerService extends AccessibilityService {

    private static final String PKG_GOOGLE_DOCS = "com.google.android.apps.docs.editors.docs";
    private static final String OUR_PACKAGE   = "com.focusguard.app";

    private boolean isGoogleDocsPackage(String pkgName) {
        return pkgName != null && pkgName.startsWith("com.google.android.apps.docs.editors");
    }

    private boolean isMonitoredSearchPackage(String pkgName) {
        if (pkgName == null) return false;
        return "com.google.android.gms".equals(pkgName) || 
               "com.google.android.googlequicksearchbox".equals(pkgName) || 
               "com.android.chrome".equals(pkgName) || 
               "com.google.android.webview".equals(pkgName) || 
               "com.android.webview".equals(pkgName) ||
               pkgName.contains("browser") || 
               pkgName.contains("firefox") || 
               pkgName.contains("opera") || 
               pkgName.contains("searchbox") || 
               pkgName.contains("websearch");
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PreferenceManager prefManager;
    private static BlockerService instance;

    public static BlockerService getInstance() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefManager = new PreferenceManager(this);
        initGhostShield();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        dismissOverlayWithAnimation();
        stopBrowserKillLoop();
        stopMenuWatchdog();
        destroyGhostShield();
        hideDocsTouchBlocker();
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

        int eventType = event.getEventType();

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgName = pkg.toString().toLowerCase();

        boolean isSettingsPkg = pkgName.contains("settings");
        boolean isSystemOrKeyboard = "android".equals(pkgName) || 
                                     "com.android.systemui".equals(pkgName) || 
                                     pkgName.contains("inputmethod") || 
                                     pkgName.contains("keyboard") || 
                                     pkgName.contains("ime");

        // Remove overlay if we leave blocked packages
        boolean isDocsBrowserSession = prefManager.isGoogleDocsBlocked() && 
                                       isBrowserKillLoopActive && 
                                       isMonitoredSearchPackage(pkgName);

        if (!isGoogleDocsPackage(pkgName) && 
            !isDocsBrowserSession &&
            !isSystemOrKeyboard) {
            
            dismissOverlayWithAnimation();
            stopBrowserKillLoop();
            // Only remove touch blocker on actual app switch, not background events
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                hideDocsTouchBlocker();
                stopMenuWatchdog();
            }
            isFromWebOptionVisible = false;
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowStateChangedTime = System.currentTimeMillis();
            if (isGoogleDocsPackage(pkgName)) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        isFromWebOptionVisible = isInsertImageMenuOpen(root);
                    } finally {
                        root.recycle();
                    }
                }
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isGoogleDocsPackage(pkgName)) {
            long timeSinceStateChange = System.currentTimeMillis() - lastWindowStateChangedTime;
            if (timeSinceStateChange < 500) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        isFromWebOptionVisible = isInsertImageMenuOpen(root);
                    } finally {
                        root.recycle();
                    }
                }
            }
        }

        // GLOBAL PROTECTION SCAN
        if (!pkgName.equals(OUR_PACKAGE)) {
            isSettingsPkg = pkgName.contains("settings");
            boolean isInstallerPkg = pkgName.contains("packageinstaller") || pkgName.contains("installer");
            boolean isSystemPkg = "android".equals(pkgName) || "com.android.systemui".equals(pkgName);
            
            if (isSettingsPkg || isInstallerPkg || isSystemPkg) {
                // Check Device Admin Protection
                if (prefManager.isDeviceAdminProtected() && (isSettingsPkg || isSystemPkg)) {
                    handleAdminProtection(event, eventType);
                }
                // Check Uninstall Protection
                if (prefManager.isUninstallProtected()) {
                    handleUninstallProtection(event, eventType);
                }
            }
        }

        // Google Docs and search components
        if (isGoogleDocsPackage(pkgName) || isMonitoredSearchPackage(pkgName)) {
            if (prefManager.isGoogleDocsBlocked()) {
                handleGoogleDocs(event, eventType, pkgName);
            }
        }
    }

    /**
     * DEVICE ADMIN PROTECTION
     */
    private void handleAdminProtection(AccessibilityEvent event, int eventType) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            boolean isAdminWindow = !root.findAccessibilityNodeInfosByText("Device administrators").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("Device admin apps").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("ডিভাইস অ্যাডমিনিস্ট্রেটর").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("Activate device admin").isEmpty() ||
                                   !root.findAccessibilityNodeInfosByText("অ্যাক্টিভেট").isEmpty();
            
            if (!isAdminWindow) return;

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

            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
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

    private boolean isFocusGuardNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence txt = node.getText();
        if (txt != null && (txt.toString().contains("Focus Guard") || txt.toString().contains("FocusGuard"))) return true;
        CharSequence desc = node.getContentDescription();
        if (desc != null && (desc.toString().contains("Focus Guard") || desc.toString().contains("FocusGuard"))) return true;
        return isFocusGuardInChildren(node, 0);
    }

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
     */
    private void handleUninstallProtection(AccessibilityEvent event, int eventType) {
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                String txt = getEventText(event).toLowerCase();
                if (txt.contains("uninstall") || txt.contains("আনইনস্টল")) {
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
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private View docsTouchBlocker = null;
    private WindowManager.LayoutParams docsTouchBlockerParams = null;
    private static final int TOUCH_BLOCKER_V_PAD = 120;

    private void showDocsTouchBlocker(Rect buttonRect) {
        // Full-width strip covering the "From web" row with generous vertical padding
        // This ensures the button CANNOT be tapped even if bounds are slightly off
        final int stripTop = Math.max(0, buttonRect.top - TOUCH_BLOCKER_V_PAD);
        final int stripHeight = buttonRect.height() + (TOUCH_BLOCKER_V_PAD * 2);
        Runnable r = () -> {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm == null) return;
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int screenWidth = dm.widthPixels;

                if (docsTouchBlocker == null) {
                    docsTouchBlocker = new View(BlockerService.this);
                    // Semi-transparent dark overlay as visual indicator that option is blocked
                    docsTouchBlocker.setBackgroundColor(Color.argb(160, 18, 18, 28));
                    docsTouchBlocker.setOnTouchListener((v, event1) -> {
                        if (event1.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                            doFromWebClickBlock();
                        }
                        return true;
                    });

                    docsTouchBlockerParams = new WindowManager.LayoutParams(
                        screenWidth,
                        stripHeight,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    );
                    docsTouchBlockerParams.gravity = Gravity.LEFT | Gravity.TOP;
                    docsTouchBlockerParams.x = 0;
                    docsTouchBlockerParams.y = stripTop;

                    wm.addView(docsTouchBlocker, docsTouchBlockerParams);
                } else {
                    docsTouchBlocker.setOnTouchListener((v, event1) -> {
                        if (event1.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                            doFromWebClickBlock();
                        }
                        return true;
                    });
                    boolean needsUpdate = docsTouchBlockerParams.y != stripTop ||
                                          docsTouchBlockerParams.width != screenWidth ||
                                          docsTouchBlockerParams.height != stripHeight;
                    if (needsUpdate) {
                        docsTouchBlockerParams.width = screenWidth;
                        docsTouchBlockerParams.height = stripHeight;
                        docsTouchBlockerParams.x = 0;
                        docsTouchBlockerParams.y = stripTop;
                        wm.updateViewLayout(docsTouchBlocker, docsTouchBlockerParams);
                    }
                }
            } catch (Exception ignored) {}
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private void hideDocsTouchBlocker() {
        Runnable r = () -> {
            if (docsTouchBlocker != null) {
                try {
                    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                    if (wm != null) {
                        wm.removeView(docsTouchBlocker);
                    }
                } catch (Exception ignored) {}
                docsTouchBlocker = null;
                docsTouchBlockerParams = null;
            }
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private AccessibilityNodeInfo findFromWebListItem(AccessibilityNodeInfo root) {
        if (root == null) return null;
        String[] webTerms = {
            "from web", "ওয়েব থেকে", "ওয়েব থেকে", "ওয়েব হতে", "ওয়েব হতে", "वेब से", 
            "desde la web", "de la web", "da web"
        };
        for (String term : webTerms) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(term);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo hit : hits) {
                    if (hit == null) continue;
                    AccessibilityNodeInfo clickableAncestor = findClickableAncestor(hit);
                    if (clickableAncestor != null) {
                        hit.recycle();
                        return clickableAncestor;
                    }
                    return hit;
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int depth = 0;
        while (current != null && depth < 5) {
            if (current.isClickable()) {
                return AccessibilityNodeInfo.obtain(current);
            }
            AccessibilityNodeInfo parent = current.getParent();
            if (current != node) {
                current.recycle();
            }
            current = parent;
            depth++;
        }
        if (current != null && current != node) {
            current.recycle();
        }
        return null;
    }

    // =========================================================================
    // GOOGLE DOCS WEB SEARCH BLOCKING (v2.1)
    // =========================================================================

    private boolean hasBlockedCurrentSearch = false;
    private boolean isFromWebOptionVisible = false;
    private long lastWindowStateChangedTime = 0;



    private void showDefaultDocsTouchBlocker() {
        Runnable r = () -> {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm == null) return;
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int screenWidth = dm.widthPixels;
                int screenHeight = dm.heightPixels;
                int heightPx = (int) (100 * dm.density);
                int topPx = screenHeight - heightPx;

                if (docsTouchBlocker == null) {
                    docsTouchBlocker = new View(BlockerService.this);
                    docsTouchBlocker.setBackgroundColor(Color.argb(160, 18, 18, 28));
                    docsTouchBlocker.setOnTouchListener((v, event1) -> {
                        if (event1.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                            doFromWebClickBlock();
                        }
                        return true;
                    });

                    docsTouchBlockerParams = new WindowManager.LayoutParams(
                        screenWidth,
                        heightPx,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    );
                    docsTouchBlockerParams.gravity = Gravity.LEFT | Gravity.TOP;
                    docsTouchBlockerParams.x = 0;
                    docsTouchBlockerParams.y = topPx;

                    wm.addView(docsTouchBlocker, docsTouchBlockerParams);
                } else {
                    docsTouchBlocker.setOnTouchListener((v, event1) -> {
                        if (event1.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                            doFromWebClickBlock();
                        }
                        return true;
                    });
                    docsTouchBlockerParams.width = screenWidth;
                    docsTouchBlockerParams.height = heightPx;
                    docsTouchBlockerParams.x = 0;
                    docsTouchBlockerParams.y = topPx;
                    wm.updateViewLayout(docsTouchBlocker, docsTouchBlockerParams);
                }
            } catch (Exception ignored) {}
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private void kickOutToGoogleDocsHome() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private void doGoogleDocsBlock(boolean force) {
        if (force || !hasBlockedCurrentSearch) {
            hasBlockedCurrentSearch = true;
            showInstantZeroFlashOverlay();
            kickOutToGoogleDocsHome();
        }
    }

    private void doGoogleDocsBlock() {
        doGoogleDocsBlock(false);
    }

    private boolean isBrowserKillLoopActive = false;
    private long browserKillLoopStartTime = 0;

    private final Runnable browserKillRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isBrowserKillLoopActive) return;
            
            long elapsed = System.currentTimeMillis() - browserKillLoopStartTime;
            boolean isSearchActive = false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    CharSequence rootPkg = root.getPackageName();
                    String rootPkgStr = rootPkg != null ? rootPkg.toString().toLowerCase() : "";
                    boolean isBrowserActive = isMonitoredSearchPackage(rootPkgStr) && !rootPkgStr.equals(OUR_PACKAGE);

                    if (isBrowserActive || checkDocsSearchDeep(root) || hasWebViewInTree(root, 0)) {
                        isSearchActive = true;
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        doGoogleDocsBlock(true);
                    }
                } finally {
                    root.recycle();
                }
            }
            
            if (elapsed > 800 && !isSearchActive) {
                dismissOverlayWithAnimation();
                isBrowserKillLoopActive = false;
                return;
            }
            
            if (isBrowserKillLoopActive) {
                if (elapsed < 3000) {
                    // Ultra-fast polling: 10ms for first 400ms, then 50ms
                    long delay = (elapsed < 400) ? 10L : 50L;
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
        mainHandler.removeCallbacks(browserKillRunnable);
        browserKillRunnable.run();
        
        mainHandler.postDelayed(() -> {
            if (isBrowserKillLoopActive) {
                isBrowserKillLoopActive = false;
                dismissOverlayWithAnimation();
            }
        }, 3000);
    }

    private void stopBrowserKillLoop() {
        isBrowserKillLoopActive = false;
        mainHandler.removeCallbacks(browserKillRunnable);
        dismissOverlayWithAnimation();
    }

    // ===== PREEMPTIVE MENU WATCHDOG =====
    // High-frequency monitor that polls every 25ms while insert image menu is visible
    // Detects browser/webview opening BEFORE accessibility events are delivered
    private boolean isMenuWatchdogActive = false;
    
    private final Runnable menuWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isMenuWatchdogActive) return;
            
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    CharSequence rootPkg = root.getPackageName();
                    String rootPkgStr = rootPkg != null ? rootPkg.toString().toLowerCase() : "";
                    
                    // If a browser is now in the foreground, block immediately
                    if (isMonitoredSearchPackage(rootPkgStr)) {
                        isMenuWatchdogActive = false;
                        showInstantZeroFlashOverlay();
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        startBrowserKillLoop();
                        return;
                    }
                    
                    // Check for webview appearing within Docs
                    if (isGoogleDocsPackage(rootPkgStr) && hasWebViewInTree(root, 0)) {
                        isMenuWatchdogActive = false;
                        showInstantZeroFlashOverlay();
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        startBrowserKillLoop();
                        return;
                    }
                } finally {
                    root.recycle();
                }
            }
            
            if (isMenuWatchdogActive) {
                mainHandler.postDelayed(this, 25);
            }
        }
    };
    
    private void startMenuWatchdog() {
        if (isMenuWatchdogActive) return;
        isMenuWatchdogActive = true;
        mainHandler.postDelayed(menuWatchdogRunnable, 25);
    }
    
    private void stopMenuWatchdog() {
        isMenuWatchdogActive = false;
        mainHandler.removeCallbacks(menuWatchdogRunnable);
    }

    private void doFromWebClickBlock() {
        hasBlockedCurrentSearch = false;
        stopMenuWatchdog();
        // Show full-screen opaque overlay IMMEDIATELY
        showInstantZeroFlashOverlay();
        // Double BACK for maximum kill speed
        performGlobalAction(GLOBAL_ACTION_BACK);
        mainHandler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 30);
        startBrowserKillLoop();
    }

    private boolean isGoogleDocsSearchText(String text) {
        String s = text.toLowerCase();
        return s.contains("search your docs and the web") ||
               s.contains("আপনার ডক্স এবং ওযেব") ||
               s.contains("search images") ||
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
        if (isGoogleDocsPackage(pkgName)) {
            // Check if search panel is open on any change event (content or state change)
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                
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

            // Manage Touch Blocker Overlay and Menu Watchdog on window content/state changes
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        if (isInsertImageMenuOpen(root)) {
                            AccessibilityNodeInfo fromWebNode = findFromWebListItem(root);
                            if (fromWebNode != null) {
                                Rect rect = new Rect();
                                fromWebNode.getBoundsInScreen(rect);
                                fromWebNode.recycle();
                                if (rect.width() > 0 && rect.height() > 0) {
                                    showDocsTouchBlocker(rect);
                                } else {
                                    showDefaultDocsTouchBlocker();
                                }
                            } else {
                                showDefaultDocsTouchBlocker();
                            }
                            // Start pre-emptive watchdog to catch browser opening instantly
                            startMenuWatchdog();
                        } else {
                            hideDocsTouchBlocker();
                            stopMenuWatchdog();
                        }
                    } finally {
                        root.recycle();
                    }
                }
            }
        }

        // Ignore text selection popup toolbar events
        CharSequence toolbarEvClass = event.getClassName();
        if (toolbarEvClass != null) {
            String clsStr = toolbarEvClass.toString();
            if (clsStr.contains("ActionMode") || clsStr.contains("FloatingToolbar")) {
                return;
            }
        }

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

        // ===== ABSOLUTE ZERO-IPC WEBVIEW KICKOUT =====
        if (isBrowserKillLoopActive) {
            if (!isGoogleDocsPackage(pkgName)) {
                doGoogleDocsBlock(true);
                return;
            }
            
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                CharSequence evClass = event.getClassName();
                if (evClass != null) {
                    String clsStr = evClass.toString();
                    if (!clsStr.contains("Editor") && !clsStr.contains("MainActivity") && !clsStr.contains("HomeActivity")) {
                        doGoogleDocsBlock(true);
                        return;
                    }
                }
            }
            
            CharSequence evClass = event.getClassName();
            if (evClass != null) {
                String clsStr = evClass.toString();
                if (clsStr.contains("WebView") || clsStr.contains("WebSearch") || 
                    clsStr.contains("CustomTab") || clsStr.contains("ExploreActivity")) {
                    doGoogleDocsBlock(true);
                    return;
                }
            }
        }

        // ===== EVENT-DRIVEN ZERO-FLASH WEBVIEW INTERCEPTION =====
        if (isBrowserKillLoopActive) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    try {
                        if (hasWebViewInTree(root, 0)) {
                            doGoogleDocsBlock(true);
                            return;
                        }
                    } finally {
                        root.recycle();
                    }
                }
            }
        }

        // ===== FALLBACK WATCHDOG =====
        boolean isWatchdogTriggered = false;
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            isWatchdogTriggered = true;
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isGoogleDocsPackage(pkgName)) {
            CharSequence evClass = event.getClassName();
            if (evClass != null && evClass.toString().contains("WebView")) {
                isWatchdogTriggered = true;
            }
        }

        if (!isBrowserKillLoopActive && isWatchdogTriggered) {
            if (!isGoogleDocsPackage(pkgName)) {
                return;
            }

            CharSequence evClass = event.getClassName();
            String clsStr = evClass != null ? evClass.toString() : "";
            
            if (clsStr.contains("ExploreActivity") || clsStr.contains("WebSearch") || clsStr.contains("CustomTab")) {
                doGoogleDocsBlock(true);
                return;
            }
            
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

        // ===== "From web" click interception =====
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && isGoogleDocsPackage(pkgName)) {
            boolean isFromWebClick = false;

            String eventTxt = getEventText(event).toLowerCase();
            if (eventTxt.contains("from web") || 
                eventTxt.contains("ওয়েব থেকে") || eventTxt.contains("ওয়েব থেকে") ||
                eventTxt.contains("ওয়েব হতে") || eventTxt.contains("ওয়েব হতে") ||
                eventTxt.contains("वेब से") || 
                eventTxt.contains("desde la web") || eventTxt.contains("de la web") ||
                eventTxt.contains("da web")) {
                isFromWebClick = true;
            }

            if (!isFromWebClick) {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    if (isFromWebClickNode(source)) {
                        isFromWebClick = true;
                    }
                    source.recycle();
                }
            }

            if (!isFromWebClick) {
                if (!isFromWebOptionVisible) {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        try {
                            isFromWebOptionVisible = isInsertImageMenuOpen(root);
                        } finally {
                            root.recycle();
                        }
                    }
                }
                if (isFromWebOptionVisible) {
                    AccessibilityNodeInfo source = event.getSource();
                    if (source == null) {
                        isFromWebClick = true;
                    } else {
                        source.recycle();
                    }
                }
            }
            if (isFromWebClick) {
                doFromWebClickBlock();
                isFromWebOptionVisible = false;
            } else {
                isFromWebOptionVisible = false;
            }
        }

        if (isBrowserKillLoopActive) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return;
                try {
                    if (checkDocsSearchDeep(root)) {
                        doGoogleDocsBlock();
                    }
                } finally {
                    root.recycle();
                }
            }
        }
    }

    private boolean isWebSearchExplicit = false;
    private boolean hasWebDomain = false;
    private boolean hasFormattingBar = false;
    private boolean hasHamburgerMenu = false;
    private boolean hasFAB = false;

    private boolean checkDocsSearchDeep(AccessibilityNodeInfo root) {
        isWebSearchExplicit = false;
        hasWebDomain = false;
        hasFormattingBar = false;
        hasHamburgerMenu = false;
        hasFAB = false;
        
        scanDocsUIOptimized(root, 0);
        
        if (hasFormattingBar || hasHamburgerMenu || hasFAB) {
            return false;
        }
        
        return isWebSearchExplicit || hasWebDomain;
    }

    private boolean scanDocsUIOptimized(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 12) return false;
        
        if (node.getClassName() != null) {
            String cls = node.getClassName().toString();
            if (cls.contains("FloatingActionButton")) {
                if (node.isVisibleToUser()) {
                    hasFAB = true;
                }
            }
        }
        
        CharSequence txt = node.getText();
        if (txt != null) {
            String s = txt.toString().toLowerCase().trim();
            if (isGoogleDocsSearchText(s)) {
                isWebSearchExplicit = true;
            }
            if (s.contains("wikimedia.org") || s.contains("flickr.com") || s.contains("pixabay.com") || s.contains("openclipart.org") || s.contains("openverse.engineering")) {
                hasWebDomain = true;
            }
        }
        
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String s = desc.toString().toLowerCase().trim();
            if (isGoogleDocsSearchText(s)) {
                isWebSearchExplicit = true;
            }
            if (s.equals("bold") || s.equals("বোল্ড") || s.equals("italic") || s.equals("ইটালিক") || s.equals("underline") || s.equals("আন্ডারলাইন") || s.equals("edit") || s.equals("সম্পাদনা করুন")) {
                if (node.isVisibleToUser()) {
                    hasFormattingBar = true;
                }
            }
            if (s.contains("drawer") || s.contains("menu") || s.contains("navigation") || s.contains("মেনু") || s.contains("ড্রয়ার")) {
                if (node.isVisibleToUser()) {
                    hasHamburgerMenu = true;
                }
            }
        }
        
        if ((isWebSearchExplicit || hasWebDomain) && (hasFormattingBar || hasHamburgerMenu || hasFAB)) {
            return true;
        }
        
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

    private boolean isFromWebClickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        int depth = 0;
        try {
            while (current != null && depth < 3) {
                CharSequence cls = current.getClassName();
                if (cls != null) {
                    String clsStr = cls.toString().toLowerCase();
                    if (clsStr.contains("recyclerview") || 
                        clsStr.contains("listview") || 
                        clsStr.contains("gridview") || 
                        clsStr.contains("scrollview") ||
                        clsStr.contains("viewpager")) {
                        break;
                    }
                }
                
                if (isFromWebNodeOrChildren(current, 0)) {
                    return true;
                }
                
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
                depth++;
            }
        } finally {
            if (current != null) {
                current.recycle();
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

    private boolean isInsertImageMenuOpen(AccessibilityNodeInfo root) {
        if (root == null) return false;
        
        boolean hasFromWeb = false;
        String[] webTerms = {
            "from web", "ওয়েব থেকে", "ওয়েব থেকে", "ওয়েব হতে", "ওয়েব হতে", "वेब से", 
            "desde la web", "de la web", "da web"
        };
        for (String term : webTerms) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(term);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) n.recycle();
                hasFromWeb = true;
                break;
            }
        }
        if (!hasFromWeb) return false;

        String[] companionTerms = {
            "from photos", "from camera", "ফটো থেকে", "ক্যামেরা থেকে", 
            "photos", "camera", "ফটো", "ক্যামেরা"
        };
        boolean hasCompanion = false;
        for (String term : companionTerms) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(term);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) n.recycle();
                hasCompanion = true;
                break;
            }
        }
        
        return hasCompanion;
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

    private View ghostShieldView = null;
    private WindowManager.LayoutParams ghostShieldParams = null;
    private boolean isGhostShieldActive = false;

    private void initGhostShield() {
        Runnable r = () -> {
            try {
                if (ghostShieldView != null) return;
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm == null) return;

                ghostShieldView = new View(BlockerService.this);
                ghostShieldView.setBackgroundColor(Color.TRANSPARENT);

                ghostShieldParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                );

                wm.addView(ghostShieldView, ghostShieldParams);
            } catch (Exception ignored) {}
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private void destroyGhostShield() {
        if (ghostShieldView != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) {
                    wm.removeView(ghostShieldView);
                }
            } catch (Exception ignored) {}
            ghostShieldView = null;
            ghostShieldParams = null;
        }
    }

    private synchronized void showInstantZeroFlashOverlay() {
        if (ghostShieldView == null) {
            initGhostShield();
        }
        isGhostShieldActive = true;
        Runnable r = () -> {
            try {
                if (ghostShieldView == null || ghostShieldParams == null) return;
                
                ghostShieldView.animate().cancel();
                ghostShieldView.setBackgroundColor(Color.parseColor("#1A1A24"));
                ghostShieldView.setAlpha(1f);

                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) {
                    ghostShieldParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    wm.updateViewLayout(ghostShieldView, ghostShieldParams);
                }
            } catch (Exception ignored) {}
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private synchronized void dismissOverlayWithAnimation() {
        if (ghostShieldView == null || !isGhostShieldActive) return;
        isGhostShieldActive = false;
        Runnable r = () -> {
            try {
                if (ghostShieldView == null || ghostShieldParams == null) return;
                
                ghostShieldView.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction(() -> {
                        try {
                            if (ghostShieldView != null && ghostShieldParams != null) {
                                ghostShieldView.setBackgroundColor(Color.TRANSPARENT);
                                ghostShieldView.setAlpha(1f);
                                
                                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                                if (wm != null) {
                                    ghostShieldParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                                    wm.updateViewLayout(ghostShieldView, ghostShieldParams);
                                }
                            }
                        } catch (Exception ignored) {}
                    })
                    .start();
            } catch (Exception e) {
                try {
                    if (ghostShieldView != null && ghostShieldParams != null) {
                        ghostShieldView.setBackgroundColor(Color.TRANSPARENT);
                        ghostShieldView.setAlpha(1f);
                        
                        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                        if (wm != null) {
                            ghostShieldParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                            wm.updateViewLayout(ghostShieldView, ghostShieldParams);
                        }
                    }
                } catch (Exception ignored) {}
            }
        };

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

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
