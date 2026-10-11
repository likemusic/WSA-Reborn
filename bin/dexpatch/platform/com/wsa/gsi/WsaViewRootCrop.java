package com.wsa.gsi;

import android.view.SurfaceControl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Path A (ready GSI) counterpart of docs/patches.md `a16/0017`, called from android.view.ViewRootImpl.relayoutWindow
 * after bin/dexpatch/ViewRootCropPatch.java has redirected two calls there (same-size replacements: the method
 * has no spare registers for inserted code). Goes into framework.jar as an extra classesN.dex.
 *
 * While the window is being drag-resized, ViewRootImpl crops its surface to the window frame, but only puts the
 * crop into mTransaction, which is applied at some random later apply(): then the crop of the last drag hits
 * a later surface change without dragging (F11 after "restore") and cuts the 2560x1600 frame down to the old
 * size. So: apply the crop at once, and when the drag is over, remove it completely.
 */
public final class WsaViewRootCrop {
    private static final WeakHashMap<SurfaceControl, Boolean> CROPPED = new WeakHashMap<>();
    private static Field sTransaction;
    private static volatile Field sSurfaceControl; // set last: publishes the other two
    private static Method sIsDrawingEnabled;

    private WsaViewRootCrop() {}

    /** Replaces {@code mTransaction.setWindowCrop(mSurfaceControl, w, h)} in the drag-resizing branch. */
    public static SurfaceControl.Transaction dragCrop(SurfaceControl.Transaction t, SurfaceControl sc, int w, int h) {
        t.setWindowCrop(sc, w, h).apply();
        synchronized (CROPPED) {
            CROPPED.put(sc, Boolean.TRUE);
        }
        return t;
    }

    /**
     * Replaces {@code HardwareRenderer.isDrawingEnabled()} at the start of the "not drag-resizing" branch: removes
     * the drag crop if this window had one (an invalid rectangle, not the surface size — the surface is still
     * the old one, and a crop by its size would survive F11), then returns what the original call returned.
     */
    public static boolean isDrawingEnabledAfterDrag(Object viewRoot) {
        try {
            if (sSurfaceControl == null) {
                Class<?> c = viewRoot.getClass();
                Field sc = c.getDeclaredField("mSurfaceControl"), tr = c.getDeclaredField("mTransaction");
                sc.setAccessible(true);
                tr.setAccessible(true);
                sIsDrawingEnabled = Class.forName("android.graphics.HardwareRenderer").getMethod("isDrawingEnabled");
                sTransaction = tr;
                sSurfaceControl = sc;
            }
            SurfaceControl sc = (SurfaceControl) sSurfaceControl.get(viewRoot);
            boolean wasCropped;
            synchronized (CROPPED) {
                wasCropped = CROPPED.remove(sc) != null;
            }
            if (wasCropped) {
                // (w, h) = (-1, -1) is Rect(0, 0, -1, -1): an invalid rectangle, i.e. no crop at all.
                ((SurfaceControl.Transaction) sTransaction.get(viewRoot)).setWindowCrop(sc, -1, -1).apply();
            }
            return (Boolean) sIsDrawingEnabled.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WSA a16/0017 helper", e);
        }
    }
}
