package org.wsalineage.armtest;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.util.Arrays;

/**
 * ARM translation test app (item 28 of the #700 checklist): the native library exists only
 * for arm64-v8a; on x86_64 WSA it is executed by libhoudini. The result goes to logcat (tag ArmTest).
 *
 *   am start -S -n org.wsalineage.armtest/.MainActivity
 */
public final class MainActivity extends Activity {
    private static final String TAG = "ArmTest";

    private static native String nativeProbe(int n);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "device ABIs " + Arrays.toString(Build.SUPPORTED_ABIS) + ", nativeLibraryDir "
                + getApplicationInfo().nativeLibraryDir);
        try {
            System.loadLibrary("armtest");
            long t0 = System.nanoTime();
            String r = nativeProbe(10_000_000);
            Log.i(TAG, "native: " + r + ", " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        } catch (Throwable e) {
            Log.e(TAG, "native failed", e);
        }
        finish();
    }
}
