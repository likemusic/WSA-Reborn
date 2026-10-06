package org.wsalineage.cameratest;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

/**
 * Camera test app (item 29 of the #700 checklist): opens the camera via camera2, runs
 * a repeating preview request into an ImageReader (YUV_420_888) and writes to logcat (tag CameraTest)
 * the size and brightness of frames (mean, spread, min, max) — a spread well above zero and
 * a changing mean mean a real picture from the host webcam, not a solid fill. Permission: pm grant org.wsalineage.cameratest android.permission.CAMERA
 *
 *   am start -S -n org.wsalineage.cameratest/.MainActivity [-e id 1] [--ei frames 90]
 */
public final class MainActivity extends Activity {
    private static final String TAG = "CameraTest";

    private HandlerThread mThread;
    private CameraDevice mCamera;
    private ImageReader mReader;
    private int mFrames;
    private int mMax;
    private long mStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMax = getIntent().getIntExtra("frames", 10);
        mThread = new HandlerThread("camera");
        mThread.start();
        Handler h = new Handler(mThread.getLooper());
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] ids = cm.getCameraIdList();
            Log.i(TAG, "cameras " + Arrays.toString(ids));
            String id = getIntent().hasExtra("id") ? getIntent().getStringExtra("id") : ids[0];
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Size[] sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.YUV_420_888);
            Log.i(TAG, "camera " + id + " facing " + ch.get(CameraCharacteristics.LENS_FACING)
                    + ", YUV sizes " + Arrays.toString(sizes));
            Size sz = sizes[0];
            mReader = ImageReader.newInstance(sz.getWidth(), sz.getHeight(), ImageFormat.YUV_420_888, 4);
            mReader.setOnImageAvailableListener(this::onImage, h);
            mStart = System.nanoTime();
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    mCamera = c;
                    Log.i(TAG, "opened in " + (System.nanoTime() - mStart) / 1_000_000 + " ms");
                    startPreview(h);
                }
                @Override public void onDisconnected(CameraDevice c) { Log.w(TAG, "disconnected"); c.close(); }
                @Override public void onError(CameraDevice c, int e) { Log.e(TAG, "open error " + e); c.close(); }
            }, h);
        } catch (Throwable e) {
            Log.e(TAG, "camera failed", e);
            finish();
        }
    }

    private void startPreview(Handler h) {
        try {
            mCamera.createCaptureSession(Collections.singletonList(mReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            try {
                                CaptureRequest.Builder b = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(mReader.getSurface());
                                s.setRepeatingRequest(b.build(), null, h);
                            } catch (Throwable e) {
                                Log.e(TAG, "repeating request failed", e);
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) { Log.e(TAG, "configure failed"); }
                    }, h);
        } catch (Throwable e) {
            Log.e(TAG, "session failed", e);
        }
    }

    private void onImage(ImageReader r) {
        try (Image img = r.acquireLatestImage()) {
            if (img == null || mFrames >= mMax) return;
            mFrames++;
            ByteBuffer y = img.getPlanes()[0].getBuffer();
            long sum = 0, sq = 0;
            int n = 0, min = 255, max = 0;
            for (int i = 0; i < y.remaining(); i += 97, n++) {
                int v = y.get(i) & 0xff;
                sum += v;
                sq += v * v;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            double m = n == 0 ? 0 : (double) sum / n;
            long mean = Math.round(m);
            long sd = n == 0 ? 0 : Math.round(Math.sqrt(Math.max(0, (double) sq / n - m * m)));
            if (mFrames % 10 != 1 && mFrames != mMax) return;
            Log.i(TAG, "frame " + mFrames + " " + img.getWidth() + "x" + img.getHeight() + " at "
                    + (System.nanoTime() - mStart) / 1_000_000 + " ms, Y mean " + mean + " sd " + sd + " min " + min + " max " + max);
        }
        if (mFrames >= mMax) {
            Log.i(TAG, "done");
            runOnUiThread(this::finish);
        }
    }

    @Override
    protected void onDestroy() {
        if (mCamera != null) mCamera.close();
        if (mReader != null) mReader.close();
        mThread.quitSafely();
        super.onDestroy();
    }
}
