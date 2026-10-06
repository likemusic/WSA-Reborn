package org.wsalineage.gltest;

import android.app.Activity;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Bundle;
import android.util.Log;

/**
 * OpenGL ES test app (items 15 and 16 of the #700 checklist): creates a GLES context
 * of the highest available version (3 → 2) on a pbuffer and writes to logcat (tag GlTest) the vendor,
 * renderer and version. With ANGLE for the package (settings angle_gl_driver_selection_*) the renderer is "ANGLE (...)".
 *
 *   am start -S -n org.wsalineage.gltest/.MainActivity
 */
public final class MainActivity extends Activity {
    private static final String TAG = "GlTest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            probe();
        } catch (Throwable e) {
            Log.e(TAG, "probe failed", e);
        }
        finish();
    }

    private static void probe() {
        EGLDisplay dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) {
            Log.e(TAG, "eglInitialize: 0x" + Integer.toHexString(EGL14.eglGetError()));
            return;
        }
        Log.i(TAG, "EGL " + ver[0] + "." + ver[1] + ", vendor " + EGL14.eglQueryString(dpy, EGL14.EGL_VENDOR)
                + ", version " + EGL14.eglQueryString(dpy, EGL14.EGL_VERSION));
        for (int es : new int[] {3, 2}) {
            int renderable = es == 3 ? 0x40 /* EGL_OPENGL_ES3_BIT_KHR */ : EGL14.EGL_OPENGL_ES2_BIT;
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, renderable, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE};
            if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfg, 0, 1, n, 0) || n[0] == 0) {
                Log.w(TAG, "no config for ES" + es);
                continue;
            }
            EGLContext ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, es, EGL14.EGL_NONE}, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT) {
                Log.w(TAG, "no context for ES" + es + ": 0x" + Integer.toHexString(EGL14.eglGetError()));
                continue;
            }
            EGLSurface surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0],
                    new int[] {EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
            String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            Log.i(TAG, "ES" + es + " context: GL_VENDOR=" + GLES20.glGetString(GLES20.GL_VENDOR)
                    + ", GL_RENDERER=" + GLES20.glGetString(GLES20.GL_RENDERER)
                    + ", GL_VERSION=" + GLES20.glGetString(GLES20.GL_VERSION)
                    + ", GLSL=" + GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
                    + ", extensions=" + (ext == null ? 0 : ext.split(" ").length));
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(dpy, surf);
            EGL14.eglDestroyContext(dpy, ctx);
            break;
        }
        EGL14.eglTerminate(dpy);
    }
}
