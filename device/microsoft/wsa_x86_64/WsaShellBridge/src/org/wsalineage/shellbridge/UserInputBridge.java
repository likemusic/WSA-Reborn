/*
 * "UserInput" channel: mouse, touch and keyboard from the Windows window into the guest app.
 *
 * In Microsoft's image the handler lives in UserApp.apk (not transplanted). The protocol is
 * WsaShellBridge/proto/userinput.proto. The host sends coordinates in guest display px, the guest
 * builds MotionEvent/KeyEvent and injects them via InputManager (uid system has
 * INJECT_EVENTS); before a press the window's task is brought to front. The reply to the host is empty.
 */
package org.wsalineage.shellbridge;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;
import org.wsalineage.shellbridge.proto.UserInput;
import org.wsalineage.shellbridge.proto.UserInput.KeyboardMessage;
import org.wsalineage.shellbridge.proto.UserInput.ModifierKeys;
import org.wsalineage.shellbridge.proto.UserInput.MouseMessage;
import org.wsalineage.shellbridge.proto.UserInput.TouchMessage;
import org.wsalineage.shellbridge.proto.UserInput.UserInputMessage;

import java.util.HashMap;
import java.util.Map;

public final class UserInputBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge.Input";
    private static final String CHANNEL = "UserInput";
    private static final long RECONNECT_MS = 2_000;
    private static final int UNIQUE_ID_PREFIX_LEN = "local:".length();
    private static final int MOUSE_POINTER_ID = 0;
    private static final int VIRTUAL_DEVICE_ID = 0;
    private static final int VK_LWIN = 0x5B;
    private static final int VK_RWIN = 0x5C;

    private final Context mContext;
    private final Handler mH;
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);
    private final ActivityManager mAm;
    private final InputManager mIm;
    private final Map<Long, Integer> mLogicalByUnique = new HashMap<>();
    private long mPointerDownTime;
    private boolean mPenButtonDown;

    public UserInputBridge(Context context, Handler handler) {
        mContext = context;
        mH = handler;
        mAm = context.getSystemService(ActivityManager.class);
        mIm = context.getSystemService(InputManager.class);
    }

    public void start() {
        mH.post(() -> {
            DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            for (Display d : dm.getDisplays()) trackDisplay(d.getDisplayId());
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) { trackDisplay(displayId); }
                @Override public void onDisplayChanged(int displayId) { }
                @Override public void onDisplayRemoved(int displayId) {
                    mLogicalByUnique.values().removeIf(id -> id == displayId);
                }
            }, mH);
            connect();
        });
    }

    private void trackDisplay(int displayId) {
        Display d = mContext.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (d == null || d.getType() != Display.TYPE_INTERNAL) return;
        // wsaDisplayId is the display address port (see ShellBridge.displayPort: on Android 17 uniqueId comes from
        // EDID, and the mouse with wsaDisplayId 100 was dropped as "unknown", 03.10.2026).
        DisplayInfo info = DisplayManagerGlobal.getInstance().getDisplayInfo(displayId);
        Long port = info != null ? ShellBridge.displayPort(info) : null;
        if (port != null) {
            mLogicalByUnique.put(port, displayId);
            return;
        }
        try {
            long unique = Long.parseLong(d.getUniqueId().substring(UNIQUE_ID_PREFIX_LEN));
            mLogicalByUnique.put(unique, displayId);
        } catch (RuntimeException e) {
            Slog.w(TAG, "display " + displayId + ": cannot parse uniqueId " + d.getUniqueId());
        }
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
        mH.postDelayed(this::connect, RECONNECT_MS);
    }

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        UserInputMessage msg;
        try {
            msg = UserInputMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad UserInputMessage: " + e,
                    ResultCode.InvalidProtocolFormat);
        }
        // Input is handled right on the receive thread: event order matters and injection is fast.
        switch (msg.getMessageType()) {
            case MouseMessage: handleMouse(msg.getMouseMessage()); break;
            case TouchMessage: handleTouch(msg.getTouchMessage()); break;
            case KeyboardMessage: handleKeyboard(msg.getKeyboardMessage()); break;
            default:
                throw new ApiChannel.ApiException("unexpected message type " + msg.getMessageType(),
                        ResultCode.InvalidProtocolFormat);
        }
        return null;  // reply without payload, as in the original implementation
    }

    // ---------------------------------------------------------------- mouse

    private void handleMouse(MouseMessage m) throws ApiChannel.ApiException {
        Integer displayId = mLogicalByUnique.get(m.getDisplayId());
        if (displayId == null) {
            Slog.w(TAG, "mouse: unknown wsaDisplayId " + m.getDisplayId());
            return;
        }
        // As Microsoft does: wait out long-press before releasing the right button, so a right click
        // turns into a long press for apps without mouse support.
        if (m.getMouseAction() == MouseMessage.MouseAction.Up
                && m.getActionButton() == MouseMessage.ActionButton.Right) {
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout());
        }
        int source = m.getEmulateTouch() ? InputDevice.SOURCE_TOUCHSCREEN : InputDevice.SOURCE_MOUSE;
        int action = mouseAction(m.getMouseAction());
        long eventTime = SystemClock.uptimeMillis();
        long downTime = pointerDownTime(action, eventTime);
        int metaState = metaState(m.getModifierKeys());
        int buttonState = 0;
        if (m.getButtonState().getLeftDown()) buttonState |= MotionEvent.BUTTON_PRIMARY;
        if (m.getButtonState().getRightDown()) buttonState |= MotionEvent.BUTTON_SECONDARY;
        if (m.getButtonState().getMiddleDown()) buttonState |= MotionEvent.BUTTON_TERTIARY;

        MotionEvent.PointerProperties[] props = MotionEvent.PointerProperties.createArray(1);
        MotionEvent.PointerCoords[] coords = MotionEvent.PointerCoords.createArray(1);
        props[0].id = MOUSE_POINTER_ID;
        props[0].toolType = m.getEmulateTouch()
                ? MotionEvent.TOOL_TYPE_FINGER : MotionEvent.TOOL_TYPE_MOUSE;
        coords[0].x = m.getXPosition();
        coords[0].y = m.getYPosition();
        coords[0].pressure = m.getButtonState().getLeftDown() ? 1f : 0f;
        if (m.hasVerticalScroll()) coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, m.getVerticalScroll());
        if (m.hasHorizontalScroll()) coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, m.getHorizontalScroll());

        MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, 1, props, coords, metaState,
                buttonState, 1f, 1f, VIRTUAL_DEVICE_ID, 0, source, displayId, 0);
        int taskId = m.getTargetTaskId();
        if (m.getMouseAction() == MouseMessage.MouseAction.Down
                || m.getMouseAction() == MouseMessage.MouseAction.Up) {
            Slog.d(TAG, "mouse " + m.getMouseAction() + " at " + m.getXPosition() + ","
                    + m.getYPosition() + " task " + taskId + " display " + displayId);
        }
        switch (m.getMouseAction()) {
            case Down: {
                MotionEvent press = ev.copy();
                press.setAction(MotionEvent.ACTION_BUTTON_PRESS);
                press.setActionButton(actionButton(m.getActionButton()));
                injectMotion(ev, taskId);
                injectMotion(press, taskId);
                break;
            }
            case Up: {
                MotionEvent release = ev.copy();
                release.setAction(MotionEvent.ACTION_BUTTON_RELEASE);
                release.setActionButton(actionButton(m.getActionButton()));
                injectMotion(release, taskId);
                injectMotion(ev, taskId);
                break;
            }
            default:
                injectMotion(ev, taskId);
        }
    }

    private static int mouseAction(MouseMessage.MouseAction a) throws ApiChannel.ApiException {
        switch (a) {
            case Down: return MotionEvent.ACTION_DOWN;
            case Up: return MotionEvent.ACTION_UP;
            case DragMove: return MotionEvent.ACTION_MOVE;
            case HoverMove: return MotionEvent.ACTION_HOVER_MOVE;
            case Scroll: return MotionEvent.ACTION_SCROLL;
        }
        throw new ApiChannel.ApiException("unexpected mouse action " + a, ResultCode.InvalidProtocolFormat);
    }

    private static int actionButton(MouseMessage.ActionButton b) {
        switch (b) {
            case Left: return MotionEvent.BUTTON_PRIMARY;
            case Right: return MotionEvent.BUTTON_SECONDARY;
            case Middle: return MotionEvent.BUTTON_TERTIARY;
            default: return 0;
        }
    }

    // ---------------------------------------------------------------- touch

    private void handleTouch(TouchMessage t) throws ApiChannel.ApiException {
        Integer displayId = mLogicalByUnique.get(t.getDisplayId());
        if (displayId == null) return;
        int action = touchAction(t.getTouchAction(), t.getActionPointerIndex());
        long eventTime = SystemClock.uptimeMillis();
        long downTime = pointerDownTime(action, eventTime);
        int n = t.getTouchPointersCount();
        MotionEvent.PointerProperties[] props = MotionEvent.PointerProperties.createArray(n);
        MotionEvent.PointerCoords[] coords = MotionEvent.PointerCoords.createArray(n);
        boolean penButtonDown = false;
        for (int i = 0; i < n; i++) {
            TouchMessage.TouchPointer p = t.getTouchPointers(i);
            boolean up = pointerUp(t, i);
            props[i].id = p.getPointerId();
            props[i].toolType = p.hasToolType() ? toolType(p.getToolType()) : MotionEvent.TOOL_TYPE_FINGER;
            coords[i].x = p.getXPosition();
            coords[i].y = p.getYPosition();
            coords[i].pressure = p.hasPressure() ? p.getPressure() : (up ? 0f : 1f);
            if (p.hasPenButtonDown()) penButtonDown = p.getPenButtonDown() && !up;
            if (p.hasTiltRadians()) coords[i].setAxisValue(MotionEvent.AXIS_TILT, p.getTiltRadians());
            if (p.hasOrientationRadians()) coords[i].setAxisValue(MotionEvent.AXIS_ORIENTATION, p.getOrientationRadians());
        }
        int buttonState = penButtonDown ? MotionEvent.BUTTON_STYLUS_PRIMARY : 0;
        int flags = t.getGeneratedGesture() ? MotionEvent.FLAG_IS_GENERATED_GESTURE : 0;
        MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, n, props, coords, 0,
                buttonState, 1f, 1f, VIRTUAL_DEVICE_ID, 0, InputDevice.SOURCE_TOUCHSCREEN,
                displayId, flags);
        int taskId = t.getTargetTaskId();
        if (penButtonDown && !mPenButtonDown) {
            mPenButtonDown = true;
            MotionEvent press = ev.copy();
            press.setAction(MotionEvent.ACTION_BUTTON_PRESS);
            press.setActionButton(MotionEvent.BUTTON_STYLUS_PRIMARY);
            injectMotion(ev, taskId);
            injectMotion(press, taskId);
        } else if (!penButtonDown && mPenButtonDown) {
            mPenButtonDown = false;
            MotionEvent release = ev.copy();
            release.setAction(MotionEvent.ACTION_BUTTON_RELEASE);
            release.setActionButton(MotionEvent.BUTTON_STYLUS_PRIMARY);
            injectMotion(release, taskId);
            injectMotion(ev, taskId);
        } else {
            injectMotion(ev, taskId);
        }
    }

    private static boolean pointerUp(TouchMessage t, int index) {
        TouchMessage.TouchAction a = t.getTouchAction();
        return a == TouchMessage.TouchAction.FinalUp || a == TouchMessage.TouchAction.Cancel
                || (a == TouchMessage.TouchAction.IntermediateUp && index == t.getActionPointerIndex());
    }

    private static int touchAction(TouchMessage.TouchAction a, int pointerIndex)
            throws ApiChannel.ApiException {
        switch (a) {
            case InitialDown: return MotionEvent.ACTION_DOWN;
            case FinalUp: return MotionEvent.ACTION_UP;
            case IntermediateDown:
                return (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT) | MotionEvent.ACTION_POINTER_DOWN;
            case IntermediateUp:
                return (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT) | MotionEvent.ACTION_POINTER_UP;
            case Move: return MotionEvent.ACTION_MOVE;
            case Cancel: return MotionEvent.ACTION_CANCEL;
        }
        throw new ApiChannel.ApiException("unexpected touch action " + a, ResultCode.InvalidProtocolFormat);
    }

    private static int toolType(TouchMessage.TouchPointer.ToolType t) {
        switch (t) {
            case Pen: return MotionEvent.TOOL_TYPE_STYLUS;
            case Eraser: return MotionEvent.TOOL_TYPE_ERASER;
            default: return MotionEvent.TOOL_TYPE_FINGER;
        }
    }

    // ---------------------------------------------------------------- keyboard

    private void handleKeyboard(KeyboardMessage k) {
        // The Win key (VK_LWIN/VK_RWIN) is for Windows shortcuts (Win+Shift+S, Win+., Win+V…); the host
        // sends it to the focused window. In Android a lone META press opens the launcher's all-apps
        // list (PhoneWindowManager), and it popped up over the screen selection in
        // Windows (27.09.2026, Win+Shift+S). The key itself is not passed on; "Win held" in metaState
        // remains, so the app still sees Meta+letter combinations.
        if (k.getVirtualKeyCode() == VK_LWIN || k.getVirtualKeyCode() == VK_RWIN) return;
        int keyCode = VK_TO_KEYCODE.get(k.getVirtualKeyCode(), -1);
        if (keyCode < 0) {
            Slog.w(TAG, "unmapped virtual key " + k.getVirtualKeyCode());
            return;
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(now, now, k.getKeyDown() ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, k.getRepeatCount(), metaState(k.getModifierKeys()),
                KeyCharacterMap.VIRTUAL_KEYBOARD, k.getScanCode(), 0, InputDevice.SOURCE_KEYBOARD);
        inject(ev, k.getTargetTaskId());
    }

    // ---------------------------------------------------------------- common

    private long pointerDownTime(int action, long eventTime) {
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                mPointerDownTime = eventTime;
                return eventTime;
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_SCROLL:
                return eventTime;
            default:
                return mPointerDownTime;
        }
    }

    private static int metaState(ModifierKeys m) {
        int meta = 0;
        if (m.getCtrlDown()) meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        if (m.getShiftDown()) meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        if (m.getAltDown()) meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        if (m.getCapsLockOn()) meta |= KeyEvent.META_CAPS_LOCK_ON;
        if (m.getNumLockOn()) meta |= KeyEvent.META_NUM_LOCK_ON;
        if (m.getScrollLockOn()) meta |= KeyEvent.META_SCROLL_LOCK_ON;
        if (m.getStartDown()) meta |= KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
        return meta;
    }

    private void injectMotion(MotionEvent ev, int taskId) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && taskId != -1) {
            mAm.moveTaskToFront(taskId, 0);
        }
        inject(ev, taskId);
    }

    private void inject(InputEvent ev, int taskId) {
        try {
            if (!mIm.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)) {
                Slog.w(TAG, "injection failed, task " + taskId + ": " + ev);
            }
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "injection failed, task " + taskId, e);
        }
    }

    /** Windows VK_* → Android KEYCODE_* (the same table as Microsoft's handler uses). */
    private static final SparseIntArray VK_TO_KEYCODE = new SparseIntArray();
    static {
        int[][] map = {
            {3, 121}, {8, 67}, {9, 61}, {12, 28}, {13, 66}, {16, 59}, {17, 113}, {18, 57}, {19, 121},
            {20, 115}, {21, 218}, {27, 111}, {32, 62}, {33, 92}, {34, 93}, {35, 123}, {36, 122},
            {37, 21}, {38, 19}, {39, 22}, {40, 20}, {41, 109}, {44, 120}, {46, 112}, {47, 259},
            {48, 7}, {49, 8}, {50, 9}, {51, 10}, {52, 11}, {53, 12}, {54, 13}, {55, 14}, {56, 15},
            {57, 16}, {65, 29}, {66, 30}, {67, 31}, {68, 32}, {69, 33}, {70, 34}, {71, 35}, {72, 36},
            {73, 37}, {74, 38}, {75, 39}, {76, 40}, {77, 41}, {78, 42}, {79, 43}, {80, 44}, {81, 45},
            {82, 46}, {83, 47}, {84, 48}, {85, 49}, {86, 50}, {87, 51}, {88, 52}, {89, 53}, {90, 54},
            {91, 117}, {92, 118}, {96, 144}, {97, 145}, {98, 146}, {99, 147}, {100, 148}, {101, 149},
            {102, 150}, {103, 151}, {104, 152}, {105, 153}, {106, 155}, {107, 157}, {109, 156},
            {110, 158}, {111, 154}, {112, 131}, {113, 132}, {114, 133}, {115, 134}, {116, 135},
            {117, 136}, {118, 137}, {119, 138}, {120, 139}, {121, 140}, {122, 141}, {123, 142},
            {144, 143}, {145, 116}, {160, 59}, {161, 60}, {162, 113}, {163, 114}, {164, 57},
            {165, 58}, {166, 4}, {168, 285}, {173, 164}, {174, 25}, {175, 24}, {176, 87}, {177, 88},
            {178, 86}, {179, 85}, {180, 65}, {186, 74}, {187, 70}, {188, 55}, {189, 69}, {190, 56},
            {191, 76}, {192, 68}, {219, 71}, {220, 73}, {221, 72}, {222, 75},
        };
        for (int[] e : map) VK_TO_KEYCODE.put(e[0], e[1]);
    }
}
