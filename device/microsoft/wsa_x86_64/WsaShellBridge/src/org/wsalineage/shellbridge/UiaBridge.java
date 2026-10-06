/*
 * Channel "UIABridge": Android UI elements for Windows UI Automation.
 *
 * The host asks for details of the active Android window and its whole visible element tree (text,
 * description, state, coordinates, relations) and builds a UI Automation provider for its window from them;
 * this is how Windows Narrator reads the Android UI. Actions on an element come back
 * (click, collapse, expand). The guest itself sends only DirtyBit when the tree has changed. The tree
 * is taken by the accessibility service WindowsAccessibilityService.
 *
 * The host switches all this on, not us: it passes the flag HKCU\Software\Microsoft\WSA\UiaBridgeEnabled during
 * the initial sync of channel "Settings", and Microsoft's SystemApp (present in our slots for networking)
 * writes the UserApp.apk service into enabled_accessibility_services, or removes it; this also happens
 * on every boot. We have no UserApp.apk, so the system removes that entry right away; therefore the bridge
 * catches its appearance and keeps its own service enabled until the end of the boot (see syncServiceState).
 * The channel is always kept open: while the service is off, the host asks nothing.
 *
 * Microsoft does the same in UIABridgeRedirectionHandler and UIABridgeAccessibilityService from
 * UserApp.apk; this code is our own. Deviations from the reference are deliberate:
 *   * an action on an element that no longer exists answers with a refusal; the reference crashed on null;
 *   * DirtyBit at most once per DIRTY_MS; the reference sent it on every event, and the host
 *     re-fetched the whole tree each time.
 * Analysis: docs/protocol.md, section "UIABridge".
 */
package org.wsalineage.shellbridge;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;
import org.wsalineage.shellbridge.proto.Uia.AccessibilityNode;
import org.wsalineage.shellbridge.proto.Uia.AccessibilityWindowNode;
import org.wsalineage.shellbridge.proto.Uia.Bounds;
import org.wsalineage.shellbridge.proto.Uia.GetAccessibilityNodesInfo;
import org.wsalineage.shellbridge.proto.Uia.GetAccessibilityWindowInfo;
import org.wsalineage.shellbridge.proto.Uia.PerformAction;
import org.wsalineage.shellbridge.proto.Uia.Result;
import org.wsalineage.shellbridge.proto.Uia.UIABridgeMessage;
import org.wsalineage.shellbridge.proto.Uia.UIABridgeMessage.MessageType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class UiaBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge.UIA";
    private static final String CHANNEL = "UIABridge";
    private static final long RECONNECT_MS = 2_000;
    private static final long DIRTY_MS = 100;
    private static final int NO_NODE = -1;
    /** The UserApp.apk service that SystemApp enables and disables on host command. */
    private static final ComponentName MS_SERVICE = new ComponentName("com.microsoft.windows.userapp",
            "com.microsoft.windows.uiabridge.UIABridgeAccessibilityService");
    /** Boot number (Settings.Global.BOOT_COUNT) in which the host enabled the channel. */
    private static final String HOST_ENABLED_BOOT = "wsa_uiabridge_enabled_boot";

    private static volatile UiaBridge sInstance;

    private final Context mContext;
    private final Handler mH;
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);
    private final ComponentName mOurService;
    /** Watching the setting and DirtyBit happen on this thread. */
    private final Handler mWork;
    private boolean mDirtyPending;
    /** The first DirtyBit per channel connection is logged, the rest silently dropped from the log. */
    private boolean mDirtyLogged;

    public UiaBridge(Context context, Handler handler) {
        mContext = context;
        mH = handler;
        mOurService = new ComponentName(context, WindowsAccessibilityService.class);
        HandlerThread t = new HandlerThread("WsaShellBridge.UIA");
        t.start();
        mWork = t.getThreadHandler();
        sInstance = this;
    }

    static UiaBridge get() {
        return sInstance;
    }

    public void start() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false,
                new ContentObserver(mWork) {
                    @Override
                    public void onChange(boolean selfChange) {
                        syncServiceState();
                    }
                });
        mWork.post(this::syncServiceState);
        mH.post(this::connect);
    }

    /**
     * Our service is enabled if and only if the host enabled the channel in this boot.
     *
     * The entry for Microsoft's service lives a fraction of a second: on every recalculation AccessibilityManagerService
     * (updateServicesLocked) removes services that are not installed, and we have no
     * UserApp.apk. So its appearance is a one-shot signal: we remember the boot number in which it happened.
     * The host reads the flag once per session and sends it only during the initial sync, it cannot disable the channel
     * mid-boot; in the next boot without the flag SystemApp will not write Microsoft's service,
     * the number will not match, and our service will be disabled.
     */
    private void syncServiceState() {
        ContentResolver cr = mContext.getContentResolver();
        String value = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        Set<ComponentName> enabled = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(value)) {
            for (String s : value.split(":")) {
                ComponentName c = ComponentName.unflattenFromString(s);
                if (c != null) enabled.add(c);
            }
        }
        int boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, 0);
        if (enabled.remove(MS_SERVICE)) {
            Settings.Secure.putInt(cr, HOST_ENABLED_BOOT, boot);
            Slog.i(TAG, "host enabled UIABridge in boot " + boot);
        }
        boolean want = Settings.Secure.getInt(cr, HOST_ENABLED_BOOT, -1) == boot;
        if (want == enabled.contains(mOurService)) return;
        if (want) enabled.add(mOurService); else enabled.remove(mOurService);
        StringBuilder sb = new StringBuilder();
        for (ComponentName c : enabled) {
            if (sb.length() > 0) sb.append(':');
            sb.append(c.flattenToString());
        }
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, sb.toString());
        Slog.i(TAG, "accessibility service " + (want ? "on" : "off"));
    }

    private void connect() {
        try {
            mChannel.open();
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "open failed: " + e.getMessage() + ", retry in " + RECONNECT_MS + "ms");
            mH.postDelayed(this::connect, RECONNECT_MS);
        }
    }

    @Override
    public void onClosed() {
        mWork.post(() -> mDirtyLogged = false);
        mH.postDelayed(this::connect, RECONNECT_MS);
    }

    /** The tree or window changed (called from the service); sent to the host at most once per DIRTY_MS. */
    void onTreeChanged() {
        mWork.post(() -> {
            if (mDirtyPending) return;
            mDirtyPending = true;
            mWork.postDelayed(() -> {
                mDirtyPending = false;
                if (!mChannel.isOpen()) return;
                try {
                    mChannel.sendAsync(UIABridgeMessage.newBuilder()
                            .setMessageType(MessageType.DirtyBit).build().toByteArray());
                    if (!mDirtyLogged) {
                        mDirtyLogged = true;
                        Slog.i(TAG, "first DirtyBit sent");
                    }
                } catch (ApiChannel.ApiException e) {
                    Slog.w(TAG, "DirtyBit failed: " + e.getMessage());
                }
            }, DIRTY_MS);
        });
    }

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        UIABridgeMessage msg;
        try {
            msg = UIABridgeMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad UIABridgeMessage: " + e.getMessage(),
                    ResultCode.InvalidProtocolFormat);
        }
        Slog.d(TAG, "host: " + msg.getMessageType()
                + (msg.hasPerformAction() ? " " + msg.getPerformAction().getAction() : ""));
        UIABridgeMessage.Builder reply = msg.toBuilder();
        String error = null;
        switch (msg.getMessageType()) {
            case GetAccessibilityNodesInfo: {
                AccessibilityNodeInfo root = WindowsAccessibilityService.rootInActiveWindow();
                if (root == null) {
                    error = "no active window";
                    break;
                }
                GetAccessibilityNodesInfo.Builder tree = nodes(root);
                Slog.d(TAG, "  -> " + tree.getAccessibilityNodeCount() + " nodes, root "
                        + root.getPackageName() + " " + root.getClassName());
                reply.setGetAccessibilityNodesInfo(tree);
                break;
            }
            case GetAccessibilityWindowInfo: {
                AccessibilityNodeInfo root = WindowsAccessibilityService.rootInActiveWindow();
                AccessibilityWindowInfo w = root != null ? root.getWindow() : null;
                if (w == null) {
                    error = "no active window";
                    break;
                }
                reply.setGetAccessibilityWindowInfo(GetAccessibilityWindowInfo.newBuilder()
                        .setAccessibilityWindowNode(window(w)));
                break;
            }
            case PerformAction:
                error = perform(msg.getPerformAction());
                break;
            default:
                throw new ApiChannel.ApiException("unexpected " + msg.getMessageType() + " from host",
                        ResultCode.InvalidProtocolFormat);
        }
        Result.Builder result = Result.newBuilder().setSucceeded(error == null);
        if (error != null) {
            result.setErrorMessage(error);
            Slog.w(TAG, msg.getMessageType() + ": " + error);
        }
        return reply.setResult(result).build().toByteArray();
    }

    /** Visible tree in breadth-first order, as in the reference: the host expects exactly this order. */
    private static GetAccessibilityNodesInfo.Builder nodes(AccessibilityNodeInfo root) {
        GetAccessibilityNodesInfo.Builder out = GetAccessibilityNodesInfo.newBuilder();
        if (!root.isVisibleToUser()) return out;
        ArrayDeque<Item> queue = new ArrayDeque<>();
        queue.add(new Item(root, NO_NODE, NO_NODE));
        while (!queue.isEmpty()) {
            Item item = queue.remove();
            List<AccessibilityNodeInfo> children = visibleChildren(item.node);
            int self = item.node.hashCode();
            for (int i = 0; i < children.size(); i++) {
                int next = i + 1 < children.size() ? children.get(i + 1).hashCode() : NO_NODE;
                queue.add(new Item(children.get(i), self, next));
            }
            int first = children.isEmpty() ? NO_NODE : children.get(0).hashCode();
            out.addAccessibilityNode(node(item, first));
        }
        return out;
    }

    private static final class Item {
        final AccessibilityNodeInfo node;
        final int parent;
        final int nextSibling;

        Item(AccessibilityNodeInfo node, int parent, int nextSibling) {
            this.node = node;
            this.parent = parent;
            this.nextSibling = nextSibling;
        }
    }

    private static List<AccessibilityNodeInfo> visibleChildren(AccessibilityNodeInfo parent) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo c = parent.getChild(i);
            if (c != null && c.isVisibleToUser()) out.add(c);
        }
        return out;
    }

    /** node_id is the node's hashCode: it is built from the window id and view id and does not change between requests. */
    private static AccessibilityNode node(Item item, int firstChild) {
        AccessibilityNodeInfo n = item.node;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return AccessibilityNode.newBuilder()
                .setNodeId(n.hashCode())
                .setName(xmlSafe(n.getViewIdResourceName()))
                .setControlType(xmlSafe(n.getClassName()))
                .setPackageName(xmlSafe(n.getPackageName()))
                .setText(xmlSafe(n.getText()))
                .setContentDesc(xmlSafe(n.getContentDescription()))
                .setCheckable(n.isCheckable())
                .setChecked(n.isChecked())
                .setClickable(n.isClickable())
                .setIsEnabled(n.isEnabled())
                .setIsKeyboardFocusable(n.isFocusable())
                .setHasKeyboardFocus(n.isFocused())
                .setIsScrollPatternAvailable(n.isScrollable())
                .setIsPassword(n.isPassword())
                .setIsSelected(n.isSelected())
                .setBounds(bounds(r))
                .setParentId(item.parent)
                .setFirstChildId(firstChild)
                .setNextSiblingId(item.nextSibling)
                .build();
    }

    private static AccessibilityWindowNode.Builder window(AccessibilityWindowInfo w) {
        AccessibilityWindowNode.WindowType type = AccessibilityWindowNode.WindowType.forNumber(w.getType());
        Rect r = new Rect();
        w.getBoundsInScreen(r);
        return AccessibilityWindowNode.newBuilder()
                .setId(w.getId())
                .setDisplayId(w.getDisplayId())
                .setTitle(xmlSafe(w.getTitle()))
                .setActive(w.isActive())
                .setFocused(w.isFocused())
                .setLayer(w.getLayer())
                .setWindowType(type != null ? type : AccessibilityWindowNode.WindowType.TYPE_APPLICATION)
                .setNodeHashCode(w.hashCode())
                .setBounds(bounds(r))
                .setCount(0);
    }

    /** null means done, otherwise the reason for refusal. */
    private static String perform(PerformAction a) {
        AccessibilityNodeInfo.AccessibilityAction action = switch (a.getAction()) {
            case CLICK -> AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK;
            case COLLAPSE -> AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE;
            case EXPAND -> AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND;
        };
        AccessibilityNodeInfo root = WindowsAccessibilityService.rootInActiveWindow();
        AccessibilityNodeInfo n = root != null ? find(root, a.getNodeId()) : null;
        if (n == null) return "node " + a.getNodeId() + " not found";
        boolean done = n.performAction(action.getId());
        Slog.i(TAG, a.getAction() + " on " + n.getClassName() + " \"" + n.getText() + "\": " + done);
        return done ? null : a.getAction() + " refused by " + n.getClassName();
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, int id) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.remove();
            if (n.hashCode() == id) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return null;
    }

    private static Bounds bounds(Rect r) {
        return Bounds.newBuilder().setLeft(r.left).setTop(r.top).setRight(r.right).setBottom(r.bottom).build();
    }

    /**
     * String without characters invalid in XML: the host apparently passes text through XML, and
     * the reference replaces them with a dot. null becomes an empty string.
     */
    private static String xmlSafe(CharSequence cs) {
        if (cs == null) return "";
        StringBuilder sb = new StringBuilder(cs.length());
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            boolean bad = (c >= 0x01 && c <= 0x08) || c == 0x0B || c == 0x0C || (c >= 0x0E && c <= 0x1F)
                    || (c >= 0x7F && c <= 0x84) || (c >= 0x86 && c <= 0x9F) || (c >= 0xFDD0 && c <= 0xFDDF)
                    || c == 0xFFFE || c == 0xFFFF;
            sb.append(bad ? '.' : c);
        }
        return sb.toString();
    }
}
