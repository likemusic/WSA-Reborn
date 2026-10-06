/*
 * Accessibility service for the "UIABridge" channel: gives the bridge the element tree of the active
 * window and tells it when the tree has changed. It speaks and draws nothing itself; reading is done by
 * Windows Narrator. UiaBridge enables and disables it on the host's command.
 */
package org.wsalineage.shellbridge;

import android.accessibilityservice.AccessibilityService;
import android.util.Slog;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public final class WindowsAccessibilityService extends AccessibilityService {
    private static final String TAG = "WsaShellBridge.UIA";
    /**
     * Events after which the host's tree becomes stale: click, selection, focus, text, window change,
     * content change, scroll, text selection, accessibility focus, window list; the same
     * mask as the reference.
     */
    private static final int TREE_EVENTS = AccessibilityEvent.TYPE_VIEW_CLICKED
            | AccessibilityEvent.TYPE_VIEW_SELECTED
            | AccessibilityEvent.TYPE_VIEW_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_SCROLLED
            | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
            | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
            | AccessibilityEvent.TYPE_WINDOWS_CHANGED;

    private static volatile WindowsAccessibilityService sInstance;

    /** Root of the active window; null if the service is not connected or there is no active window. */
    static AccessibilityNodeInfo rootInActiveWindow() {
        WindowsAccessibilityService s = sInstance;
        return s != null ? s.getRootInActiveWindow() : null;
    }

    @Override
    protected void onServiceConnected() {
        sInstance = this;
        Slog.i(TAG, "accessibility service connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        Slog.i(TAG, "accessibility service unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if ((event.getEventType() & TREE_EVENTS) == 0) return;
        UiaBridge bridge = UiaBridge.get();
        if (bridge != null) bridge.onTreeChanged();
    }

    @Override
    public void onInterrupt() {
    }
}
