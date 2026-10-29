package org.wsalineage.decodetest;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Decodes extra "file" (default /data/local/tmp/test.mp4, readable by all) with extra "codec" (a codec name; default the
 * system's choice for the track's type) and logs "RESULT codec=... frames=... ms=..." or "FAILED ...". The output goes to
 * an offscreen SurfaceTexture (Latte decodes into Android native buffers only); the frames are dropped, not rendered.
 * With extra "show" (boolean) the frames go to a full-window SurfaceView at their presentation times instead, and
 * "SHOWING frame=..." is logged at frame 150, for a screenshot of the decoded picture (29.10.2026). With extra "list"
 * (boolean) it logs every decoder of MediaCodecList instead ("CODEC ...").
 */
public class MainActivity extends Activity {
    private static final String TAG = "DecodeTest";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String file = getIntent().getStringExtra("file");
        String codec = getIntent().getStringExtra("codec");
        String path = file != null ? file : "/data/local/tmp/test.mp4";
        if (getIntent().getBooleanExtra("list", false)) {
            // Every decoder of MediaCodecList.ALL_CODECS: "CODEC <name> <types> hw=<bool> sw=<bool> alias=<bool>".
            for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos())
                if (!i.isEncoder())
                    Log.i(TAG, "CODEC " + i.getName() + " " + String.join(",", i.getSupportedTypes()) + " hw=" + i.isHardwareAccelerated()
                            + " sw=" + i.isSoftwareOnly() + " alias=" + i.isAlias());
            Log.i(TAG, "RESULT list");
            finish();
            return;
        }
        if (!getIntent().getBooleanExtra("show", false)) {
            start(path, codec, null);
            return;
        }
        SurfaceView view = new SurfaceView(this);
        setContentView(view);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { start(path, codec, h.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {}
        });
    }

    private void start(String path, String codec, Surface shown) {
        new Thread(() -> {
            try {
                decode(path, codec, shown);
            } catch (Exception e) {
                Log.e(TAG, "FAILED " + e, e);
            }
            finish();
        }).start();
    }

    private void decode(String path, String name, Surface shown) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(path);
        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            if (f.getString(MediaFormat.KEY_MIME).startsWith("video/")) { track = i; format = f; break; }
        }
        if (track < 0) throw new IllegalArgumentException("no video track in " + path);
        ex.selectTrack(track);
        String mime = format.getString(MediaFormat.KEY_MIME);
        MediaCodec c = name != null ? MediaCodec.createByCodecName(name) : MediaCodec.createDecoderByType(mime);
        Log.i(TAG, "decoding " + path + " (" + mime + ", " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" + format.getInteger(MediaFormat.KEY_HEIGHT) + ") with " + c.getName());
        SurfaceTexture st = shown == null ? new SurfaceTexture(0) : null;
        Surface surface = shown == null ? new Surface(st) : shown;
        c.configure(format, surface, null, 0);
        c.start();
        long start = SystemClock.elapsedRealtime();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        int frames = 0, inputs = 0, firstAt = -1;
        while (true) {
            if (!inputDone) {
                int in = c.dequeueInputBuffer(10000);
                if (in >= 0) {
                    int n = ex.readSampleData(c.getInputBuffer(in), 0);
                    if (n < 0) { c.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                    else { c.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0); ex.advance(); inputs++; }
                }
            }
            int out = c.dequeueOutputBuffer(info, 10000);
            if (out >= 0) {
                if (info.size > 0 || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) frames++;
                if (frames == 1 && firstAt < 0) firstAt = inputs;
                if (shown == null) {
                    c.releaseOutputBuffer(out, false);  // nobody consumes the SurfaceTexture: rendering would fill its queue
                } else {
                    long wait = info.presentationTimeUs / 1000 - (SystemClock.elapsedRealtime() - start);
                    if (wait > 0) Thread.sleep(wait);
                    c.releaseOutputBuffer(out, true);
                    if (frames == 150) Log.i(TAG, "SHOWING frame=150");
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
            if (SystemClock.elapsedRealtime() - start > 60000) throw new IllegalStateException("timeout after " + frames + " frames");
        }
        long ms = SystemClock.elapsedRealtime() - start;
        Log.i(TAG, "RESULT codec=" + c.getName() + " frames=" + frames + " ms=" + ms + " inputs=" + inputs + " first_output_after=" + firstAt);
        c.stop();
        c.release();
        if (st != null) {
            surface.release();
            st.release();
        }
        ex.release();
    }
}
