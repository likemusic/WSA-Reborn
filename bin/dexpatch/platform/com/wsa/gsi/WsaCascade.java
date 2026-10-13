package com.wsa.gsi;

import android.graphics.Rect;
import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * Path A (ready GSI) counterpart of docs/patches/0027 (a window launched from Windows cascades 75 dp from the topmost
 * visible freeform window, as on Microsoft's WSA). bin/dexpatch/InvokeToStaticPatch.java turns the call
 * TaskLaunchParamsModifier.getTaskBounds(root, taskDisplayArea, layout, resolvedMode, hasInitialBounds, bounds) in
 * calculate() into a call of getTaskBounds below with the same registers (this first). Goes into services.jar as an
 * extra classesN.dex; everything is reflective, the window manager classes are not in the SDK (system_server has no
 * hidden-API restrictions on its own classes).
 *
 * The task being launched is excluded as in the source patch ("t != task"): here it is root.getTask(). Without that
 * calculate(), which runs again once the task exists and is visible, cascaded the window from itself — a second
 * window landed 2x75 dp off the first (13.10.2026).
 */
public final class WsaCascade {
    private WsaCascade() {}

    private static final int WINDOWING_MODE_FREEFORM = 5;

    public static void getTaskBounds(Object self, Object root, Object displayArea, Object layout, int resolvedMode,
            boolean hasInitialBounds, Rect bounds) {
        try {
            if (resolvedMode == WINDOWING_MODE_FREEFORM && bounds.isEmpty() && !hasInitialBounds) {
                Object own = root == null ? null : find(root.getClass(), "getTask", 0).invoke(root);
                Object top = topFreeformTask(displayArea, own);
                if (top != null) {
                    Rect topBounds = (Rect) find(top.getClass(), "getBounds", 0).invoke(top);
                    Method cascade = find(self.getClass(), "cascadeBounds", 3);
                    cascade.invoke(self, topBounds, displayArea, bounds);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("WsaCascade", "cascade from the top window failed", t);
            bounds.setEmpty();
        }
        try {
            find(self.getClass(), "getTaskBounds", 6).invoke(self, root, displayArea, layout, resolvedMode,
                    hasInitialBounds, bounds);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("TaskLaunchParamsModifier.getTaskBounds", e);
        }
    }

    // displayArea.getRootTask(t -> t != own && t.inFreeformWindowingMode() && t.isVisible()), topmost first.
    private static Object topFreeformTask(Object displayArea, Object own) throws ReflectiveOperationException {
        Method getRootTask = null;
        for (Class<?> c = displayArea.getClass(); c != null && getRootTask == null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("getRootTask") && p.length == 1 && p[0] == Predicate.class) getRootTask = m;
            }
        }
        if (getRootTask == null) throw new NoSuchMethodException("getRootTask(Predicate)");
        getRootTask.setAccessible(true);
        Predicate<Object> freeformVisible = t -> {
            if (t == own) return false;
            try {
                return (Boolean) find(t.getClass(), "inFreeformWindowingMode", 0).invoke(t)
                        && (Boolean) find(t.getClass(), "isVisible", 0).invoke(t);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        };
        return getRootTask.invoke(displayArea, freeformVisible);
    }

    // A declared method of the class or a superclass with this name and parameter count, made accessible.
    private static Method find(Class<?> cls, String name, int params) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == params) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(cls.getName() + "." + name);
    }
}
