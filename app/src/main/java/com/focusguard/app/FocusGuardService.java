package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FocusGuardService extends AccessibilityService {

    private static final String TAG = "FocusGuardService";
    private static final String PKG_GOOGLE_DOCS = "com.google.android.apps.docs.editors.docs";
    private static final String DOCS_ID_PREFIX = "com.google.android.apps.docs.editors.docs:id/";
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
               "com.sec.android.app.sbrowser".equals(pkgName) ||
               "org.mozilla.firefox".equals(pkgName) ||
               pkgName.contains("browser") ||
               pkgName.contains("firefox") ||
               pkgName.contains("opera") ||
               pkgName.contains("searchbox") ||
               pkgName.contains("websearch") ||
               pkgName.contains("webview");
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread scannerThread;
    private Handler scannerHandler;
    private PreferenceManager prefManager;
    private static FocusGuardService instance;

    public static FocusGuardService getInstance() { return instance; }

    private synchronized Handler getScannerHandler() {
        if (scannerThread == null || !scannerThread.isAlive()) {
            scannerThread = new HandlerThread("FocusGuardScanner");
            scannerThread.start();
            scannerHandler = new Handler(scannerThread.getLooper());
        }
        return scannerHandler;
    }

    private synchronized void shutdownScannerThread() {
        if (scannerHandler != null) {
            scannerHandler.removeCallbacksAndMessages(null);
            scannerHandler = null;
        }
        if (scannerThread != null) {
            scannerThread.quitSafely();
            scannerThread = null;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefManager = new PreferenceManager(this);
        getScannerHandler();
        stopWebGuardLoop();
        destroyFromWebIntercept();
        destroyLoadingBarShield();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopWebGuardLoop();
        dismissZeroFlashCurtain();
        destroyFromWebIntercept();
        destroyLoadingBarShield();
        shutdownScannerThread();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopWebGuardLoop();
        dismissZeroFlashCurtain();
        destroyFromWebIntercept();
        destroyLoadingBarShield();
        shutdownScannerThread();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    public void disableService() {
        stopWebGuardLoop();
        dismissZeroFlashCurtain();
        destroyFromWebIntercept();
        destroyLoadingBarShield();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            disableSelf();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefManager == null || event == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgName = pkg.toString().toLowerCase();

        // 1. Never process our own package's accessibility events
        if (pkgName.equals(OUR_PACKAGE)) return;

        int eventType = event.getEventType();

        // 2. ISOLATION & SESSION TRACKING
        if (!isGoogleDocsPackage(pkgName) && !isMonitoredSearchPackage(pkgName)) {
            boolean hasDocsSessionOrOverlay = (lastGoogleDocsActiveTime > 0L) ||
                                              isWebGuardActiveFlag ||
                                              isFlashCurtainAttached ||
                                              isFromWebInterceptAttached ||
                                              isLoadingShieldAttached;

            if (hasDocsSessionOrOverlay && hasLeftDocsToAnotherApp(event, eventType, pkgName)) {
                Log.d(TAG, "leftDocsApp true for pkg=" + pkgName + " eventType=" + eventType);
                lastGoogleDocsActiveTime = 0L;
                lastDocsClickTime = 0L;
                stopWebGuardLoop();
                dismissZeroFlashCurtain();
                destroyFromWebIntercept();
                if (isLoadingShieldAttached) {
                    destroyLoadingBarShield();
                }
            }
        }

        // GLOBAL PROTECTION SCAN (Gated to Settings/Installer for maximum performance)
        boolean isSettingsPkg = pkgName.contains("settings");
        boolean isInstallerPkg = pkgName.contains("packageinstaller") || pkgName.contains("installer");
        if (isSettingsPkg || isInstallerPkg) {
            if (prefManager.isAccessibilityProtected()) {
                handleAccessibilityProtection(event, eventType, pkgName);
            }
            if (prefManager.isDeviceAdminProtected()) {
                if (isSettingsPkg) {
                    handleAdminProtection(event, eventType);
                }
                handleUninstallProtection(event, eventType);
            }
        }

        // Google Docs Web search & Docs-launched browser blocker
        if (prefManager.isGoogleDocsBlocked()) {
            if (isGoogleDocsPackage(pkgName)) {
                lastGoogleDocsActiveTime = SystemClock.uptimeMillis();
                handleGoogleDocs(event, eventType, pkgName);
            } else if (isMonitoredSearchPackage(pkgName)) {
                handleMonitoredSearchPackage(event, eventType, pkgName);
            }
        }
    }

    /**
     * ACCESSIBILITY PROTECTION
     * Triggers when user attempts to access FocusGuard in Accessibility Settings to disable it.
     */
    private void handleAccessibilityProtection(AccessibilityEvent event, int eventType, String pkgName) {
        boolean isSettings = pkgName.contains("settings");

        if (eventType != AccessibilityEvent.TYPE_VIEW_CLICKED && 
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !(eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isSettings)) return;

        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
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

            CharSequence evtDesc = event.getContentDescription();
            if (evtDesc != null) {
                String d = evtDesc.toString();
                if (d.contains("Focus Guard") || d.contains("FocusGuard")) {
                    triggerKickOut();
                    return;
                }
            }

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

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
           (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && isSettings)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                boolean isAccessibilityWindow = !root.findAccessibilityNodeInfosByText("Accessibility").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("এক্সেসিবিলিটি").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Installed apps").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Installed services").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("ইনস্টল করা অ্যাপ").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Use Focus Guard").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Use FocusGuard").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Shortcut").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("শর্টকাট").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("Focus Guard Blocker").isEmpty() ||
                                               !root.findAccessibilityNodeInfosByText("FocusGuard Blocker").isEmpty();
                
                if (isAccessibilityWindow && isFocusGuardDetailScreen(root)) {
                    triggerKickOut();
                }
            } finally {
                root.recycle();
            }
        }
    }

    private boolean isFocusGuardDetailScreen(AccessibilityNodeInfo root) {
        String[] ignoreTitles = {
            "Accessibility", "Downloaded services", "এক্সেসিবিলিটি", "ডাউনলোড করা পরিষেবা",
            "Installed apps", "Installed services", "ইনস্টল করা অ্যাপ",
            "App info", "অ্যাপ তথ্য", "Permissions", "Storage"
        };
        for (String title : ignoreTitles) {
            if (!root.findAccessibilityNodeInfosByText(title).isEmpty()) return false;
        }

        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText("Focus Guard");
        if (hits == null || hits.isEmpty()) {
            hits = root.findAccessibilityNodeInfosByText("FocusGuard");
        }
        if (hits == null || hits.isEmpty()) return false;
        for (AccessibilityNodeInfo n : hits) n.recycle();

        boolean isAccessibilityContext = !root.findAccessibilityNodeInfosByText("Use service").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("Use Focus Guard").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("Use FocusGuard").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("ব্যবহার").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("Shortcut").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("শর্টকাট").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("Focus Guard Blocker").isEmpty() ||
                                        !root.findAccessibilityNodeInfosByText("FocusGuard Blocker").isEmpty();
        
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
     * Restricts removing FocusGuard as device administrator.
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
     * Intercepts and blocks clicks on "Uninstall" button for FocusGuard.
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
        Intent intent = new Intent(this, BlockActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    // =========================================================================
    // GOOGLE DOCS WEB SEARCH BLOCKING (ZERO-DROP, ZERO-FLASH GUARD ENGINE)
    // =========================================================================

    private static final long WEB_GUARD_DURATION_MS = 2500L;
    private static final long WEB_GUARD_MAX_BURST_MS = 4500L;
    private static final long BACK_COOLDOWN_MS = 95L;
    private static final long SCAN_THROTTLE_MS = 35L;
    private static final long DEEP_SCAN_INTERVAL_MS = 150L;
    private static final long LOADING_SCAN_INTERVAL_MS = 250L;
    private static final long HOLD_DISMISS_MS = 10_000L;

    private volatile long lastBlockedToastTime = 0L;
    private volatile long lastHoldHintToastTime = 0L;
    private volatile long lastStateScanTime = 0L;
    private volatile long lastDeepScanTime = 0L;
    private volatile long lastLoadingBarScanTime = 0L;
    private volatile long lastGoogleDocsActiveTime = 0L;
    private volatile long lastDocsClickTime = 0L;
    private volatile long lastActiveBackTime = 0L;
    private volatile long lastInsertBackClickTime = 0L;
    private volatile long webGuardUntil = 0L;
    private volatile long webGuardMaxEndTime = 0L;
    private volatile long webGuardStartTime = 0L;
    private volatile long flashCurtainCooldownUntil = 0L;
    private volatile long holdDownTime = 0L;
    private volatile boolean isWebGuardActiveFlag = false;
    private volatile boolean isDeferredScanScheduled = false;
    private volatile boolean wasSearchOrBrowserVisible = false;

    private final Runnable deferredScanRunnable = new Runnable() {
        @Override
        public void run() {
            isDeferredScanScheduled = false;
            if (prefManager == null || !prefManager.isGoogleDocsBlocked()) return;
            long now = SystemClock.uptimeMillis();
            List<AccessibilityNodeInfo> docsRoots = collectGoogleDocsRoots(null);
            try {
                lastStateScanTime = now;
                boolean allowDeepWalk = isWebGuardActive() || (now - lastDeepScanTime >= DEEP_SCAN_INTERVAL_MS);
                if (allowDeepWalk) lastDeepScanTime = now;
                if (isDocsSearchScreenActive(docsRoots, null, allowDeepWalk)) {
                    closeDocsSearchDrawer(docsRoots);
                    return;
                }
                if (!docsRoots.isEmpty() && !isFlashCurtainAttached && (now - lastLoadingBarScanTime >= LOADING_SCAN_INTERVAL_MS)) {
                    lastLoadingBarScanTime = now;
                    updateLoadingBarShield(docsRoots);
                }
                if (!docsRoots.isEmpty() && !isFlashCurtainAttached) {
                    updateFromWebIntercept(docsRoots);
                }
            } finally {
                recycleNodeList(docsRoots);
            }
        }
    };

    private final Runnable webGuardRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isWebGuardActiveFlag) return;
            long now = SystemClock.uptimeMillis();

            if (prefManager == null || !prefManager.isGoogleDocsBlocked()) {
                stopWebGuardLoop();
                return;
            }

            boolean browserVisible = isMonitoredBrowserWindowVisible();
            boolean docsSearchVisible = false;
            List<AccessibilityNodeInfo> docsRoots = null;
            try {
                if (!browserVisible) {
                    docsRoots = collectGoogleDocsRoots(null);
                    boolean allowDeepWalk = (now - lastDeepScanTime >= DEEP_SCAN_INTERVAL_MS);
                    if (allowDeepWalk) lastDeepScanTime = now;
                    docsSearchVisible = isDocsSearchScreenActive(docsRoots, null, allowDeepWalk);
                }

                boolean activeThreatVisible = browserVisible || docsSearchVisible;
                if (activeThreatVisible) {
                    boolean newlyAppeared = !wasSearchOrBrowserVisible;
                    wasSearchOrBrowserVisible = true;
                    showZeroFlashCurtain();
                    if (browserVisible) {
                        performActiveBack(newlyAppeared);
                    } else {
                        closeSearchInDocs(docsRoots, newlyAppeared);
                    }
                    showWebBlockedToast();
                    webGuardUntil = Math.min(webGuardMaxEndTime, Math.max(webGuardUntil, now + 1200L));
                } else {
                    wasSearchOrBrowserVisible = false;
                }
            } finally {
                recycleNodeList(docsRoots);
            }

            if (now >= webGuardMaxEndTime || (now >= webGuardUntil && !browserVisible && !docsSearchVisible)) {
                stopWebGuardLoop();
                return;
            }

            long elapsed = now - webGuardStartTime;
            long nextDelay = (elapsed < 800L) ? 50L : 100L;
            getScannerHandler().postDelayed(this, nextDelay);
        }
    };

    private boolean isWebGuardActive() {
        return isWebGuardActiveFlag && SystemClock.uptimeMillis() < webGuardUntil;
    }

    private void startWebGuardLoop() {
        long now = SystemClock.uptimeMillis();
        webGuardMaxEndTime = now + WEB_GUARD_MAX_BURST_MS;
        webGuardUntil = Math.min(webGuardMaxEndTime, Math.max(webGuardUntil, now + WEB_GUARD_DURATION_MS));
        if (!isWebGuardActiveFlag) {
            isWebGuardActiveFlag = true;
            webGuardStartTime = now;
            Handler sh = getScannerHandler();
            sh.removeCallbacks(webGuardRunnable);
            sh.postDelayed(webGuardRunnable, 50L);
        }
    }

    private void stopWebGuardLoop() {
        isWebGuardActiveFlag = false;
        webGuardUntil = 0L;
        webGuardMaxEndTime = 0L;
        wasSearchOrBrowserVisible = false;
        isDeferredScanScheduled = false;
        Handler sh = scannerHandler;
        if (sh != null) {
            sh.removeCallbacks(webGuardRunnable);
            sh.removeCallbacks(deferredScanRunnable);
        }
    }

    private void scheduleDeferredScan(long delayMs) {
        if (isDeferredScanScheduled) return;
        isDeferredScanScheduled = true;
        getScannerHandler().postDelayed(deferredScanRunnable, Math.max(0L, delayMs));
    }

    private void performActiveBack(boolean forceImmediate) {
        long now = SystemClock.uptimeMillis();
        if (forceImmediate || (now - lastActiveBackTime >= BACK_COOLDOWN_MS)) {
            lastActiveBackTime = now;
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    private boolean isMonitoredBrowserWindowVisible() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0) sw = 720;
        if (sh <= 0) sh = 1600;

        try {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            if (activeRoot != null) {
                try {
                    CharSequence pkg = activeRoot.getPackageName();
                    if (pkg != null && isMonitoredSearchPackage(pkg.toString().toLowerCase())) {
                        return true;
                    }
                } finally {
                    activeRoot.recycle();
                }
            }
        } catch (Exception ignored) {}

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    int type = w.getType();
                    if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                        type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                        type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        continue;
                    }
                    if (!w.isActive() && !w.isFocused()) {
                        continue;
                    }
                    AccessibilityNodeInfo wRoot = null;
                    try {
                        wRoot = w.getRoot();
                        if (wRoot != null) {
                            CharSequence pkg = wRoot.getPackageName();
                            if (pkg != null && isMonitoredSearchPackage(pkg.toString().toLowerCase())) {
                                Rect b = new Rect();
                                wRoot.getBoundsInScreen(b);
                                if (isRectOnScreen(b, sw, sh, (int) (sw * 0.35f), (int) (sh * 0.2f))) {
                                    return true;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (wRoot != null) {
                            try { wRoot.recycle(); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isRectOnScreen(Rect r, int sw, int sh, int minW, int minH) {
        if (r == null || r.isEmpty()) return false;
        int left = Math.max(0, r.left);
        int top = Math.max(0, r.top);
        int right = Math.min(sw, r.right);
        int bottom = Math.min(sh, r.bottom);
        return (right - left) >= minW && (bottom - top) >= minH;
    }

    private void handleMonitoredSearchPackage(AccessibilityEvent event, int eventType, String pkgName) {
        long now = SystemClock.uptimeMillis();
        boolean launchedFromDocs = lastGoogleDocsActiveTime > 0 &&
                                   (now - lastGoogleDocsActiveTime) < 2500L &&
                                   ((now - lastDocsClickTime) < 2000L || isEmbeddedBrowserOrCustomTab(pkgName, event));
        if (!isWebGuardActive() && !launchedFromDocs) {
            return;
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            boolean newlyAppeared = !wasSearchOrBrowserVisible;
            wasSearchOrBrowserVisible = true;
            showZeroFlashCurtain();
            startWebGuardLoop();
            // Fire multiple immediate BACKs to force-close browser/custom-tab ASAP
            performGlobalAction(GLOBAL_ACTION_BACK);
            lastActiveBackTime = SystemClock.uptimeMillis();
            // Schedule a second BACK after 60ms in case the first was swallowed
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isWebGuardActive()) {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                }
            }, 60L);
            showWebBlockedToast();
        }
    }

    private boolean isEmbeddedBrowserOrCustomTab(String pkgName, AccessibilityEvent event) {
        if ("com.google.android.googlequicksearchbox".equals(pkgName) ||
            "com.google.android.gms".equals(pkgName) ||
            pkgName.contains("webview") ||
            pkgName.contains("searchbox") ||
            pkgName.contains("websearch")) {
            return true;
        }
        if (event != null && event.getClassName() != null) {
            String cls = event.getClassName().toString().toLowerCase();
            if (cls.contains("customtab") || cls.contains("webview") || cls.contains("search")) {
                return true;
            }
        }
        return false;
    }

    private void showWebBlockedToast() {
        long now = System.currentTimeMillis();
        if (now - lastBlockedToastTime > 2500L) {
            lastBlockedToastTime = now;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(FocusGuardService.this, "ওয়েব অনুসন্ধান ব্লক করা আছে", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * Collects distinct root AccessibilityNodeInfo objects belonging strictly to Google Docs.
     * Caller MUST recycle every returned node using recycleNodeList().
     */
    private List<AccessibilityNodeInfo> collectGoogleDocsRoots(AccessibilityEvent event) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        Set<Integer> seenWindowIds = new HashSet<>();
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0) sw = 720;
        if (sh <= 0) sh = 1600;

        // 1. Check active window root first (fastest, ~5ms)
        try {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            addIfDocsRoot(activeRoot, seenWindowIds, roots);
        } catch (Exception ignored) {}

        // 2. Check visible on-screen Google Docs windows (skip offscreen parked PopupWindows with 0 IPC!)
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                Rect wb = new Rect();
                for (AccessibilityWindowInfo w : windows) {
                    if (roots.size() >= 2) break;
                    collectDocsWindowRecursive(w, seenWindowIds, roots, sw, sh, wb);
                }
            }
        } catch (Exception ignored) {}

        return roots;
    }

    private void collectDocsWindowRecursive(AccessibilityWindowInfo w, Set<Integer> seenIds, List<AccessibilityNodeInfo> out,
                                            int sw, int sh, Rect wb) {
        if (w == null || out.size() >= 2) return;
        int type = w.getType();
        if (type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            type != AccessibilityWindowInfo.TYPE_SYSTEM &&
            type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
            try {
                w.getBoundsInScreen(wb);
                if (isRectOnScreen(wb, sw, sh, 20, 20)) {
                    AccessibilityNodeInfo wRoot = w.getRoot();
                    addIfDocsRoot(wRoot, seenIds, out);
                }
            } catch (Exception ignored) {}
        }
        int childCount = w.getChildCount();
        for (int i = 0; i < childCount && out.size() < 2; i++) {
            try {
                collectDocsWindowRecursive(w.getChild(i), seenIds, out, sw, sh, wb);
            } catch (Exception ignored) {}
        }
    }

    private AccessibilityNodeInfo walkUpToRoot(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        int steps = 0;
        while (steps < 20) {
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) break;
            current.recycle();
            current = parent;
            steps++;
        }
        return current;
    }

    private void addIfDocsRoot(AccessibilityNodeInfo root, Set<Integer> seenIds, List<AccessibilityNodeInfo> out) {
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !isGoogleDocsPackage(pkg.toString().toLowerCase())) {
            root.recycle();
            return;
        }
        int winId = root.getWindowId();
        if (winId >= 0 && !seenIds.add(winId)) {
            root.recycle();
            return;
        }
        out.add(root);
    }

    private void recycleNodeList(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo n : nodes) {
            if (n != null) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
        }
        nodes.clear();
    }

    private boolean clickInsertToolBackButton(List<AccessibilityNodeInfo> roots) {
        if (roots == null) return false;
        for (AccessibilityNodeInfo root : roots) {
            if (root == null) continue;
            try {
                List<AccessibilityNodeInfo> backBtns = root.findAccessibilityNodeInfosByViewId(DOCS_ID_PREFIX + "insert_tool_back_button");
                if (backBtns != null && !backBtns.isEmpty()) {
                    boolean clickedAny = false;
                    for (AccessibilityNodeInfo b : backBtns) {
                        if (b.isVisibleToUser() && b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            clickedAny = true;
                        }
                        b.recycle();
                    }
                    if (clickedAny) return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void closeSearchInDocs(List<AccessibilityNodeInfo> docsRoots, boolean forceImmediate) {
        long now = SystemClock.uptimeMillis();
        if (now - lastInsertBackClickTime >= 180L && clickInsertToolBackButton(docsRoots)) {
            lastInsertBackClickTime = now;
            return;
        }
        performActiveBack(forceImmediate);
    }

    private boolean hasLeftDocsToAnotherApp(AccessibilityEvent event, int eventType, String pkgName) {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return false;
        }
        boolean isImeOrSystemWindow = "android".equals(pkgName) ||
                                      pkgName.contains("inputmethod") ||
                                      pkgName.contains("honeyboard") ||
                                      pkgName.contains("gboard") ||
                                      pkgName.contains("latin") ||
                                      pkgName.contains("keyboard");
        if (isImeOrSystemWindow) return false;

        boolean isSystemUi = pkgName.contains("systemui");
        if (isSystemUi && eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return false;
        }

        // Only treat this as leaving Google Docs if Docs/browser is no longer the active/focused app window.
        // Floating overlays from other apps/services must NOT dismiss the curtain or intercept.
        try {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            if (activeRoot != null) {
                try {
                    CharSequence activePkg = activeRoot.getPackageName();
                    if (activePkg != null) {
                        String aPkg = activePkg.toString().toLowerCase();
                        if (isGoogleDocsPackage(aPkg) || isMonitoredSearchPackage(aPkg)) {
                            return false;
                        }
                    }
                } finally {
                    activeRoot.recycle();
                }
            }
        } catch (Exception ignored) {}

        boolean hasActiveAppWindow = false;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    if (!w.isActive() && !w.isFocused()) continue;
                    AccessibilityNodeInfo wRoot = null;
                    try {
                        wRoot = w.getRoot();
                        if (wRoot != null) {
                            CharSequence wPkg = wRoot.getPackageName();
                            if (wPkg != null) {
                                String wp = wPkg.toString().toLowerCase();
                                if (isGoogleDocsPackage(wp) || isMonitoredSearchPackage(wp)) {
                                    return false;
                                }
                                if (!wp.contains("systemui")) {
                                    hasActiveAppWindow = true;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (wRoot != null) {
                            try { wRoot.recycle(); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (isSystemUi) {
            // SystemUI clicks (e.g. notification shade) only leave Docs if another real app window took focus
            return hasActiveAppWindow;
        }
        return hasActiveAppWindow;
    }

    private void closeDocsSearchDrawer(List<AccessibilityNodeInfo> docsRoots) {
        Log.d(TAG, "closeDocsSearchDrawer triggered");
        hideFromWebIntercept();
        boolean newlyAppeared = !wasSearchOrBrowserVisible;
        wasSearchOrBrowserVisible = true;
        showZeroFlashCurtain();
        startWebGuardLoop();
        closeSearchInDocs(docsRoots, newlyAppeared);
        showWebBlockedToast();
    }

    private void onFromWebClicked(List<AccessibilityNodeInfo> docsRoots) {
        Log.d(TAG, "onFromWebClicked triggered");
        hideFromWebIntercept();
        showZeroFlashCurtain();
        startWebGuardLoop();
        if (isDocsSearchScreenActive(docsRoots, null, true)) {
            wasSearchOrBrowserVisible = true;
            closeSearchInDocs(docsRoots, true);
        } else {
            // Dismiss the Image palette immediately without blocking future active search-screen BACKs
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
        showWebBlockedToast();
    }

    private boolean isDocsSearchEventClass(AccessibilityEvent event) {
        if (event == null || event.getClassName() == null) return false;
        String clsStr = event.getClassName().toString();
        return clsStr.contains("WebView") ||
               clsStr.contains("WebSearch") ||
               clsStr.contains("CustomTab") ||
               clsStr.contains("ExploreActivity") ||
               clsStr.contains("InsertTool") ||
               clsStr.contains("ImageSearch");
    }

    private boolean isQuickActiveDrawerVisible() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            try {
                CharSequence pkg = root.getPackageName();
                if (pkg == null || !isGoogleDocsPackage(pkg.toString().toLowerCase())) {
                    return false;
                }
                int sw = getResources().getDisplayMetrics().widthPixels;
                int sh = getResources().getDisplayMetrics().heightPixels;
                if (sw <= 0) sw = 720;
                if (sh <= 0) sh = 1600;
                String[] fastIds = {
                    DOCS_ID_PREFIX + "insert_tool_drawer_container",
                    DOCS_ID_PREFIX + "insert_tool_drawer"
                };
                for (String id : fastIds) {
                    List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
                    if (list != null && !list.isEmpty()) {
                        boolean vis = false;
                        for (AccessibilityNodeInfo n : list) {
                            if (!vis && n.isVisibleToUser()) {
                                Rect b = new Rect();
                                n.getBoundsInScreen(b);
                                if (isRectOnScreen(b, sw, sh, 8, 8)) vis = true;
                            }
                            n.recycle();
                        }
                        if (vis) return true;
                    }
                }
            } finally {
                root.recycle();
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void handleGoogleDocs(AccessibilityEvent event, int eventType, String pkgName) {
        // Fast ignore for text selection floating context menus unless it's a click event
        if (eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            CharSequence toolbarEvClass = event.getClassName();
            if (toolbarEvClass != null) {
                String clsStr = toolbarEvClass.toString();
                if (clsStr.contains("ActionMode") || clsStr.contains("FloatingToolbar")) {
                    return;
                }
            }
        }

        // 0ms fast path: WebView/InsertTool event class -> cover screen immediately on main thread
        if (isDocsSearchEventClass(event)) {
            showZeroFlashCurtain();
            startWebGuardLoop();
            performActiveBack(true);
            showWebBlockedToast();
            hideFromWebIntercept();
            scheduleDeferredScan(0L);
            return;
        }

        long now = SystemClock.uptimeMillis();

        // 1. USER CLICKS (~1-3ms main thread only; never stall main thread on multi-window tree walks)
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            lastDocsClickTime = now;
            if (isStrictFromWebClick(event)) {
                Log.d(TAG, "onFromWebClicked triggered");
                hideFromWebIntercept();
                showZeroFlashCurtain();
                startWebGuardLoop();
                performGlobalAction(GLOBAL_ACTION_BACK);
                showWebBlockedToast();
                scheduleDeferredScan(20L);
                return;
            }
            if (isQuickActiveDrawerVisible()) {
                showZeroFlashCurtain();
                startWebGuardLoop();
                performActiveBack(true);
                showWebBlockedToast();
                hideFromWebIntercept();
                scheduleDeferredScan(0L);
                return;
            }
            hideFromWebIntercept();
            scheduleInterceptProbe();
            scheduleDeferredScan(15L);
            return;
        }

        // 2. FAST DRAWER CHECK FOR WINDOW TRANSITIONS
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (isQuickActiveDrawerVisible()) {
                Log.d(TAG, "closeDocsSearchDrawer fast triggered");
                hideFromWebIntercept();
                showZeroFlashCurtain();
                startWebGuardLoop();
                performActiveBack(true);
                showWebBlockedToast();
                scheduleDeferredScan(25L);
                return;
            }
            if (isDeferredScanScheduled) {
                isDeferredScanScheduled = false;
                getScannerHandler().removeCallbacks(deferredScanRunnable);
            }
            scheduleDeferredScan(0L);
            return;
        }

        // 3. COALESCE CONTENT/SCROLL/FOCUS FLOODS TO BACKGROUND SCANNER THREAD (<1ms on main thread)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            long elapsed = now - lastStateScanTime;
            long delay = (elapsed < SCAN_THROTTLE_MS) ? (SCAN_THROTTLE_MS - elapsed) : 0L;
            scheduleDeferredScan(delay);
        }
    }

    private boolean isCloseOrBackText(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().toLowerCase().trim();
        return s.equals("close") || s.equals("navigate up") || s.equals("back") ||
               s.equals("collapse") || s.equals("cancel") || s.equals("clear query") ||
               s.equals("clear search") || s.equals("up") ||
               s.equals("বন্ধ করুন") || s.equals("উপরে নেভিগেট করুন") ||
               s.equals("ফিরে যান") || s.equals("বাতিল") || s.equals("ব্যাক") ||
               s.equals("উপরে যান") || s.equals("পিছনে");
    }

    private boolean isUncertainPaletteWebClick(AccessibilityEvent event, List<AccessibilityNodeInfo> docsRoots) {
        if (event == null || docsRoots == null || docsRoots.isEmpty()) return false;
        if (!isImagePaletteOpen(docsRoots)) return false;

        String eventTxt = getEventText(event).toLowerCase();
        if (isPhotoOrCameraString(eventTxt) || isCloseOrBackText(eventTxt)) return false;

        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            try {
                if (isPhotoOrCameraNode(source, 0) || isDocumentBody(source)) return false;
                String id = source.getViewIdResourceName();
                if (id != null) {
                    String lowId = id.toLowerCase();
                    if (lowId.contains("palette_back_button") ||
                        lowId.contains("action_bar_back_button") ||
                        lowId.contains("touch_outside")) {
                        return false;
                    }
                }
                CharSequence desc = source.getContentDescription();
                if (desc != null && isCloseOrBackText(desc)) return false;
                CharSequence txt = source.getText();
                if (txt != null && (isCloseOrBackText(txt) || isPhotoOrCameraString(txt.toString()))) return false;
                return true;
            } finally {
                source.recycle();
            }
        }
        return false;
    }

    private boolean isImagePaletteOpen(List<AccessibilityNodeInfo> roots) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0) sw = 720;
        if (sh <= 0) sh = 1600;

        for (AccessibilityNodeInfo root : roots) {
            if (root == null) continue;
            boolean hasBack = false;
            boolean hasImageHeader = false;
            try {
                List<AccessibilityNodeInfo> backBtns = root.findAccessibilityNodeInfosByViewId(DOCS_ID_PREFIX + "palette_back_button");
                if (backBtns != null) {
                    for (AccessibilityNodeInfo b : backBtns) {
                        if (!hasBack && b.isVisibleToUser()) {
                            Rect r = new Rect();
                            b.getBoundsInScreen(r);
                            if (isRectOnScreen(r, sw, sh, 8, 8)) hasBack = true;
                        }
                        b.recycle();
                    }
                }
                if (hasBack) {
                    List<AccessibilityNodeInfo> headers = root.findAccessibilityNodeInfosByViewId(DOCS_ID_PREFIX + "single_tab_header_textview");
                    if (headers != null) {
                        for (AccessibilityNodeInfo h : headers) {
                            if (!hasImageHeader && h.isVisibleToUser()) {
                                CharSequence ht = h.getText();
                                if (ht != null) {
                                    String s = ht.toString().toLowerCase();
                                    if (s.contains("image") || s.contains("ছবি") || s.contains("imagen")) {
                                        hasImageHeader = true;
                                    }
                                }
                            }
                            h.recycle();
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (hasBack && hasImageHeader) return true;

            if (hasVisibleTextInRoot(root, new String[]{"from web", "ওয়েব থেকে", "ওয়েব থেকে", "ওয়েব হতে", "ওয়েব হতে", "search the web"}) &&
                hasVisibleTextInRoot(root, new String[]{"from photos", "from camera", "ফটো থেকে", "ক্যামেরা থেকে"})) {
                return true;
            }
        }
        return false;
    }

    private boolean hasVisibleTextInRoot(AccessibilityNodeInfo root, String[] terms) {
        if (root == null) return false;
        for (String term : terms) {
            try {
                List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(term);
                if (list != null && !list.isEmpty()) {
                    boolean vis = false;
                    for (AccessibilityNodeInfo n : list) {
                        if (!vis && n.isVisibleToUser()) vis = true;
                        n.recycle();
                    }
                    if (vis) return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean isStrictFromWebClick(AccessibilityEvent event) {
        String eventTxt = getEventText(event).toLowerCase();
        if (isPhotoOrCameraString(eventTxt)) return false;

        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            try {
                if (isDocumentBody(source) || isPhotoOrCameraNode(source, 0)) return false;
                String id = source.getViewIdResourceName();
                if (id != null) {
                    String lowId = id.toLowerCase();
                    if ((lowId.contains("from_web") || lowId.contains("web_search") || lowId.contains("insert_tool")) &&
                        !lowId.contains("back_button")) {
                        return true;
                    }
                }
                if (hasDirectStrictWebChild(source, 0)) return true;
                CharSequence sTxt = source.getText();
                if (sTxt != null && isStrictFromWebText(sTxt)) return true;
                CharSequence sDesc = source.getContentDescription();
                if (sDesc != null && isStrictFromWebText(sDesc)) return true;

                // Walk up to 4 parent levels (stopping at scrollable containers)
                AccessibilityNodeInfo current = source.getParent();
                int depth = 0;
                while (current != null && depth < 4) {
                    CharSequence cls = current.getClassName();
                    if (cls != null) {
                        String clsStr = cls.toString().toLowerCase();
                        if (clsStr.contains("recyclerview") ||
                            clsStr.contains("listview") ||
                            clsStr.contains("gridview") ||
                            clsStr.contains("scrollview") ||
                            clsStr.contains("viewpager")) {
                            current.recycle();
                            break;
                        }
                    }
                    if (isPhotoOrCameraNode(current, 0)) {
                        current.recycle();
                        return false;
                    }
                    if (hasDirectStrictWebChild(current, 0)) {
                        current.recycle();
                        return true;
                    }
                    AccessibilityNodeInfo next = current.getParent();
                    current.recycle();
                    current = next;
                    depth++;
                }
            } finally {
                source.recycle();
            }
        }

        if (isStrictFromWebText(eventTxt)) return true;
        CharSequence desc = event.getContentDescription();
        if (desc != null && isStrictFromWebText(desc)) return true;

        return false;
    }

    private boolean isStrictFromWebText(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().toLowerCase().trim();
        return s.equals("from web") || s.contains("from web") ||
               s.equals("web") || s.equals("ওয়েব") || s.equals("ওয়েব") ||
               s.equals("explore") || s.contains("explore") || s.equals("অন্বেষণ") ||
               s.equals("web search") || s.contains("web search") ||
               s.equals("search the web") || s.contains("search the web") ||
               s.equals("search web") || s.contains("search web") ||
               s.equals("search images") || s.contains("search images") ||
               s.equals("image search") || s.contains("image search") ||
               s.equals("search with google") || s.contains("search google") ||
               s.equals("smart search") || s.equals("look up") ||
               s.equals("search google images") || s.contains("search google images") ||
               s.equals("google images") || s.contains("google images") ||
               s.equals("ওয়েব থেকে") || s.contains("ওয়েব থেকে") ||
               s.equals("ওয়েব থেকে") || s.contains("ওয়েব থেকে") ||
               s.equals("ওয়েব হতে") || s.contains("ওয়েব হতে") ||
               s.equals("ওয়েব হতে") || s.contains("ওয়েব হতে") ||
               s.equals("ওয়েবে খুঁজুন") || s.contains("ওয়েবে খুঁজুন") ||
               s.equals("ওয়েবে খুঁজুন") || s.contains("ওয়েবে খুঁজুন") ||
               s.equals("ওয়েব অনুসন্ধান") || s.contains("ওয়েব অনুসন্ধান") ||
               s.equals("ওয়েব অনুসন্ধান") || s.contains("ওয়েব অনুসন্ধান") ||
               s.equals("ওয়েব সার্চ") || s.contains("ওয়েব সার্চ") ||
               s.equals("ওয়েব সার্চ") || s.contains("ওয়েব সার্চ") ||
               s.equals("ছবি খুঁজুন") || s.contains("ছবি খুঁজুন") ||
               s.equals("ছবি অনুসন্ধান") || s.contains("ছবি অনুসন্ধান") ||
               s.equals("ছবি সার্চ") || s.contains("ছবি সার্চ") ||
               s.equals("গুগল অনুসন্ধান") || s.contains("গুগল অনুসন্ধান") ||
               s.equals("গুগল ইমেজ") || s.contains("গুগল ইমেজ") ||
               s.equals("গুগল সার্চ") || s.contains("গুগল সার্চ") ||
               s.equals("গুগল ছবি") || s.contains("গুগল ছবি") ||
               s.equals("ইন্টারনেট থেকে") || s.contains("ইন্টারনেট থেকে") ||
               s.equals("वेब से") || s.contains("वेब से") ||
               s.equals("वेब खोज") || s.contains("वेब खोज") ||
               s.equals("desde la web") || s.contains("desde la web") ||
               s.equals("de la web") || s.contains("buscar en la web");
    }

    private boolean isPhotoOrCameraString(String s) {
        if (s == null || s.isEmpty()) return false;
        String l = s.toLowerCase();
        return l.contains("photo") || l.contains("camera") || 
               l.contains("ফটো") || l.contains("ক্যামেরা") ||
               l.contains("ছবি তুলুন") || l.contains("গ্যালারি");
    }

    private boolean hasDirectStrictWebChild(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return false;
        CharSequence txt = node.getText();
        if (txt != null && isStrictFromWebText(txt)) return true;
        CharSequence desc = node.getContentDescription();
        if (desc != null && isStrictFromWebText(desc)) return true;

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean match = hasDirectStrictWebChild(child, depth + 1);
                child.recycle();
                if (match) return true;
            }
        }
        return false;
    }

    private boolean isPhotoOrCameraNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return false;
        CharSequence txt = node.getText();
        if (txt != null && isPhotoOrCameraString(txt.toString())) return true;
        CharSequence desc = node.getContentDescription();
        if (desc != null && isPhotoOrCameraString(desc.toString())) return true;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean match = isPhotoOrCameraNode(child, depth + 1);
                child.recycle();
                if (match) return true;
            }
        }
        return false;
    }

    private boolean isDocumentBody(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String id = node.getViewIdResourceName();
        if (id != null) {
            String idLow = id.toLowerCase();
            if (idLow.contains("viewport_view") || 
                idLow.contains("main_canvas_view") || 
                idLow.contains("content_view") ||
                idLow.contains("document_canvas") ||
                idLow.contains("simple_fast_scroll_view")) {
                return true;
            }
        }
        return false;
    }

    private boolean isSafeDocsOrDriveLabel(CharSequence cs) {
        if (cs == null || cs.length() > 45) return false;
        String s = cs.toString().toLowerCase().trim();
        return s.equals("add shortcut to drive") || s.equals("add shortcut") || s.equals("shortcut to drive") ||
               s.contains("ড্রাইভে শর্টকাট") || s.contains("শর্টকাট যোগ করুন") ||
               s.equals("my drive") || s.equals("আমার ড্রাইভ") ||
               s.equals("shared with me") || s.contains("আমার সাথে শেয়ার") ||
               s.equals("computers") || s.equals("কম্পিউটার") ||
               s.equals("starred") || s.equals("তারকাচিহ্নিত") ||
               s.equals("move here") || s.equals("এখানে সরান") ||
               s.equals("select folder") || s.equals("choose folder") || s.contains("ফোল্ডার নির্বাচন") ||
               s.equals("save to drive") || s.contains("ড্রাইভে সংরক্ষণ") ||
               s.equals("open from drive") || s.contains("ড্রাইভ থেকে খুলুন") ||
               s.equals("find and replace") || s.equals("find & replace") || s.contains("খুঁজুন ও প্রতিস্থাপন") ||
               s.equals("replace all") || s.equals("সব প্রতিস্থাপন করুন") ||
               s.equals("document outline") || s.equals("ডকুমেন্টের রূপরেখা") ||
               s.equals("word count") || s.equals("শব্দ গণনা") ||
               s.equals("page setup") || s.equals("পৃষ্ঠা সেটআপ");
    }

    private boolean isDocsSearchScreenActive(List<AccessibilityNodeInfo> roots, AccessibilityEvent event, boolean allowDeepWalk) {
        if (isDocsSearchEventClass(event)) return true;
        if (roots == null || roots.isEmpty()) return false;
        for (AccessibilityNodeInfo root : roots) {
            if (hasVisibleSearchDrawerResource(root)) return true;
        }
        if (allowDeepWalk) {
            for (AccessibilityNodeInfo root : roots) {
                if (hasWebViewOrSearchUi(root)) return true;
            }
        }
        return false;
    }

    private boolean hasVisibleSearchDrawerResource(AccessibilityNodeInfo root) {
        if (root == null) return false;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0) sw = 720;
        if (sh <= 0) sh = 1600;
        String[] searchIds = {
            DOCS_ID_PREFIX + "insert_tool_drawer_container",
            DOCS_ID_PREFIX + "insert_tool_drawer",
            DOCS_ID_PREFIX + "insert_tool_back_button",
            DOCS_ID_PREFIX + "explore_drawer",
            DOCS_ID_PREFIX + "explore_web_view"
        };
        for (String id : searchIds) {
            try {
                List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
                if (list != null && !list.isEmpty()) {
                    boolean visible = false;
                    for (AccessibilityNodeInfo n : list) {
                        if (n.isVisibleToUser()) {
                            Rect b = new Rect();
                            n.getBoundsInScreen(b);
                            if (isRectOnScreen(b, sw, sh, 8, 8)) {
                                visible = true;
                            }
                        }
                        n.recycle();
                    }
                    if (visible) return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean hasWebViewOrSearchUi(AccessibilityNodeInfo root) {
        if (root == null) return false;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0) sw = 720;
        if (sh <= 0) sh = 1600;
        int[] visited = {0};
        // flags: [0]=explicitMatch, [1]=safeScreen, [2]=hasFormattingBar, [3]=hasFindInDoc,
        //        [4]=hasFloatingHeaderBtn, [5]=hasHeaderSearchBtn, [6]=hasHeaderBackBtn,
        //        [7]=hasProgressBar, [8]=hasNonFindEditText
        boolean[] flags = new boolean[9];
        scanTreeForWebViewOrSearchUi(root, 0, visited, sw, sh, flags);
        if (flags[0]) return true;
        if (flags[1] || flags[3]) return false;
        if (flags[4] && flags[5] && (flags[6] || flags[7])) return true;
        if (flags[8] && flags[5] && flags[6] && !flags[2]) return true;
        return false;
    }

    private void scanTreeForWebViewOrSearchUi(AccessibilityNodeInfo node, int depth, int[] visited,
                                              int sw, int sh, boolean[] flags) {
        if (node == null || depth > 15 || visited[0] >= 400 || flags[0] || isDocumentBody(node)) return;
        visited[0]++;

        if (node.isVisibleToUser()) {
            CharSequence txt = node.getText();
            CharSequence desc = node.getContentDescription();
            String id = node.getViewIdResourceName();
            String lowId = (id != null) ? id.toLowerCase() : "";

            if (!node.isEditable() && (isSafeDocsOrDriveLabel(txt) || isSafeDocsOrDriveLabel(desc))) {
                flags[1] = true;
            }
            if (lowId.contains("find_next") || lowId.contains("find_prev") ||
                lowId.contains("find_query") || lowId.contains("replace")) {
                flags[3] = true;
            }
            if (lowId.contains("insert_tool") || lowId.contains("search_results") ||
                lowId.contains("web_search") || lowId.contains("image_search") ||
                lowId.contains("explore_")) {
                if (!lowId.contains("palette_back_button") && !lowId.contains("open_search_bar")) {
                    Rect b = new Rect();
                    node.getBoundsInScreen(b);
                    if (isRectOnScreen(b, sw, sh, 8, 8)) {
                        flags[0] = true;
                        return;
                    }
                }
            }

            CharSequence cls = node.getClassName();
            String clsStr = (cls != null) ? cls.toString() : "";
            if (clsStr.contains("WebView") || clsStr.contains("webview")) {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (isRectOnScreen(b, sw, sh, (int) (sw * 0.35f), (int) (sh * 0.15f))) {
                    flags[0] = true;
                    return;
                }
            }

            if (isExplicitWebSearchUiText(txt) || isExplicitWebSearchUiText(desc)) {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (isRectOnScreen(b, sw, sh, 4, 4)) {
                    flags[0] = true;
                    return;
                }
            }

            if (clsStr.contains("ProgressBar") || clsStr.contains("ProgressIndicator")) {
                flags[7] = true;
            }
            if (clsStr.contains("EditText") || clsStr.contains("AutoCompleteTextView")) {
                if (!lowId.contains("find") && !lowId.contains("replace")) {
                    Rect b = new Rect();
                    node.getBoundsInScreen(b);
                    if (isRectOnScreen(b, sw, sh, 40, 12)) {
                        flags[8] = true;
                    }
                }
            }

            String dLow = (desc != null) ? desc.toString().toLowerCase().trim() : "";
            String tLow = (txt != null) ? txt.toString().toLowerCase().trim() : "";
            if (dLow.equals("bold") || dLow.equals("italic") || dLow.equals("underline") ||
                dLow.equals("বোল্ড") || dLow.equals("ইটালিক") || dLow.equals("আন্ডারলাইন")) {
                flags[2] = true;
            }
            boolean isSearchBtn = !lowId.contains("open_search_bar") &&
                                  (dLow.contains("search") || dLow.contains("অনুসন্ধান") || dLow.contains("সার্চ") ||
                                   tLow.equals("search") || tLow.equals("অনুসন্ধান") || tLow.equals("সার্চ") ||
                                   lowId.contains("search_button"));
            boolean isBackBtn = !lowId.contains("palette_back_button") &&
                                (isCloseOrBackText(dLow) || isCloseOrBackText(tLow));
            if (isSearchBtn || isBackBtn) {
                if (isSearchBtn) flags[5] = true;
                if (isBackBtn) flags[6] = true;
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (isRectOnScreen(b, sw, sh, 8, 8) && b.top >= (int) (sh * 0.12f) && b.bottom <= (int) (sh * 0.78f)) {
                    flags[4] = true;
                }
            }
        }

        // Traverse children in reverse so foreground BottomSheets/Drawers are visited before background editor
        int count = node.getChildCount();
        for (int i = count - 1; i >= 0 && visited[0] < 400 && !flags[0]; i--) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                scanTreeForWebViewOrSearchUi(child, depth + 1, visited, sw, sh, flags);
                child.recycle();
            }
        }
    }

    private boolean isExplicitWebSearchUiText(CharSequence cs) {
        if (cs == null || cs.length() > 80) return false;
        String s = cs.toString().toLowerCase().trim();
        return s.contains("search your docs and the web") ||
               s.contains("আপনার ডকুমেন্টস এবং ওয়েব") ||
               s.contains("find images, facts and text") ||
               s.contains("search web images") ||
               s.contains("search google images") ||
               s.contains("google image search") ||
               s.contains("search images") ||
               s.contains("ছবি খুঁজুন") ||
               s.contains("ছবি অনুসন্ধান") ||
               s.contains("ছবি সার্চ") ||
               s.contains("search the web") ||
               s.contains("search web") ||
               s.contains("ওয়েব অনুসন্ধান") ||
               s.contains("ওয়েব অনুসন্ধান") ||
               s.contains("ওয়েবে খুঁজুন") ||
               s.contains("ওয়েবে খুঁজুন") ||
               s.contains("ওয়েব সার্চ") ||
               s.contains("ওয়েব সার্চ") ||
               s.contains("গুগল অনুসন্ধান") ||
               s.contains("গুগল সার্চ") ||
               s.contains("গুগল ইমেজ");
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

    // =========================================================================
    // GOOGLE DOCS LOADING PROGRESS BAR SHIELD
    // =========================================================================

    private volatile View loadingBarShieldView = null;
    private WindowManager.LayoutParams loadingShieldParams = null;
    private volatile boolean isLoadingShieldAttached = false;
    private Runnable loadingMonitorRunnable = null;
    private volatile boolean isLoadingMonitorActive = false;
    private int loadingMonitorAbsentCount = 0;

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * (density > 0 ? density : 2.0f));
    }

    private boolean isDarkThemeActive() {
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private void updateLoadingBarShield(List<AccessibilityNodeInfo> docsRoots) {
        Rect loadingBounds = findVisibleLoadingBarBounds(docsRoots);
        if (loadingBounds != null) {
            positionLoadingBarShield(loadingBounds);
            startLoadingBarMonitor();
        } else if (isLoadingShieldAttached) {
            destroyLoadingBarShield();
        }
    }

    private Rect findVisibleLoadingBarBounds(List<AccessibilityNodeInfo> docsRoots) {
        if (docsRoots == null || docsRoots.isEmpty()) return null;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0) sw = 720;
        if (sh <= 0) sh = 1600;
        int maxBarHeight = dpToPx(18);
        int maxTopY = sh / 3;

        for (AccessibilityNodeInfo root : docsRoots) {
            if (root == null) continue;
            int[] visited = {0};
            Rect found = scanForLoadingBarNode(root, 0, visited, sw, maxTopY, maxBarHeight);
            if (found != null) {
                found.top = Math.max(0, found.top - 2);
                found.bottom = Math.min(sh, found.bottom + 2);
                found.left = 0;
                found.right = sw;
                return found;
            }
        }
        return null;
    }

    private boolean isLoadingProgressBarNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence cls = node.getClassName();
        if (cls != null) {
            String c = cls.toString();
            if (c.contains("ProgressBar") || c.contains("ProgressIndicator")) {
                return true;
            }
        }
        String id = node.getViewIdResourceName();
        if (id != null) {
            String lowId = id.toLowerCase();
            if ((lowId.contains("progress") || lowId.contains("loading")) &&
                !lowId.contains("circular") &&
                !lowId.contains("spinner") &&
                !lowId.contains("dialog")) {
                return true;
            }
        }
        return false;
    }

    private Rect scanForLoadingBarNode(AccessibilityNodeInfo node, int depth, int[] visited,
                                       int sw, int maxTopY, int maxBarHeight) {
        if (node == null || depth > 16 || visited[0] >= 250 || isDocumentBody(node)) {
            return null;
        }
        visited[0]++;

        if (node.isVisibleToUser() && isLoadingProgressBarNode(node)) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (!r.isEmpty() &&
                r.width() >= (sw * 0.45f) &&
                r.height() >= 1 &&
                r.height() <= maxBarHeight &&
                r.top >= 0 &&
                r.bottom <= maxTopY) {
                return r;
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count && visited[0] < 250; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                Rect match = scanForLoadingBarNode(child, depth + 1, visited, sw, maxTopY, maxBarHeight);
                child.recycle();
                if (match != null) return match;
            }
        }
        return null;
    }

    private void startLoadingBarMonitor() {
        if (isLoadingMonitorActive) return;
        isLoadingMonitorActive = true;
        loadingMonitorAbsentCount = 0;
        loadingMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isLoadingMonitorActive) return;

                List<AccessibilityNodeInfo> docsRoots = collectGoogleDocsRoots(null);
                try {
                    Rect bounds = findVisibleLoadingBarBounds(docsRoots);
                    if (bounds != null) {
                        loadingMonitorAbsentCount = 0;
                        positionLoadingBarShield(bounds);
                    } else {
                        loadingMonitorAbsentCount++;
                        if (loadingMonitorAbsentCount >= 2) {
                            destroyLoadingBarShield();
                            return;
                        }
                    }
                } finally {
                    recycleNodeList(docsRoots);
                }

                if (isLoadingMonitorActive) {
                    getScannerHandler().postDelayed(this, 120L);
                }
            }
        };
        getScannerHandler().postDelayed(loadingMonitorRunnable, 120L);
    }

    private void stopLoadingBarMonitor() {
        isLoadingMonitorActive = false;
        loadingMonitorAbsentCount = 0;
        if (loadingMonitorRunnable != null) {
            Handler sh = scannerHandler;
            if (sh != null) sh.removeCallbacks(loadingMonitorRunnable);
            loadingMonitorRunnable = null;
        }
    }

    private void positionLoadingBarShield(final Rect rect) {
        if (rect == null || rect.isEmpty() || rect.width() <= 0 || rect.height() <= 0) {
            return;
        }

        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                    if (wm == null) return;

                    if (loadingBarShieldView != null && !loadingBarShieldView.isAttachedToWindow()) {
                        // Still attaching on first vsync frame; reuse instead of leaking
                        return;
                    }

                    int x = Math.max(0, rect.left);
                    int y = Math.max(0, rect.top);
                    int width = rect.width();
                    int height = rect.height();
                    int bgColor = isDarkThemeActive() ? Color.BLACK : Color.WHITE;

                    if (loadingBarShieldView == null) {
                        View shield = new View(FocusGuardService.this);
                        shield.setBackgroundColor(bgColor);
                        shield.setClickable(false);
                        shield.setFocusable(false);

                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            width,
                            height,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT
                        );
                        params.gravity = Gravity.TOP | Gravity.START;
                        params.x = x;
                        params.y = y;

                        wm.addView(shield, params);
                        loadingBarShieldView = shield;
                        loadingShieldParams = params;
                        isLoadingShieldAttached = true;
                    } else if (loadingShieldParams != null && isLoadingShieldAttached) {
                        loadingBarShieldView.setBackgroundColor(bgColor);
                        boolean changed = (loadingShieldParams.x != x ||
                                           loadingShieldParams.y != y ||
                                           loadingShieldParams.height != height ||
                                           loadingShieldParams.width != width);
                        if (changed) {
                            loadingShieldParams.x = x;
                            loadingShieldParams.y = y;
                            loadingShieldParams.width = width;
                            loadingShieldParams.height = height;
                            wm.updateViewLayout(loadingBarShieldView, loadingShieldParams);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "positionLoadingBarShield error", e);
                    destroyLoadingBarShield();
                }
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.postAtFrontOfQueue(r);
        }
    }

    private void removeLoadingShieldFromWindowManager(WindowManager wm) {
        if (loadingBarShieldView != null) {
            try {
                if (loadingBarShieldView.isAttachedToWindow()) {
                    wm.removeViewImmediate(loadingBarShieldView);
                } else {
                    wm.removeView(loadingBarShieldView);
                }
            } catch (IllegalArgumentException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Failed removing loading bar shield view", e);
            }
        }
        loadingBarShieldView = null;
        loadingShieldParams = null;
        isLoadingShieldAttached = false;
    }

    private void destroyLoadingBarShield() {
        stopLoadingBarMonitor();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (loadingBarShieldView == null && !isLoadingShieldAttached) {
                    return;
                }
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) {
                    removeLoadingShieldFromWindowManager(wm);
                } else {
                    loadingBarShieldView = null;
                    loadingShieldParams = null;
                    isLoadingShieldAttached = false;
                }
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.postAtFrontOfQueue(r);
        }
    }

    // =========================================================================
    // ZERO-FLASH BLACK SCREEN (Instant On-Click, 10-Second Hold To Dismiss)
    // =========================================================================

    private final List<View> activeCurtainViews = new ArrayList<>();
    private volatile View flashCurtainView = null;
    private volatile boolean isFlashCurtainAttached = false;

    private final Runnable holdDismissRunnable = new Runnable() {
        @Override
        public void run() {
            flashCurtainCooldownUntil = SystemClock.uptimeMillis() + 400L;
            lastDocsClickTime = 0L;
            stopWebGuardLoop();
            dismissZeroFlashCurtain();
            scheduleInterceptProbe();
            Toast.makeText(FocusGuardService.this, "পর্দা সরানো হয়েছে", Toast.LENGTH_SHORT).show();
        }
    };

    private void showHoldHintToast() {
        long now = System.currentTimeMillis();
        if (now - lastHoldHintToastTime > 3000L) {
            lastHoldHintToastTime = now;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(FocusGuardService.this, "পর্দা সরাতে ১০ সেকেন্ড চেপে ধরে রাখুন", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void removeAllCurtainsFromWindowManager(WindowManager wm) {
        List<View> toRemove = new ArrayList<>(activeCurtainViews);
        if (flashCurtainView != null && !toRemove.contains(flashCurtainView)) {
            toRemove.add(flashCurtainView);
        }
        for (View v : toRemove) {
            if (v == null) continue;
            try {
                if (v.isAttachedToWindow()) {
                    wm.removeViewImmediate(v);
                } else {
                    wm.removeView(v);
                }
            } catch (IllegalArgumentException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Failed removing flash curtain view", e);
            }
        }
        activeCurtainViews.clear();
        flashCurtainView = null;
        isFlashCurtainAttached = false;
    }

    private void showZeroFlashCurtain() {
        long now = SystemClock.uptimeMillis();
        if (isFlashCurtainAttached || flashCurtainView != null) {
            return;
        }
        if (now < flashCurtainCooldownUntil) {
            Log.d(TAG, "showZeroFlashCurtain skipped due to cooldown");
            return;
        }
        destroyLoadingBarShield();

        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    if (flashCurtainView != null || isFlashCurtainAttached) {
                        return;
                    }
                    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                    if (wm == null) return;

                    if (!activeCurtainViews.isEmpty()) {
                        removeAllCurtainsFromWindowManager(wm);
                    }

                    View curtain = new View(FocusGuardService.this);
                    curtain.setBackgroundColor(Color.BLACK);
                    curtain.setClickable(true);
                    curtain.setFocusable(false);

                    curtain.setOnTouchListener(new View.OnTouchListener() {
                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            int action = event.getActionMasked();
                            if (action == MotionEvent.ACTION_DOWN) {
                                holdDownTime = SystemClock.uptimeMillis();
                                mainHandler.removeCallbacks(holdDismissRunnable);
                                mainHandler.postDelayed(holdDismissRunnable, HOLD_DISMISS_MS);
                            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                                mainHandler.removeCallbacks(holdDismissRunnable);
                                if (isFlashCurtainAttached &&
                                    (SystemClock.uptimeMillis() - holdDownTime) < HOLD_DISMISS_MS) {
                                    showHoldHintToast();
                                }
                            }
                            return true;
                        }
                    });

                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    );
                    wm.addView(curtain, params);
                    flashCurtainView = curtain;
                    activeCurtainViews.add(curtain);
                    isFlashCurtainAttached = true;
                    Log.d(TAG, "showZeroFlashCurtain added");
                } catch (Exception e) {
                    Log.w(TAG, "showZeroFlashCurtain error", e);
                }
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.postAtFrontOfQueue(r);
        }
    }

    private void dismissZeroFlashCurtain() {
        mainHandler.removeCallbacks(holdDismissRunnable);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (flashCurtainView == null && activeCurtainViews.isEmpty() && !isFlashCurtainAttached) return;
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) {
                    removeAllCurtainsFromWindowManager(wm);
                } else {
                    activeCurtainViews.clear();
                    flashCurtainView = null;
                    isFlashCurtainAttached = false;
                }
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.postAtFrontOfQueue(r);
        }
    }

    // =========================================================================
    // STRICT "FROM WEB" BUTTON INTERCEPT (Zero-Frame Prevention, Auto-Outside-Dismiss)
    // =========================================================================

    private final List<View> activeInterceptViews = new ArrayList<>();
    private volatile View fromWebInterceptView = null;
    private WindowManager.LayoutParams fromWebInterceptParams = null;
    private volatile boolean isFromWebInterceptAttached = false;
    private Runnable interceptMonitorRunnable = null;
    private volatile boolean isInterceptMonitorActive = false;
    private volatile long interceptMonitorStartTime = 0L;
    private volatile int interceptMissCount = 0;
    private final List<Runnable> pendingInterceptProbes = new ArrayList<>();
    // Cached last-known "From web" row bounds for instant re-positioning
    private volatile Rect cachedFromWebBounds = null;

    private void scheduleInterceptProbe() {
        cancelInterceptProbes();
        Handler sh = getScannerHandler();
        long[] delays = {5L, 15L, 35L, 65L, 110L, 180L, 280L, 420L};
        for (long d : delays) {
            Runnable probe = new Runnable() {
                @Override
                public void run() {
                    synchronized (pendingInterceptProbes) {
                        pendingInterceptProbes.remove(this);
                    }
                    if (isFlashCurtainAttached || prefManager == null || !prefManager.isGoogleDocsBlocked()) {
                        return;
                    }
                    List<AccessibilityNodeInfo> roots = collectGoogleDocsRoots(null);
                    try {
                        updateFromWebIntercept(roots);
                    } finally {
                        recycleNodeList(roots);
                    }
                }
            };
            synchronized (pendingInterceptProbes) {
                pendingInterceptProbes.add(probe);
            }
            sh.postDelayed(probe, d);
        }
    }

    private void cancelInterceptProbes() {
        Handler sh = scannerHandler;
        synchronized (pendingInterceptProbes) {
            if (sh != null) {
                for (Runnable r : pendingInterceptProbes) {
                    sh.removeCallbacks(r);
                }
            }
            pendingInterceptProbes.clear();
        }
    }

    private void updateFromWebIntercept(List<AccessibilityNodeInfo> docsRoots) {
        if (isFlashCurtainAttached) {
            hideFromWebIntercept();
            return;
        }
        Rect rowBounds = findStrictFromWebRowBounds(docsRoots);
        if (rowBounds != null) {
            interceptMissCount = 0;
            cachedFromWebBounds = new Rect(rowBounds);
            positionFromWebIntercept(rowBounds);
            startInterceptMonitor();
        } else if (isFromWebInterceptAttached || fromWebInterceptView != null) {
            interceptMissCount++;
            if (interceptMissCount >= 3) {
                hideFromWebIntercept();
            }
        }
    }

    private Rect findStrictFromWebRowBounds(List<AccessibilityNodeInfo> roots) {
        if (roots == null || roots.isEmpty()) return null;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0) sw = 720;
        if (sh <= 0) sh = 1600;

        for (AccessibilityNodeInfo root : roots) {
            if (root == null) continue;
            boolean hasVisibleBack = false;
            boolean hasCompanion = false;
            AccessibilityNodeInfo webNode = null;

            try {
                List<AccessibilityNodeInfo> backBtns = root.findAccessibilityNodeInfosByViewId(DOCS_ID_PREFIX + "palette_back_button");
                if (backBtns != null) {
                    for (AccessibilityNodeInfo b : backBtns) {
                        if (!hasVisibleBack && b.isVisibleToUser()) {
                            Rect br = new Rect();
                            b.getBoundsInScreen(br);
                            if (isRectOnScreen(br, sw, sh, 8, 8)) hasVisibleBack = true;
                        }
                        b.recycle();
                    }
                }

                List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(DOCS_ID_PREFIX + "title");
                int visibleTitles = 0;
                if (titles != null) {
                    for (AccessibilityNodeInfo n : titles) {
                        if (n.isVisibleToUser()) {
                            Rect nr = new Rect();
                            n.getBoundsInScreen(nr);
                            if (isRectOnScreen(nr, sw, sh, 12, 8)) {
                                visibleTitles++;
                                CharSequence cs = n.getText();
                                if (cs != null && cs.length() <= 30) {
                                    String s = cs.toString().toLowerCase().trim();
                                    if (isStrictPalettePhotoOrCamera(s)) {
                                        hasCompanion = true;
                                    } else if (webNode == null && isStrictPaletteWebLabel(s)) {
                                        webNode = AccessibilityNodeInfo.obtain(n);
                                    }
                                }
                            }
                        }
                        n.recycle();
                    }
                }

                // Small bounded walk ONLY when palette_back_button is visible or it's a small popup/dialog window
                if (visibleTitles == 0 && (hasVisibleBack || root.getChildCount() <= 8)) {
                    int[] visited = {0};
                    AccessibilityNodeInfo[] foundWeb = new AccessibilityNodeInfo[1];
                    boolean[] compFlag = new boolean[1];
                    scanPaletteForFromWeb(root, 0, visited, sw, sh, foundWeb, compFlag);
                    if (compFlag[0]) hasCompanion = true;
                    if (webNode == null && foundWeb[0] != null) webNode = foundWeb[0];
                    else if (foundWeb[0] != null) foundWeb[0].recycle();
                }

                if (webNode != null && (hasCompanion || hasVisibleBack)) {
                    Rect rowRect = getClickableRowBounds(webNode);
                    int h = rowRect.height();
                    if (!rowRect.isEmpty() && isRectOnScreen(rowRect, sw, sh, 80, 30) &&
                        h >= 30 && h <= 240) {
                        if (rowRect.width() >= (int) (sw * 0.25f)) {
                            rowRect.left = 0;
                            rowRect.right = sw;
                        } else {
                            rowRect.left = Math.max(0, rowRect.left);
                            rowRect.right = Math.min(sw, rowRect.right);
                        }
                        rowRect.top = Math.max(0, rowRect.top);
                        rowRect.bottom = Math.min(sh, rowRect.bottom);
                        return rowRect;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (webNode != null) webNode.recycle();
            }
        }
        return null;
    }

    private boolean isStrictPalettePhotoOrCamera(String s) {
        return s.equals("from photos") || s.equals("photos") ||
               s.equals("from photo") || s.equals("photo") ||
               s.equals("from camera") || s.equals("camera") ||
               s.equals("ফটো থেকে") || s.equals("ফটো হতে") || s.equals("ফটো") ||
               s.equals("ক্যামেরা থেকে") || s.equals("ক্যামেরা হতে") || s.equals("ক্যামেরা");
    }

    private boolean isStrictPaletteWebLabel(String s) {
        return s.equals("from web") || s.contains("from web") ||
               s.equals("ওয়েব থেকে") || s.contains("ওয়েব থেকে") ||
               s.equals("ওয়েব থেকে") || s.contains("ওয়েব থেকে") ||
               s.equals("ওয়েব হতে") || s.contains("ওয়েব হতে") ||
               s.equals("ওয়েব হতে") || s.contains("ওয়েব হতে");
    }

    private void scanPaletteForFromWeb(AccessibilityNodeInfo node, int depth, int[] visited,
                                       int sw, int sh, AccessibilityNodeInfo[] foundWeb, boolean[] compFlag) {
        if (node == null || depth > 8 || visited[0] >= 80 || isDocumentBody(node)) return;
        visited[0]++;
        if (node.isVisibleToUser()) {
            CharSequence cs = node.getText();
            if (cs != null && cs.length() <= 30) {
                String s = cs.toString().toLowerCase().trim();
                if (isStrictPalettePhotoOrCamera(s)) {
                    compFlag[0] = true;
                } else if (foundWeb[0] == null && isStrictPaletteWebLabel(s)) {
                    Rect nr = new Rect();
                    node.getBoundsInScreen(nr);
                    if (isRectOnScreen(nr, sw, sh, 12, 8)) {
                        foundWeb[0] = AccessibilityNodeInfo.obtain(node);
                    }
                }
            }
        }
        int count = node.getChildCount();
        for (int i = count - 1; i >= 0 && visited[0] < 80; i--) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                scanPaletteForFromWeb(child, depth + 1, visited, sw, sh, foundWeb, compFlag);
                child.recycle();
            }
        }
    }

    private Rect getClickableRowBounds(AccessibilityNodeInfo node) {
        Rect out = new Rect();
        if (node == null) return out;
        AccessibilityNodeInfo current = node.getParent();
        int depth = 0;
        while (current != null && depth < 4) {
            CharSequence cls = current.getClassName();
            if (cls != null) {
                String c = cls.toString().toLowerCase();
                if (c.contains("recyclerview") || c.contains("listview") ||
                    c.contains("scrollview") || c.contains("viewpager")) {
                    current.recycle();
                    break;
                }
            }
            if (current.isClickable()) {
                current.getBoundsInScreen(out);
                current.recycle();
                if (!out.isEmpty()) return out;
                break;
            }
            AccessibilityNodeInfo next = current.getParent();
            current.recycle();
            current = next;
            depth++;
        }
        node.getBoundsInScreen(out);
        return out;
    }

    private void startInterceptMonitor() {
        if (isInterceptMonitorActive) return;
        isInterceptMonitorActive = true;
        interceptMonitorStartTime = SystemClock.uptimeMillis();
        interceptMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isInterceptMonitorActive) return;
                if (isFlashCurtainAttached || prefManager == null || !prefManager.isGoogleDocsBlocked()) {
                    hideFromWebIntercept();
                    return;
                }
                List<AccessibilityNodeInfo> roots = collectGoogleDocsRoots(null);
                try {
                    Rect bounds = findStrictFromWebRowBounds(roots);
                    if (bounds != null) {
                        interceptMissCount = 0;
                        cachedFromWebBounds = new Rect(bounds);
                        positionFromWebIntercept(bounds);
                    } else {
                        interceptMissCount++;
                        if (interceptMissCount >= 3) {
                            hideFromWebIntercept();
                            return;
                        }
                    }
                } finally {
                    recycleNodeList(roots);
                }
                if (isInterceptMonitorActive) {
                    long elapsed = SystemClock.uptimeMillis() - interceptMonitorStartTime;
                    getScannerHandler().postDelayed(this, elapsed < 500L ? 45L : 120L);
                }
            }
        };
        getScannerHandler().postDelayed(interceptMonitorRunnable, 45L);
    }

    private void stopInterceptMonitor() {
        isInterceptMonitorActive = false;
        interceptMissCount = 0;
        if (interceptMonitorRunnable != null) {
            Handler sh = scannerHandler;
            if (sh != null) sh.removeCallbacks(interceptMonitorRunnable);
            interceptMonitorRunnable = null;
        }
    }

    private void positionFromWebIntercept(final Rect rect) {
        if (rect == null || rect.isEmpty() || rect.width() <= 0 || rect.height() <= 0) return;

        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    if (isFlashCurtainAttached) {
                        destroyFromWebIntercept();
                        return;
                    }
                    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                    if (wm == null) return;

                    int x = Math.max(0, rect.left);
                    int y = Math.max(0, rect.top);
                    int width = rect.width();
                    int height = rect.height();

                    if (fromWebInterceptView == null) {
                        if (!activeInterceptViews.isEmpty()) {
                            removeInterceptViewsFromWindowManager(wm);
                        }
                        View shield = new View(FocusGuardService.this);
                        shield.setBackgroundColor(Color.argb(140, 0, 0, 0));
                        shield.setClickable(true);
                        shield.setFocusable(false);

                        shield.setOnTouchListener(new View.OnTouchListener() {
                            @Override
                            public boolean onTouch(View v, MotionEvent event) {
                                int action = event.getActionMasked();
                                if (action == MotionEvent.ACTION_OUTSIDE) {
                                    Log.d(TAG, "fromWebInterceptView ACTION_OUTSIDE");
                                    // User tapped outside "From web" (Photos, Camera, Back, etc.) -> hide off-screen
                                    hideFromWebIntercept();
                                    return false;
                                }
                                if (action == MotionEvent.ACTION_DOWN) {
                                    Log.d(TAG, "fromWebInterceptView ACTION_DOWN blocked");
                                    // Show full-screen black curtain FIRST so zero frames are exposed,
                                    // then remove intercept and back out of the palette.
                                    showZeroFlashCurtain();
                                    startWebGuardLoop();
                                    performGlobalAction(GLOBAL_ACTION_BACK);
                                    showWebBlockedToast();
                                    hideFromWebIntercept();
                                    return true;
                                }
                                return true;
                            }
                        });

                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            width,
                            height,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                            PixelFormat.TRANSLUCENT
                        );
                        params.gravity = Gravity.TOP | Gravity.START;
                        params.x = x;
                        params.y = y;

                        wm.addView(shield, params);
                        fromWebInterceptView = shield;
                        fromWebInterceptParams = params;
                        activeInterceptViews.add(shield);
                        isFromWebInterceptAttached = true;
                        Log.d(TAG, "fromWebInterceptView added at " + x + "," + y + " " + width + "x" + height);
                    } else if (fromWebInterceptParams != null && isFromWebInterceptAttached) {
                        boolean changed = (fromWebInterceptParams.x != x ||
                                           fromWebInterceptParams.y != y ||
                                           fromWebInterceptParams.width != width ||
                                           fromWebInterceptParams.height != height);
                        if (changed) {
                            fromWebInterceptParams.x = x;
                            fromWebInterceptParams.y = y;
                            fromWebInterceptParams.width = width;
                            fromWebInterceptParams.height = height;
                            wm.updateViewLayout(fromWebInterceptView, fromWebInterceptParams);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "positionFromWebIntercept error", e);
                    destroyFromWebIntercept();
                }
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.postAtFrontOfQueue(r);
        }
    }

    /**
     * Move the intercept overlay off-screen (y = -10000) instead of destroying it.
     * This avoids the destroy/create gap that lets touches through.
     */
    private void hideFromWebIntercept() {
        stopInterceptMonitor();
        cancelInterceptProbes();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (fromWebInterceptView == null || fromWebInterceptParams == null || !isFromWebInterceptAttached) {
                    return;
                }
                try {
                    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                    if (wm != null && fromWebInterceptView.isAttachedToWindow()) {
                        // Move off-screen instead of removing - avoids gap
                        fromWebInterceptParams.x = 0;
                        fromWebInterceptParams.y = -10000;
                        fromWebInterceptParams.width = 1;
                        fromWebInterceptParams.height = 1;
                        wm.updateViewLayout(fromWebInterceptView, fromWebInterceptParams);
                        Log.d(TAG, "fromWebIntercept hidden off-screen");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "hideFromWebIntercept error", e);
                    destroyFromWebInterceptImmediate();
                }
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.postAtFrontOfQueue(r);
        }
    }

    private void removeInterceptViewsFromWindowManager(WindowManager wm) {
        List<View> toRemove = new ArrayList<>(activeInterceptViews);
        if (fromWebInterceptView != null && !toRemove.contains(fromWebInterceptView)) {
            toRemove.add(fromWebInterceptView);
        }
        for (View v : toRemove) {
            if (v == null) continue;
            try {
                if (v.isAttachedToWindow()) {
                    wm.removeViewImmediate(v);
                } else {
                    wm.removeView(v);
                }
            } catch (IllegalArgumentException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Failed removing intercept view", e);
            }
        }
        activeInterceptViews.clear();
        fromWebInterceptView = null;
        fromWebInterceptParams = null;
        isFromWebInterceptAttached = false;
    }

    private void destroyFromWebInterceptImmediate() {
        if (fromWebInterceptView == null && activeInterceptViews.isEmpty() && !isFromWebInterceptAttached) {
            return;
        }
        Log.d(TAG, "destroyFromWebIntercept removing view");
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            removeInterceptViewsFromWindowManager(wm);
        } else {
            activeInterceptViews.clear();
            fromWebInterceptView = null;
            fromWebInterceptParams = null;
            isFromWebInterceptAttached = false;
        }
    }

    private void destroyFromWebIntercept() {
        stopInterceptMonitor();
        cancelInterceptProbes();
        cachedFromWebBounds = null;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                destroyFromWebInterceptImmediate();
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.postAtFrontOfQueue(r);
        }
    }

    @Override
    public void onInterrupt() {
        stopWebGuardLoop();
        dismissZeroFlashCurtain();
        destroyFromWebIntercept();
        destroyLoadingBarShield();
    }
}
