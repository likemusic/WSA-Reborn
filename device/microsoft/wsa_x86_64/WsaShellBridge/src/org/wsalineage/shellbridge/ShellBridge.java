/*
 * "Android tasks <-> Windows windows" bridge over the RCM channel "Shell".
 *
 * Our own implementation of the protocol from docs/protocol.md. Unlike the original Microsoft
 * component (WsaTaskManager inside SystemUIApp.apk, tied to ShellTaskOrganizer from WM Shell and
 * therefore not starting on Android 14), only version-stable platform entry points are used here:
 * ITaskStackListener for events, IActivityTaskManager.getTasks() for state,
 * WindowOrganizer.applyTransaction() to apply the geometry sent by the host.
 *
 * Deliberately not implemented in the first version (see docs/protocol.md, section "Shell"):
 * the HomeApp placeholder activity, the PiP menu. Instead of IWsaSystemServer.updateDisplayBounds (their
 * WsaLaunchParamsModifier in system_server) a new freeform task is fitted to the host work area
 * right here, before CreateTopLevelWindow — see fitToHostWorkArea().
 */
package org.wsalineage.shellbridge;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.TaskStackListener;
import android.app.WindowConfiguration;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;
import android.window.WindowOrganizer;

import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;
import org.wsalineage.shellbridge.proto.Shell;
import org.wsalineage.shellbridge.proto.Shell.ShellMessage;
import org.wsalineage.shellbridge.proto.Shell.ShellMessage.MessageType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ShellBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge";
    private static final String CHANNEL = "Shell";
    private static final long CREATE_WINDOW_TIMEOUT_MS = 15_000;
    private static final long RECONNECT_MIN_MS = 1_000;
    private static final long RECONNECT_MAX_MS = 10_000;
    // DisplayInfo.uniqueId of the form "local:100" — the numeric part is the host's wsaDisplayId.
    private static final int UNIQUE_ID_PREFIX_LEN = "local:".length();
    private static final int DISPLAY_IME_POLICY_LOCAL = 0;
    // As in WsaLaunchParamsModifier: a window of at least 90% of the work area is maximized.
    private static final float MAXIMIZE_THRESHOLD = 0.9f;
    private static final long[] PIP_SETTLE_DELAYS_MS = {400, 1200};
    private static final long[] BOUNDS_REPORT_DELAYS_MS = {300, 1000};
    // For how long after UpdateAndroidWindow bounds corrections made by Android itself count as part of
    // the host command (reported to it) rather than as an unsolicited move (snapBackToHostBounds).
    private static final long HOST_BOUNDS_SETTLE_MS = 2_000;
    // At most one transition per this interval while dragging the frame (see updateAndroidTask): roughly
    // how long one transition with an app redraw takes (05.10.2026, lineage21).
    private static final long DRAG_TRANSITION_INTERVAL_MS = 250;
    // The host creates the PiP window on CreateTopLevelWindow and then manages its geometry itself:
    // later UpdateClientWindow messages with PiP bounds are not applied (27.09.2026, ETW: PipWindowCreated
    // with the task size from BEFORE entering PiP). So a PiP task not yet announced is announced not
    // immediately but once WM Shell gives it PiP bounds: on the test bench that is ~1.2 s after entering.
    private static final long PIP_ANNOUNCE_DELAY_MS = 1_300;

    private static final class Task {
        int taskId;
        int displayId;
        String packageName;
        WindowContainerToken token;
        int windowingMode;
        boolean userResize;
        Rect hostBounds;     // last freeform window bounds known to the host (px)
        long hostBoundsAt;   // uptimeMillis when the host set them
        long dragTransitionAt; // uptimeMillis of the last transition while resizing
        boolean pipSent;     // the host has already been sent the window in PiP mode (CreateTopLevelWindow or Update)
        boolean announced;   // CreateTopLevelWindow sent to the host in the current channel session
        boolean removing;
    }

    private final Context mContext;
    private final IActivityTaskManager mAtm = ActivityTaskManager.getService();
    private final IWindowManager mWm = WindowManagerGlobal.getWindowManagerService();
    private final WindowOrganizer mOrganizer = new WindowOrganizer();
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);
    private final HandlerThread mThread = new HandlerThread(TAG);
    private Handler mH;

    private final Map<Integer, Task> mTasks = new HashMap<>();
    private final Map<Long, Integer> mLogicalByUnique = new HashMap<>();
    private final Map<Integer, Long> mUniqueByLogical = new HashMap<>();
    private final Set<Integer> mUnsupportedDisplays = new HashSet<>();
    // Host work area (without the Windows taskbar) and the maximized window rectangle, in px
    // of the guest display; arrive in UpdateAndroidDisplayInfo when the channel opens.
    private final Map<Integer, Rect> mStableBounds = new HashMap<>();
    private final Map<Integer, Rect> mMaximizedBounds = new HashMap<>();
    private final Map<Integer, Long> mPinnedAt = new HashMap<>();
    // Latest not-yet-applied resize bounds per task (channel thread -> mH).
    private final Map<Integer, Shell.WindowInfo> mPendingDrag = new HashMap<>();
    private int mLastFocusedOnWindows = -1;
    private int mLastFocusedOnAndroid = -1;
    private long mReconnectDelay = RECONNECT_MIN_MS;

    // Who to notify that a package got a host window (the Windows media panel is bound to the
    // package's window — MediaControlsBridge re-announces the media session). Called on the bridge thread.
    private static volatile Consumer<String> sHostWindowListener;

    /** Subscription to "host created a window for a package"; called after the host replies to CreateTopLevelWindow. */
    public static void setHostWindowListener(Consumer<String> onWindowCreated) {
        sHostWindowListener = onWindowCreated;
    }

    public ShellBridge(Context context) {
        mContext = context;
    }

    /** Shared bridge worker thread — the other channels live on it too. */
    public Handler handler() { return mH; }

    public void start() {
        mThread.start();
        mH = new Handler(mThread.getLooper());
        mH.post(() -> {
            DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            for (Display d : dm.getDisplays()) initializeDisplay(d.getDisplayId());
            suppressImmersiveConfirmation();
            mutePhysicalKeyboardNotification();
            DefaultHome.applyOnce(mContext, mH);
            // A window per task until the host says otherwise: undo a Launcher3 home left by a one-window session.
            DefaultHome.forOneWindow(mContext, false, null);
            dm.registerDisplayListener(mDisplayListener, mH);
            try {
                mAtm.registerTaskStackListener(mTaskStackListener);
            } catch (RemoteException e) {
                Slog.wtf(TAG, "registerTaskStackListener failed", e);
            }
            connect();
        });
        registerDebugReceiver();
    }

    /**
     * Debugging without the host: the same path as UpdateAndroidWindow from the host (F11, "maximize") —
     * `adb shell am broadcast -a org.wsalineage.shellbridge.DEBUG_WINDOW --ei task 41
     * --es mode Fullscreen --es bounds 0,0,2560,1600`. Only for senders holding DUMP (shell, root).
     */
    private void registerDebugReceiver() {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int task = intent.getIntExtra("task", -1);
                String mode = intent.getStringExtra("mode");
                String bounds = intent.getStringExtra("bounds");
                Shell.WindowInfo.Builder w = Shell.WindowInfo.newBuilder().setTaskId(task)
                        .setUserResize(false)
                        .setWindowMode(mode == null ? Shell.WindowMode.Freeform
                                : Shell.WindowMode.valueOf(mode));
                if (bounds != null) {
                    String[] v = bounds.split(",");
                    w.setBounds(bounds(new Rect(Integer.parseInt(v[0]), Integer.parseInt(v[1]),
                            Integer.parseInt(v[2]), Integer.parseInt(v[3]))));
                }
                Slog.i(TAG, "debug UpdateAndroidWindow task " + task + " " + mode + " " + bounds);
                updateAndroidTask(w.build());
            }
        };
        mContext.registerReceiver(r, new IntentFilter("org.wsalineage.shellbridge.DEBUG_WINDOW"),
                android.Manifest.permission.DUMP, mH, Context.RECEIVER_EXPORTED);
    }

    /**
     * The "full screen — swipe down to exit" confirmation (ImmersiveModeConfirmation) is harmful
     * in WSA. It pops up when an app in a full-screen window (F11 on the host) hides the system
     * bars, takes focus and keeps it until "OK" is pressed. The host does not really show this
     * taskless window to the user, and the keyboard never reaches the app (27.09.2026,
     * WindowTest: mCurrentFocus=ImmersiveModeConfirmation). There is no "swipe down" gesture in WSA
     * anyway; full screen is left with F11 or the host button. The standard switch is the setting
     * immersive_mode_confirmations=confirmed, read by both WM and SystemUI.
     */
    private void suppressImmersiveConfirmation() {
        try {
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.IMMERSIVE_MODE_CONFIRMATIONS, "confirmed");
        } catch (RuntimeException e) {
            Slog.w(TAG, "cannot disable immersive mode confirmation: " + e);
        }
    }

    /**
     * Android 14+ treats the uinput keyboard of Microsoft's vendor virtualinputservice ("virtual_keyboard") as a
     * physical one and, finding no layout for our IME, keeps posting "Configure virtual_keyboard" (seen on the own
     * host, 17.10.2026). Keys reach Android through the bridge and text through TSF, both laid out by Windows, so
     * the channel of that notification (SystemNotificationChannels.PHYSICAL_KEYBOARD of "android") is turned off;
     * blocking a channel also cancels what it has posted.
     */
    private void mutePhysicalKeyboardNotification() {
        try {
            INotificationManager nm = NotificationManager.getService();
            NotificationChannel ch = nm.getNotificationChannelForPackage(
                    "android", Process.SYSTEM_UID, "PHYSICAL_KEYBOARD", null, false);
            if (ch == null || ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            ch.setImportance(NotificationManager.IMPORTANCE_NONE);
            nm.updateNotificationChannelForPackage("android", Process.SYSTEM_UID, ch);
            Slog.i(TAG, "physical keyboard notification channel turned off");
        } catch (RemoteException | RuntimeException | LinkageError e) {
            Slog.w(TAG, "cannot turn off the physical keyboard notification: " + e);
        }
    }

    // ---------------------------------------------------------------- channel

    private void connect() {
        try {
            mChannel.open();
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "open(" + CHANNEL + ") failed: " + e.getMessage()
                    + ", retry in " + mReconnectDelay + "ms");
            mH.postDelayed(this::connect, mReconnectDelay);
            mReconnectDelay = Math.min(mReconnectDelay * 2, RECONNECT_MAX_MS);
            return;
        }
        mReconnectDelay = RECONNECT_MIN_MS;
        mLastFocusedOnWindows = -1;
        for (Task t : mTasks.values()) t.announced = false;
        syncAllTasks();
        showAnnouncedTasks();
    }

    /**
     * After a reconnect the host recreates the windows (it closed the old ones when the channel dropped),
     * but shows only the window of the task brought to front; the rest stay hidden
     * (checked 27.09.2026: am crash of the bridge -> "Settings" window vis=False; neither redrawing the task
     * nor SetFocusedWindow without bringing to front helps, am start of that task does).
     * So every visible task is brought to front in turn, and finally the one that had focus.
     * Called on every connect: a reconnect within the process and the first connect of a bridge
     * process restarted after a crash look the same to the host, and on a normal boot there are no
     * app tasks yet at this point.
     */
    private void showAnnouncedTasks() {
        int focused = mLastFocusedOnAndroid;
        boolean any = false;
        for (Task t : mTasks.values()) {
            if (!t.announced || t.taskId == focused) continue;
            RunningTaskInfo info = findTask(t.taskId);
            if (info == null || !info.isVisible) continue;
            Slog.i(TAG, "show task " + t.taskId + " after reconnect");
            reorderTask(t.taskId, true);
            any = true;
        }
        if (any && focused != -1) reorderTask(focused, true);
    }

    @Override
    public void onClosed() {
        // The host (WsaClient.exe) restarted or went away — reconnect, windows will be announced again.
        mH.postDelayed(this::connect, mReconnectDelay);
    }

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        ShellMessage msg;
        try {
            msg = ShellMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad ShellMessage: " + e,
                    ResultCode.InvalidProtocolFormat);
        }
        Slog.d(TAG, "host -> " + msg.getMessageType());
        switch (msg.getMessageType()) {
            case SetFocusedWindow:
                mH.post(() -> setFocusedWindowOnAndroid(msg.getSetFocusedWindow().getTaskId()));
                break;
            case ClearFocusedWindow:
                mH.post(() -> clearFocusedWindowOnAndroid(msg.getClearFocusedWindow().getTaskId()));
                break;
            case RemoveWindow:
                mH.post(() -> removeAndroidWindow(msg.getRemoveWindow().getTaskId(),
                        msg.getRemoveWindow().getForce()));
                break;
            case UpdateAndroidWindow:
                postUpdateAndroidTask(msg.getUpdateAndroidWindow().getWindowInfo());
                break;
            case OnWindowMinimized:
                mH.post(() -> reorderTask(msg.getOnWindowMinimized().getTaskId(),
                        !msg.getOnWindowMinimized().getMinimized()));
                break;
            case UpdateAndroidDisplayInfo:
                mH.post(() -> updateAndroidDisplayInfo(msg.getUpdateAndroidDisplayInfo()));
                break;
            case RestorePipWindow:
                mH.post(() -> restorePipWindow(msg.getRestorePipWindow().getTaskId()));
                break;
            default:
                throw new ApiChannel.ApiException("unexpected message type " + msg.getMessageType(),
                        ResultCode.InvalidProtocolFormat);
        }
        // Reply to the host is an echo of the request (the original implementation does the same).
        return payload;
    }

    private void sendAsync(ShellMessage msg) {
        if (!mChannel.isOpen()) return;
        Slog.d(TAG, "-> host " + msg.getMessageType());
        try {
            mChannel.sendAsync(msg.toByteArray());
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "send " + msg.getMessageType() + " failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- displays

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) { initializeDisplay(displayId); }
                @Override public void onDisplayChanged(int displayId) { }
                @Override public void onDisplayRemoved(int displayId) {
                    Long unique = mUniqueByLogical.remove(displayId);
                    if (unique != null) mLogicalByUnique.remove(unique);
                    mUnsupportedDisplays.remove(displayId);
                    mStableBounds.remove(displayId);
                    mMaximizedBounds.remove(displayId);
                }
            };

    /**
     * The host's wsaDisplayId is the HWC display number, which is also the port of the display's physical
     * address. Up to and including Android 16 it was also in uniqueId ("local:100"); in Android 17 SurfaceFlinger
     * builds the id from EDID ("local:4619827122562783332", address StablePhysical{…, port=100}),
     * and the host answered CreateTopLevelWindow with E_UNEXPECTED for the foreign number — no windows were
     * created (03.10.2026). The port is read via reflection: in Android 17 the address has its own class.
     */
    static Long displayPort(DisplayInfo info) {
        Object address = info.address;
        if (address == null) return null;
        try {
            Object port = address.getClass().getMethod("getPort").invoke(address);
            return port instanceof Number ? ((Number) port).longValue() & 0xff : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private void initializeDisplay(int displayId) {
        DisplayInfo info = DisplayManagerGlobal.getInstance().getDisplayInfo(displayId);
        if (info == null) return;
        if (info.type != Display.TYPE_INTERNAL) {
            mUnsupportedDisplays.add(displayId);
            return;
        }
        long unique;
        Long port = displayPort(info);
        if (port != null) {
            unique = port;
        } else {
            try {
                unique = Long.parseLong(info.uniqueId.substring(UNIQUE_ID_PREFIX_LEN));
            } catch (RuntimeException e) {
                Slog.w(TAG, "display " + displayId + ": cannot parse uniqueId " + info.uniqueId);
                mUnsupportedDisplays.add(displayId);
                return;
            }
        }
        mLogicalByUnique.put(unique, displayId);
        mUniqueByLogical.put(displayId, unique);
        Slog.i(TAG, "display " + displayId + " -> wsaDisplayId " + unique);
        try {
            mWm.clearForcedDisplaySize(displayId);
            setShouldShowSystemDecors(displayId);
            mWm.setDisplayImePolicy(displayId, DISPLAY_IME_POLICY_LOCAL);
            mWm.setForcedDisplayDensityForUser(displayId, Math.round(info.physicalXDpi),
                    UserHandle.USER_CURRENT);
            mWm.setWindowingMode(displayId, WindowConfiguration.WINDOWING_MODE_FREEFORM);
        } catch (RemoteException e) {
            Slog.wtf(TAG, "display " + displayId + " init failed", e);
        }
    }

    /**
     * System decorations (IME, wallpaper) on the WSA display. Up to and including Android 16 — an explicit
     * IWindowManager.setShouldShowSystemDecors call; in Android 17 the method is gone from the interface and
     * the platform decides itself (DisplayUpdater -> setShouldShowSystemDecorationsForNewDisplay). Via reflection
     * so that one apk builds and works on all versions (02.10.2026).
     */
    private void setShouldShowSystemDecors(int displayId) throws RemoteException {
        try {
            IWindowManager.class.getMethod("setShouldShowSystemDecors", int.class, boolean.class)
                    .invoke(mWm, displayId, true);
        } catch (NoSuchMethodException e) {
            Slog.i(TAG, "display " + displayId + ": setShouldShowSystemDecors is absent in this"
                    + " Android version — the platform handles decorations");
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RemoteException) throw (RemoteException) e.getCause();
            Slog.w(TAG, "display " + displayId + ": setShouldShowSystemDecors failed", e.getCause());
        } catch (IllegalAccessException e) {
            Slog.w(TAG, "display " + displayId + ": setShouldShowSystemDecors failed", e);
        }
    }

    private void updateAndroidDisplayInfo(Shell.UpdateAndroidDisplayInfo msg) {
        for (Shell.UpdateAndroidDisplayInfo.DisplayParameters p : msg.getDisplayParametersList()) {
            Integer displayId = mLogicalByUnique.get(p.getWsaDisplayId());
            if (displayId == null) continue;
            if (p.hasDensity()) {
                try {
                    mWm.setForcedDisplayDensityForUser(displayId, p.getDensity(),
                            UserHandle.USER_CURRENT);
                } catch (RemoteException e) {
                    Slog.e(TAG, "setForcedDisplayDensity failed", e);
                }
            }
            if (p.hasWindowingMode()) {
                // The own host shows Android in one window: apps fullscreen in it, as on a tablet;
                // back to freeform when every task gets a host window again (17.10.2026).
                try {
                    mWm.setWindowingMode(displayId, p.getWindowingMode());
                    Slog.i(TAG, "display " + displayId + " windowing mode " + p.getWindowingMode());
                } catch (RemoteException e) {
                    Slog.e(TAG, "setWindowingMode failed", e);
                }
                // One window: Launcher3's desktop as home (its taskbar launches apps only then); shown at
                // once when no app is in front.
                DefaultHome.forOneWindow(mContext,
                        p.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                        () -> mH.post(this::showHomeIfInFront));
            }
            if (p.hasStableBounds()) {
                // At Microsoft this goes to IWsaSystemServer.updateDisplayBounds (their service inside
                // system_server); here fitToHostWorkArea() applies the bounds to new tasks.
                Rect stable = rect(p.getStableBounds());
                Rect maximized = p.hasMaximizedBounds() ? rect(p.getMaximizedBounds()) : stable;
                mStableBounds.put(displayId, stable);
                mMaximizedBounds.put(displayId, maximized);
                Slog.i(TAG, "display " + displayId + " stable=" + stable
                        + " maximized=" + maximized);
            }
        }
    }

    // ---------------------------------------------------------------- task events

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override public void onTaskCreated(int taskId, ComponentName component) {
            mH.post(() -> { wakeAndroid(); refreshTask(taskId); });
        }
        @Override public void onTaskMovedToFront(RunningTaskInfo info) {
            mH.post(() -> onTaskToFront(info));
        }
        @Override public void onTaskMovedToBack(RunningTaskInfo info) {
            mH.post(() -> onTaskToBack(info));
        }
        @Override public void onTaskRemoved(int taskId) {
            mH.post(() -> onTaskGone(taskId));
        }
        @Override public void onTaskDescriptionChanged(RunningTaskInfo info) {
            mH.post(() -> onTaskChanged(info));
        }
        @Override public void onTaskDisplayChanged(int taskId, int newDisplayId) {
            mH.post(() -> refreshTask(taskId));
        }
        @Override public void onTaskFocusChanged(int taskId, boolean focused) {
            mH.post(() -> { if (focused) refreshTask(taskId); });
        }
        // TaskStackListener does not report windowing mode changes (PiP and back) otherwise: at Microsoft
        // they come via onTaskInfoChanged from WM Shell. Pinned is reported at the start of the transition,
        // while WM Shell sets the final PiP bounds when it ends — hence two more delayed updates
        // (27.09.2026: without them the host window kept its old size at the old position).
        @Override public void onActivityPinned(String packageName, int userId, int taskId,
                int rootTaskId) {
            mH.post(() -> {
                mPinnedAt.putIfAbsent(taskId, SystemClock.uptimeMillis());
                refreshTask(taskId);
            });
            for (long d : PIP_SETTLE_DELAYS_MS) mH.postDelayed(() -> refreshTask(taskId), d);
        }
        @Override public void onActivityUnpinned() {
            mH.post(ShellBridge.this::refreshAllTasks);
            for (long d : PIP_SETTLE_DELAYS_MS) mH.postDelayed(ShellBridge.this::refreshAllTasks, d);
        }
    };

    private void refreshAllTasks() {
        for (Integer taskId : new ArrayList<>(mTasks.keySet())) refreshTask(taskId);
    }

    /** The new home right away, but only over the old one: an app in front stays where it is. */
    private void showHomeIfInFront() {
        try {
            List<RunningTaskInfo> top = mAtm.getTasks(1, false, false, Display.INVALID_DISPLAY);
            if (top.isEmpty()
                    || top.get(0).getActivityType() != WindowConfiguration.ACTIVITY_TYPE_HOME) {
                return;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "getTasks failed", e);
            return;
        }
        mContext.startActivityAsUser(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), UserHandle.CURRENT);
    }

    private RunningTaskInfo findTask(int taskId) {
        try {
            List<RunningTaskInfo> tasks = mAtm.getTasks(Integer.MAX_VALUE, false, false,
                    Display.INVALID_DISPLAY);
            for (RunningTaskInfo t : tasks) if (t.taskId == taskId) return t;
        } catch (RemoteException e) {
            Slog.e(TAG, "getTasks failed", e);
        }
        return null;
    }

    private void syncAllTasks() {
        List<RunningTaskInfo> tasks;
        try {
            tasks = mAtm.getTasks(Integer.MAX_VALUE, false, false, Display.INVALID_DISPLAY);
        } catch (RemoteException e) {
            Slog.e(TAG, "getTasks failed", e);
            return;
        }
        // getTasks returns top to bottom; announce bottom-up so the window order matches.
        for (int i = tasks.size() - 1; i >= 0; i--) onTaskChanged(tasks.get(i));
        for (RunningTaskInfo t : tasks) {
            if (t.isFocused && isCandidate(t)) { setFocusedWindowOnWindows(t.taskId); break; }
        }
    }

    private void refreshTask(int taskId) {
        RunningTaskInfo info = findTask(taskId);
        if (info != null) onTaskChanged(info);
    }

    /** Single entry point: a task appeared or changed — announce it to the host or update it. */
    private void onTaskChanged(RunningTaskInfo info) {
        Task t = mTasks.get(info.taskId);
        if (t == null) {
            t = new Task();
            t.taskId = info.taskId;
            mTasks.put(info.taskId, t);
        }
        t.displayId = info.displayId;
        t.token = info.token;
        t.windowingMode = info.getWindowingMode();
        boolean pinned = t.windowingMode == WindowConfiguration.WINDOWING_MODE_PINNED;
        if (!pinned) {
            t.pipSent = false;
            mPinnedAt.remove(t.taskId);
        }
        // As in the reference: on entering PiP the window is announced visible (firstAppeared) so the host
        // shows it even if the task is still mid-transition.
        boolean enteredPip = pinned && !t.pipSent;
        if (info.baseActivity != null) t.packageName = info.baseActivity.getPackageName();
        if (t.removing || !isCandidate(info) || !mChannel.isOpen()) return;

        // The host applies the first message about a task in PiP (be it CreateTopLevelWindow or UpdateClientWindow
        // with a mode change) once: it takes the PiP window size from it and never changes it afterwards.
        // 27.09.2026: an announced freeform task went to PiP -> first Update with old bounds
        // -> host window 1645×962 instead of 918×516. So we wait for bounds from WM Shell.
        if (enteredPip) {
            long now = SystemClock.uptimeMillis();
            Long pinnedAt = mPinnedAt.get(t.taskId);
            if (pinnedAt == null) {
                mPinnedAt.put(t.taskId, now);
                pinnedAt = now;
            }
            long wait = pinnedAt + PIP_ANNOUNCE_DELAY_MS - now;
            if (wait > 0) {
                final int id = t.taskId;
                mH.postDelayed(() -> refreshTask(id), wait + 50);
                return;
            }
        }
        if (!t.announced) {
            Shell.WindowInfo.Builder w = windowInfo(info, true);
            Rect fitted = fitToHostWorkArea(info);
            if (fitted != null) w.setBounds(bounds(fitted));
            Slog.i(TAG, "CreateTopLevelWindow task " + t.taskId + " " + t.packageName
                    + " bounds=" + rect(w.getBounds()) + " mode=" + w.getWindowMode());
            ShellMessage msg = ShellMessage.newBuilder()
                    .setMessageType(MessageType.CreateTopLevelWindow)
                    .setCreateTopLevelWindow(Shell.CreateTopLevelWindow.newBuilder()
                            .setWindowInfo(w))
                    .build();
            try {
                mChannel.send(msg.toByteArray(), CREATE_WINDOW_TIMEOUT_MS);
                t.announced = true;
                t.hostBounds = t.windowingMode == WindowConfiguration.WINDOWING_MODE_FREEFORM
                        ? rect(w.getBounds()) : null;
                t.pipSent = pinned;
                Consumer<String> l = sHostWindowListener;
                if (l != null && t.packageName != null) {
                    try {
                        l.accept(t.packageName);
                    } catch (RuntimeException e) {
                        Slog.e(TAG, "host window listener failed", e);
                    }
                }
            } catch (ApiChannel.ApiException e) {
                Slog.e(TAG, "CreateTopLevelWindow task " + t.taskId + " failed: " + e.getMessage());
                return;
            }
        } else {
            if (snapBackToHostBounds(t, info)) return;
            Shell.WindowInfo.Builder w = windowInfo(info, enteredPip);
            Slog.d(TAG, "UpdateClientWindow task " + t.taskId + " bounds=" + rect(w.getBounds())
                    + " mode=" + w.getWindowMode() + " visible=" + w.getVisible());
            sendAsync(ShellMessage.newBuilder()
                    .setMessageType(MessageType.UpdateClientWindow)
                    .setUpdateClientWindow(Shell.UpdateClientWindow.newBuilder().setInfo(w))
                    .build());
            if (pinned) t.pipSent = true;
            // The host now knows these bounds — unsolicited moves are measured from them.
            if (t.hostBounds != null) t.hostBounds = rect(w.getBounds());
        }
        if (info.isFocused && mLastFocusedOnAndroid != info.taskId) {
            mLastFocusedOnAndroid = info.taskId;
            setFocusedWindowOnWindows(info.taskId);
        }
    }

    /**
     * Fit a new freeform task to the host work area — what Microsoft's WsaLaunchParamsModifier does
     * in system_server. The guest TaskLaunchParamsModifier centers the window in its own display
     * and knows nothing about the Windows taskbar, so the window may end up under it or off the
     * edge. Rules as in the reference: at least 90% of the work area -> maximize (resizeable tasks
     * only, otherwise the app goes into size-compat), otherwise move inside stable,
     * shrink if larger. Specifically stable, not maximized as in the reference: the host draws the window
     * title bar above the task (38 px at 125%), and stable.top leaves exactly that. A task with top=30
     * inside maximized gave a host window with top=-8, i.e. the title bar off-screen (27.09.2026).
     * Applied as a single WCT before CreateTopLevelWindow, so the host window appears in place right away.
     *
     * @return the new bounds if they had to be changed, otherwise null
     */
    private Rect fitToHostWorkArea(RunningTaskInfo info) {
        if (info.getWindowingMode() != WindowConfiguration.WINDOWING_MODE_FREEFORM
                || info.token == null) {
            return null;
        }
        Rect stable = mStableBounds.get(info.displayId);
        Rect max = mMaximizedBounds.get(info.displayId);
        Rect cur = info.getConfiguration().windowConfiguration.getBounds();
        if (stable == null || max == null || stable.isEmpty() || cur.isEmpty()) return null;

        Rect out = new Rect(cur);
        if (info.isResizeable && cur.width() >= stable.width() * MAXIMIZE_THRESHOLD
                && cur.height() >= stable.height() * MAXIMIZE_THRESHOLD) {
            out.set(max);
        } else {
            if (info.isResizeable) {
                out.right = out.left + Math.min(out.width(), stable.width());
                out.bottom = out.top + Math.min(out.height(), stable.height());
            }
            int dx = 0;
            if (out.width() > stable.width() || out.left < stable.left) dx = stable.left - out.left;
            else if (out.right > stable.right) dx = stable.right - out.right;
            int dy = 0;
            if (out.height() > stable.height() || out.top < stable.top) dy = stable.top - out.top;
            else if (out.bottom > stable.bottom) dy = stable.bottom - out.bottom;
            out.offset(dx, dy);
        }
        if (out.equals(cur)) return null;
        Slog.i(TAG, "task " + info.taskId + ": fit " + cur + " -> " + out
                + " (host stable=" + stable + " maximized=" + max + ")");
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(info.token, out);
        applyChange(wct, WindowManager.TRANSIT_CHANGE);
        return out;
    }

    private void onTaskToFront(RunningTaskInfo info) {
        onTaskChanged(info);
        if (isCandidate(info)) {
            mLastFocusedOnAndroid = info.taskId;
            setFocusedWindowOnWindows(info.taskId);
        }
    }

    private void onTaskToBack(RunningTaskInfo info) {
        Task t = mTasks.get(info.taskId);
        if (t != null && t.announced && isCandidate(info)) {
            sendAsync(ShellMessage.newBuilder()
                    .setMessageType(MessageType.MinimizeClientWindow)
                    .setMinimizeClientWindow(Shell.MinimizeClientWindow.newBuilder()
                            .setTaskId(info.taskId))
                    .build());
        }
    }

    private void onTaskGone(int taskId) {
        Task t = mTasks.remove(taskId);
        mPinnedAt.remove(taskId);
        if (mLastFocusedOnAndroid == taskId) mLastFocusedOnAndroid = -1;
        boolean wasFocusedOnWindows = mLastFocusedOnWindows == taskId;
        if (wasFocusedOnWindows) mLastFocusedOnWindows = -1;
        if (t == null || !t.announced) return;
        Slog.i(TAG, "RemoveWindow task " + taskId);
        sendAsync(ShellMessage.newBuilder()
                .setMessageType(MessageType.RemoveWindow)
                .setRemoveWindow(Shell.RemoveWindow.newBuilder().setTaskId(taskId))
                .build());
        if (wasFocusedOnWindows && mLastFocusedOnAndroid != -1) {
            setFocusedWindowOnWindows(mLastFocusedOnAndroid);
        }
    }

    private void setFocusedWindowOnWindows(int taskId) {
        Task t = mTasks.get(taskId);
        if (t == null || !t.announced || mLastFocusedOnWindows == taskId) return;
        mLastFocusedOnWindows = taskId;
        Slog.d(TAG, "SetFocusedWindow task " + taskId);
        sendAsync(ShellMessage.newBuilder()
                .setMessageType(MessageType.SetFocusedWindow)
                .setSetFocusedWindow(Shell.SetFocusedWindow.newBuilder()
                        .setTaskId(taskId).setSetForeground(true))
                .build());
    }

    // ---------------------------------------------------------------- host commands

    private void setFocusedWindowOnAndroid(int taskId) {
        if (taskId == -1 || mLastFocusedOnWindows == taskId) return;
        mLastFocusedOnWindows = taskId;
        reorderTask(taskId, true);
    }

    private void clearFocusedWindowOnAndroid(int taskId) {
        if (mLastFocusedOnWindows == taskId) mLastFocusedOnWindows = -1;
        // At Microsoft the HomeApp placeholder activity is brought to front here; our build
        // does not have it, and the guest screen is not shown to the host — do nothing.
    }

    private void removeAndroidWindow(int taskId, boolean force) {
        Task t = mTasks.get(taskId);
        if (t == null) return;
        t.removing = true;
        if (mLastFocusedOnWindows == taskId) mLastFocusedOnWindows = -1;
        Slog.i(TAG, "host removes task " + taskId + " force=" + force);
        try {
            if (force && t.packageName != null) {
                android.app.ActivityManager.getService()
                        .forceStopPackage(t.packageName, mContext.getUserId());
            } else {
                mAtm.removeTask(taskId);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "remove task " + taskId + " failed", e);
        }
    }

    /**
     * While the user drags the frame the host sends UpdateAndroidWindow every ~6 ms (05.10.2026,
     * lineage21: 46 messages per second). Intermediate bounds not yet applied are
     * collapsed to the latest; the final message (userResize=false) goes as is, after them.
     */
    private void postUpdateAndroidTask(Shell.WindowInfo info) {
        if (!info.getUserResize()) {
            mH.post(() -> updateAndroidTask(info));
            return;
        }
        final int id = info.getTaskId();
        synchronized (mPendingDrag) {
            if (mPendingDrag.put(id, info) != null) return;
        }
        mH.post(() -> applyPendingDrag(id));
    }

    private void applyPendingDrag(int taskId) {
        Shell.WindowInfo latest;
        synchronized (mPendingDrag) {
            latest = mPendingDrag.remove(taskId);
        }
        if (latest != null) updateAndroidTask(latest);
    }

    private void updateAndroidTask(Shell.WindowInfo info) {
        Task t = mTasks.get(info.getTaskId());
        if (t == null || t.token == null) return;
        if (t.windowingMode == WindowConfiguration.WINDOWING_MODE_PINNED) {
            // The PiP task's geometry is managed by WM Shell (PipTaskOrganizer): WCT.setBounds from
            // the bridge changes only its configuration, the surface and input window stay in the PiP corner.
            // And the host, having received the new bounds back, computes mouse coordinates from them — clicks
            // went to the launcher (27.09.2026: host sent 0,0-918,516, the task sat at 1622,939).
            // Not applied: then the host keeps computing coordinates from the real PiP bounds.
            Slog.d(TAG, "UpdateAndroidWindow task " + t.taskId + " (PiP) bounds="
                    + rect(info.getBounds()) + " ignored");
            return;
        }
        Integer displayId = mLogicalByUnique.get(info.getWsaDisplayId());
        if (displayId != null && displayId != t.displayId) {
            try {
                mAtm.moveRootTaskToDisplay(t.taskId, displayId);
                t.displayId = displayId;
            } catch (RemoteException e) {
                Slog.e(TAG, "moveRootTaskToDisplay failed", e);
            }
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        int newMode = windowingMode(info.getWindowMode());
        if (isFreeformOrFullscreen(t.windowingMode) && isFreeformOrFullscreen(newMode)
                && t.windowingMode != newMode) {
            wct.setWindowingMode(t.token, newMode);
            t.windowingMode = newMode;
        }
        // Buffer cropping after a resize (a frame of the old size after "maximize"/F11) is fixed by
        // platform patch a16/0017 in ViewRootImpl on all versions (01.10.2026); the old bridge workaround for
        // Android 15 — a size jump under dragResizing — itself left this cropping and was removed.
        if (info.hasBounds()) wct.setBounds(t.token, rect(info.getBounds()));
        t.hostBounds = info.hasBounds() && newMode == WindowConfiguration.WINDOWING_MODE_FREEFORM
                ? rect(info.getBounds()) : null;
        t.hostBoundsAt = SystemClock.uptimeMillis();
        if (t.windowingMode == WindowConfiguration.WINDOWING_MODE_FREEFORM
                && t.userResize != info.getUserResize()) {
            t.userResize = info.getUserResize();
            wct.setDragResizing(t.token, t.userResize);
        }
        Slog.d(TAG, "UpdateAndroidWindow task " + t.taskId + " bounds=" + rect(info.getBounds())
                + " mode=" + info.getWindowMode() + " userResize=" + info.getUserResize());
        // Transitions run strictly in sequence, and each waits for an app frame: with one transition per
        // host message the window kept redrawing step by step for ~14 s after the frame was released (05.10.2026,
        // lineage21, Google Play: 67 messages, ~4 transitions per second). Without a transition
        // (applyTransaction, like FluidResizeTaskPositioner in WM Shell) only the task configuration changes,
        // while the leash keeps its old position and crop, and the final transition with the same
        // bounds is dropped by Shell ("No transition roots") — the window was never redrawn.
        // So while resizing — a transition no more often than DRAG_TRANSITION_INTERVAL_MS, with the latest
        // bounds; the final ones (userResize=false) — immediately.
        if (t.userResize && newMode == WindowConfiguration.WINDOWING_MODE_FREEFORM) {
            long wait = t.dragTransitionAt + DRAG_TRANSITION_INTERVAL_MS - SystemClock.uptimeMillis();
            if (wait > 0) {
                final int id = t.taskId;
                synchronized (mPendingDrag) {
                    if (mPendingDrag.put(id, info) != null) return;
                }
                mH.postDelayed(() -> applyPendingDrag(id), wait);
                return;
            }
            t.dragTransitionAt = SystemClock.uptimeMillis();
        } else {
            synchronized (mPendingDrag) {
                mPendingDrag.remove(t.taskId);
            }
        }
        applyChange(wct, WindowManager.TRANSIT_CHANGE);
        // The host computes mouse coordinates from the bounds the guest reported in UpdateClientWindow, and
        // TaskStackListener does not report bounds changes (at Microsoft that is onTaskInfoChanged from WM
        // Shell). Without an explicit report after F11 clicks were offset by the old window corner
        // (27.09.2026: +1023,+323). During a drag the host already knows the bounds.
        if (!info.getUserResize()) {
            final int id = t.taskId;
            for (long d : BOUNDS_REPORT_DELAYS_MS) mH.postDelayed(() -> refreshTask(id), d);
        }
    }

    /**
     * A freeform task's window is moved only by the host: it does not move the Windows window after the
     * guest by itself, and computes the mouse from the bounds in UpdateClientWindow. Android, however, moves
     * the task itself when an activity is launched into it: TaskLaunchParamsModifier.adjustBoundsToAvoidConflictInDisplayArea
     * sees a "conflict" of the task with its own bounds and shifts it by 1/16 of the screen (30.09.2026,
     * aosp16-vanilla: "Files" -> picture -> SendToWindowsActivity, +160,+100; after that hover
     * missed the cursor until the window was resized). Such a move without a size change is reverted.
     * But not right after a host command: Android legitimately corrects its own bounds (maximized window
     * 0,29 -> 0,30 per the work area), and the host must be told about the correction — reverting dragged
     * the window back and forth, and after "maximize" clicks did not get through (30.09.2026).
     */
    private boolean snapBackToHostBounds(Task t, RunningTaskInfo info) {
        if (t.hostBounds == null || t.userResize || t.token == null
                || t.windowingMode != WindowConfiguration.WINDOWING_MODE_FREEFORM
                || SystemClock.uptimeMillis() - t.hostBoundsAt < HOST_BOUNDS_SETTLE_MS) {
            return false;
        }
        Rect b = info.getConfiguration().windowConfiguration.getBounds();
        if (b.equals(t.hostBounds) || b.width() != t.hostBounds.width()
                || b.height() != t.hostBounds.height()) {
            return false;
        }
        Slog.i(TAG, "task " + t.taskId + " moved by Android to " + b + ", back to " + t.hostBounds);
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(t.token, new Rect(t.hostBounds));
        applyChange(wct, WindowManager.TRANSIT_CHANGE);
        return true;
    }

    /**
     * Apply a WCT via a transition. With Shell Transitions (Android 14, enabled by default)
     * WM Shell applies the leash geometry of an organized task only inside a transition; a bare
     * applyTransaction() changes the task configuration, but the surface stays in the old place
     * (checked 21.09.2026: maximizing the window on the host — the app relaid out, the crop stayed old).
     */
    private void applyChange(WindowContainerTransaction wct, int transitType) {
        try {
            mOrganizer.startNewTransition(transitType, wct);
        } catch (RuntimeException e) {
            Slog.w(TAG, "startNewTransition failed, falling back to applyTransaction: " + e);
            mOrganizer.applyTransaction(wct);
        }
    }

    private void restorePipWindow(int taskId) {
        Task t = mTasks.get(taskId);
        if (t == null || t.token == null) return;
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setWindowingMode(t.token, WindowConfiguration.WINDOWING_MODE_FREEFORM);
        wct.setBounds(t.token, null);
        applyChange(wct, WindowManager.TRANSIT_CHANGE);
    }

    private void reorderTask(int taskId, boolean toTop) {
        Task t = mTasks.get(taskId);
        if (t == null || t.token == null) {
            Slog.w(TAG, "reorder: no task " + taskId);
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reorder(t.token, toTop);
        applyChange(wct, toTop ? WindowManager.TRANSIT_TO_FRONT : WindowManager.TRANSIT_TO_BACK);
    }

    // ---------------------------------------------------------------- helpers

    private boolean isCandidate(RunningTaskInfo info) {
        // The bridge's own activities (OpenInWindowsActivity etc.) are Theme.NoDisplay trampolines: a Windows
        // window for them flashes for 16 ms (seen 27.09.2026 in the host ETW).
        return info.baseActivity != null
                && !mContext.getPackageName().equals(info.baseActivity.getPackageName())
                && info.topActivityType == WindowConfiguration.ACTIVITY_TYPE_STANDARD
                && !mUnsupportedDisplays.contains(info.displayId)
                && mUniqueByLogical.containsKey(info.displayId);
    }

    private Shell.WindowInfo.Builder windowInfo(RunningTaskInfo info, boolean firstAppeared) {
        Rect b = info.getConfiguration().windowConfiguration.getBounds();
        Shell.WindowInfo.Builder w = Shell.WindowInfo.newBuilder()
                .setTaskId(info.taskId)
                .setWsaDisplayId(mUniqueByLogical.get(info.displayId))
                .setBounds(bounds(b))
                .setVisible(firstAppeared || info.isVisible)
                .setWindowMode(windowMode(info.getWindowingMode()));
        if (info.baseActivity != null) w.setPackageName(info.baseActivity.getPackageName());
        ActivityInfo top = info.topActivityInfo;
        if (top != null && top.windowLayout != null && info.topActivity != null
                && info.topActivity.equals(info.baseActivity)
                && top.windowLayout.minWidth > 0 && top.windowLayout.minHeight > 0) {
            w.setMinSize(Shell.Size.newBuilder()
                    .setWidth(top.windowLayout.minWidth).setHeight(top.windowLayout.minHeight));
        }
        String label = null;
        if (info.taskDescription != null) label = info.taskDescription.getLabel();
        if (TextUtils.isEmpty(label) && top != null) {
            label = String.valueOf(top.loadLabel(mContext.getPackageManager()));
        }
        if (!TextUtils.isEmpty(label)) w.setLabel(label);
        if (info.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_PINNED) {
            float ratio = 0f;
            if (info.pictureInPictureParams != null) {
                ratio = info.pictureInPictureParams.getAspectRatioFloat();
            }
            if (ratio <= 0f && b.width() > 0 && b.height() > 0) {
                ratio = (float) Math.max(b.width(), b.height()) / Math.min(b.width(), b.height());
            }
            if (ratio > 0f) w.setFixedAspectRatio(ratio);
        }
        return w;
    }

    private static Shell.Bounds.Builder bounds(Rect r) {
        return Shell.Bounds.newBuilder().setLeft(r.left).setTop(r.top)
                .setRight(r.right).setBottom(r.bottom);
    }

    private static Rect rect(Shell.Bounds b) {
        return new Rect(b.getLeft(), b.getTop(), b.getRight(), b.getBottom());
    }

    private static Shell.WindowMode windowMode(int windowingMode) {
        switch (windowingMode) {
            case WindowConfiguration.WINDOWING_MODE_PINNED: return Shell.WindowMode.PictureInPicture;
            case WindowConfiguration.WINDOWING_MODE_FULLSCREEN: return Shell.WindowMode.Fullscreen;
            default: return Shell.WindowMode.Freeform;
        }
    }

    private static int windowingMode(Shell.WindowMode mode) {
        switch (mode) {
            case PictureInPicture: return WindowConfiguration.WINDOWING_MODE_PINNED;
            case Fullscreen: return WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
            default: return WindowConfiguration.WINDOWING_MODE_FREEFORM;
        }
    }

    private static boolean isFreeformOrFullscreen(int mode) {
        return mode == WindowConfiguration.WINDOWING_MODE_FREEFORM
                || mode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
    }

    /** The host does not create a window for a display that is off — wake the screen on every new task. */
    private void wakeAndroid() {
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        pm.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_APPLICATION, TAG);
    }
}
