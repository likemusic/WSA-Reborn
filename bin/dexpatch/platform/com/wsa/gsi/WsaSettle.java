package com.wsa.gsi;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceControl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Path A (ready GSI) counterpart of docs/patches.md `0029`: an OPEN transition whose BLAST sync times out before the app
 * process attaches (cold start over 5 s) is aborted, the freeform task becomes visible without a transition and nobody
 * positions or crops its leash — it stays at (0, 0) and input misses the window. A while after a task turns visible,
 * do what the opening would have done: a decoration relayout (onTaskChanging) and the leash position and crop.
 * Called from the start of FreeformTaskListener.onTaskAppeared/onTaskInfoChanged (bin/dexpatch/FreeformSettlePatch.java),
 * on the shell main thread. WM Shell types and the hidden TaskInfo fields — by reflection (SystemUI is a platform app).
 * Goes into SystemUI.apk as an extra classesN.dex.
 */
public final class WsaSettle {
    private static final String TAG = "FreeformTaskListener";
    private static final long SETTLE_MS = 500;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static Handler sHandler;

    private WsaSettle() {}

    public static void appeared(Object listener, Object info, Object leash) {
        try {
            if (visible(info)) schedule(listener, taskId(info));
        } catch (Throwable e) {
            Log.w(TAG, "WSA: settle", e);
        }
    }

    /** Before the listener stores the new info: the task's State still has the previous one. */
    public static void changed(Object listener, Object info) {
        try {
            Object state = state(listener, taskId(info));
            if (state != null && visible(info) && !visible(field(state, "mTaskInfo"))) schedule(listener, taskId(info));
        } catch (Throwable e) {
            Log.w(TAG, "WSA: settle", e);
        }
    }

    private static void schedule(Object listener, int taskId) {
        // Created on first use, on the shell main thread the task callbacks come on.
        if (sHandler == null) sHandler = new Handler(Looper.myLooper());
        sHandler.postDelayed(() -> {
            try {
                settle(listener, taskId);
            } catch (Throwable e) {
                Log.w(TAG, "WSA: settle task #" + taskId, e);
            }
        }, SETTLE_MS);
    }

    private static void settle(Object listener, int taskId) throws ReflectiveOperationException {
        Object state = state(listener, taskId);
        if (state == null) return;
        Object info = field(state, "mTaskInfo");
        SurfaceControl leash = (SurfaceControl) field(state, "mLeash");
        if (!visible(info) || (int) info.getClass().getMethod("getWindowingMode").invoke(info) != WINDOWING_MODE_FREEFORM) {
            return;
        }
        Object configuration = field(info, "configuration");
        Object windowConfiguration = field(configuration, "windowConfiguration");
        Rect bounds = (Rect) windowConfiguration.getClass().getMethod("getBounds").invoke(windowConfiguration);
        android.graphics.Point position = (android.graphics.Point) field(info, "positionInParent");
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        Object viewModel = field(listener, "mWindowDecorationViewModel");
        if (viewModel != null) {
            for (Method m : viewModel.getClass().getMethods()) {
                if (m.getName().equals("onTaskChanging") && m.getParameterCount() == 4) {
                    m.invoke(viewModel, info, leash, t, t);
                    break;
                }
            }
        }
        t.setPosition(leash, (float) position.x, (float) position.y)
                .setWindowCrop(leash, bounds.width(), bounds.height())
                .show(leash)
                .apply();
        Log.i(TAG, "WSA: settled visible task #" + taskId + " at " + position + " " + bounds.width() + "x"
                + bounds.height());
    }

    private static Object state(Object listener, int taskId) throws ReflectiveOperationException {
        return ((SparseArray<?>) field(listener, "mTasks")).get(taskId);
    }

    private static int taskId(Object info) throws ReflectiveOperationException {
        return (int) field(info, "taskId");
    }

    private static boolean visible(Object info) throws ReflectiveOperationException {
        return info != null && (boolean) field(info, "isVisible");
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // look in the superclass (TaskInfo for RunningTaskInfo)
            }
        }
        throw new NoSuchFieldException(name);
    }
}
