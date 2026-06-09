package com.focusguard.app;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class FocusGuardService extends AccessibilityService {

    private static final String GOOGLE_DOCS_PACKAGE = "com.google.android.apps.docs.editors.docs";
    private static final String PREFS_NAME = "focusguard_prefs";
    private static final String KEY_BLOCKING_ENABLED = "blocking_enabled";

    // Keywords that identify the "From web" image search screen
    private static final String[] WEB_SEARCH_KEYWORDS_EN = {
        "From web", "from web", "Search the web", "search the web"
    };
    private static final String[] WEB_SEARCH_KEYWORDS_BN = {
        "ওয়েব থেকে", "ওয়েব অনুসন্ধান"
    };

    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Check if blocking is enabled
        if (!prefs.getBoolean(KEY_BLOCKING_ENABLED, true)) {
            return;
        }

        // Only process events from Google Docs
        if (event.getPackageName() == null ||
            !GOOGLE_DOCS_PACKAGE.equals(event.getPackageName().toString())) {
            return;
        }

        int eventType = event.getEventType();

        // Process window state changes and content changes
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            // Fast check: scan event text first (cheaper than tree traversal)
            if (containsWebSearchKeyword(event)) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                return;
            }

            // Deep check: traverse the accessibility node tree
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                if (isWebImageSearchScreen(rootNode)) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
                rootNode.recycle();
            }
        }
    }

    /**
     * Fast check: scan event text for web search keywords
     */
    private boolean containsWebSearchKeyword(AccessibilityEvent event) {
        // Check event text
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence text : texts) {
                if (text != null && matchesWebSearchKeyword(text.toString())) {
                    return true;
                }
            }
        }

        // Check content description
        CharSequence contentDesc = event.getContentDescription();
        if (contentDesc != null && matchesWebSearchKeyword(contentDesc.toString())) {
            return true;
        }

        return false;
    }

    /**
     * Deep check: traverse node tree to find web image search indicators
     */
    private boolean isWebImageSearchScreen(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // Check this node's text
        CharSequence text = node.getText();
        if (text != null && matchesWebSearchKeyword(text.toString())) {
            return true;
        }

        // Check content description
        CharSequence desc = node.getContentDescription();
        if (desc != null && matchesWebSearchKeyword(desc.toString())) {
            return true;
        }

        // Search by text directly (faster for exact matches)
        for (String keyword : WEB_SEARCH_KEYWORDS_EN) {
            List<AccessibilityNodeInfo> found = node.findAccessibilityNodeInfosByText(keyword);
            if (found != null && !found.isEmpty()) {
                for (AccessibilityNodeInfo n : found) {
                    n.recycle();
                }
                return true;
            }
        }
        for (String keyword : WEB_SEARCH_KEYWORDS_BN) {
            List<AccessibilityNodeInfo> found = node.findAccessibilityNodeInfosByText(keyword);
            if (found != null && !found.isEmpty()) {
                for (AccessibilityNodeInfo n : found) {
                    n.recycle();
                }
                return true;
            }
        }

        // Recursive child traversal as fallback
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = isWebImageSearchScreen(child);
                child.recycle();
                if (found) return true;
            }
        }

        return false;
    }

    /**
     * Check if text matches any web search keyword
     */
    private boolean matchesWebSearchKeyword(String text) {
        if (text == null || text.isEmpty()) return false;

        for (String keyword : WEB_SEARCH_KEYWORDS_EN) {
            if (text.contains(keyword)) return true;
        }
        for (String keyword : WEB_SEARCH_KEYWORDS_BN) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        // Service interrupted
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        // Service is now connected and ready
    }
}
