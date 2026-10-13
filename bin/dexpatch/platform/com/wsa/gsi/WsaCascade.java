package com.wsa.gsi;

import android.graphics.Rect;
import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * Path A (ready GSI) counterpart of docs/patches/0027 and 0028 (a window launched from Windows cascades 75 dp from the topmost
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

    /**
     * Counterpart of docs/patches/0028: the call cascadeBounds(sourceBounds, taskDisplayArea, bounds) in the source
     * activity branch of calculate() comes here (InvokeToStaticPatch, this first). A source that has not drawn a frame
     * yet is a trampoline (Files' LauncherActivity starts FilesActivity in a new task and finishes): the new task takes
     * its place instead of cascading 75 dp further. The source activity itself is not passed to cascadeBounds, so its
     * task is found by these bounds and the top activity's firstWindowDrawn is read.
     */
    public static void cascadeBounds(Object self, Rect sourceBounds, Object displayArea, Rect bounds) {
        try {
            if (undrawnTaskAt(displayArea, sourceBounds)) {
                bounds.set(sourceBounds);
                return;
            }
        } catch (Throwable t) {
            android.util.Log.w("WsaCascade", "trampoline check failed", t);
        }
        try {
            find(self.getClass(), "cascadeBounds", 3).invoke(self, sourceBounds, displayArea, bounds);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("TaskLaunchParamsModifier.cascadeBounds", e);
        }
    }

    /**
     * Second half of 0028: in adjustBoundsToAvoidConflictInDisplayArea() the call
     * displayArea.forAllRootTasks(consumer, traverseTopToBottom) comes here; tasks whose top activity has not drawn a
     * frame yet (a trampoline on its way out, a window still being created) are not passed to the consumer, so they
     * are not windows to keep clear of — otherwise the window that took a trampoline's place is pushed 1/16 of the
     * screen away from it.
     */
    public static void forAllRootTasks(Object displayArea, Object consumer, boolean traverseTopToBottom) {
        forAllDrawn("forAllRootTasks", displayArea, consumer, traverseTopToBottom);
    }

    /** The same for Android 17, where the method walks leaf tasks: displayArea.forAllLeafTasks(consumer, false). */
    public static void forAllLeafTasks(Object displayArea, Object consumer, boolean traverseTopToBottom) {
        forAllDrawn("forAllLeafTasks", displayArea, consumer, traverseTopToBottom);
    }

    @SuppressWarnings("unchecked")
    private static void forAllDrawn(String name, Object displayArea, Object consumer, boolean traverseTopToBottom) {
        java.util.function.Consumer<Object> c = (java.util.function.Consumer<Object>) consumer;
        java.util.function.Consumer<Object> drawnOnly = t -> {
            try {
                if (undrawnTop(t)) return;
            } catch (Throwable e) {
                android.util.Log.w("WsaCascade", "trampoline check failed", e);
            }
            c.accept(t);
        };
        try {
            Method m = null;
            for (Class<?> k = displayArea.getClass(); k != null && m == null; k = k.getSuperclass()) {
                for (Method d : k.getDeclaredMethods()) {
                    Class<?>[] p = d.getParameterTypes();
                    if (d.getName().equals(name) && p.length == 2
                            && p[0] == java.util.function.Consumer.class && p[1] == boolean.class) m = d;
                }
            }
            if (m == null) throw new NoSuchMethodException(name + "(Consumer, boolean)");
            m.setAccessible(true);
            m.invoke(displayArea, drawnOnly, traverseTopToBottom);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("TaskDisplayArea." + name, e);
        }
    }

    // Has the task's top activity not drawn its first window yet?
    private static boolean undrawnTop(Object task) throws ReflectiveOperationException {
        Object top = find(task.getClass(), "getTopMostActivity", 0).invoke(task);
        return top != null && !field(top.getClass(), "firstWindowDrawn").getBoolean(top);
    }

    // Is there a freeform task with exactly these bounds whose top activity has not drawn its first window?
    private static boolean undrawnTaskAt(Object displayArea, Rect at) throws ReflectiveOperationException {
        Predicate<Object> match = t -> {
            try {
                if (!(Boolean) find(t.getClass(), "inFreeformWindowingMode", 0).invoke(t)) return false;
                if (!at.equals(find(t.getClass(), "getBounds", 0).invoke(t))) return false;
                return undrawnTop(t);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        };
        return rootTask(displayArea, match) != null;
    }

    // displayArea.getRootTask(t -> t != own && t.inFreeformWindowingMode() && t.isVisible()), topmost first.
    private static Object topFreeformTask(Object displayArea, Object own) throws ReflectiveOperationException {
        Predicate<Object> freeformVisible = t -> {
            if (t == own) return false;
            try {
                return (Boolean) find(t.getClass(), "inFreeformWindowingMode", 0).invoke(t)
                        && (Boolean) find(t.getClass(), "isVisible", 0).invoke(t);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        };
        return rootTask(displayArea, freeformVisible);
    }

    // displayArea.getRootTask(predicate): the topmost root task matching it.
    private static Object rootTask(Object displayArea, Predicate<Object> predicate) throws ReflectiveOperationException {
        Method getRootTask = null;
        for (Class<?> c = displayArea.getClass(); c != null && getRootTask == null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("getRootTask") && p.length == 1 && p[0] == Predicate.class) getRootTask = m;
            }
        }
        if (getRootTask == null) throw new NoSuchMethodException("getRootTask(Predicate)");
        getRootTask.setAccessible(true);
        return getRootTask.invoke(displayArea, predicate);
    }

    // A declared field of the class or a superclass, made accessible.
    private static java.lang.reflect.Field field(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // next superclass
            }
        }
        throw new NoSuchFieldException(cls.getName() + "." + name);
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
