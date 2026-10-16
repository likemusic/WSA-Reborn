package org.wsalineage.mictest;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;

/**
 * Microphone test app (the own host's AudioIn channel): records N seconds with AudioRecord (48 kHz, mono, 16 bit)
 * and logs how many frames came in how long, and their peak and RMS — silence from a host without a microphone is
 * peak 0 in real time, a live microphone is not 0. Logcat tag MicTest.
 *
 *   pm grant org.wsalineage.mictest android.permission.RECORD_AUDIO
 *   am start -S -n org.wsalineage.mictest/.MainActivity [--ei seconds 3]
 *
 * An app in the background is recorded as silence by Android ("App op 27 missing, silencing record"), and keeping the
 * activity in front means a window on the host's desktop — so it also runs without any window, as the shell user (who
 * holds RECORD_AUDIO), printing to stdout:
 *
 *   CLASSPATH=/data/local/tmp/MicTest.apk app_process /system/bin org.wsalineage.mictest.MainActivity 3
 */
public final class MainActivity extends Activity {
    private static final String TAG = "MicTest";
    private static final int RATE = 48000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int seconds = getIntent().getIntExtra("seconds", 3);
        new Thread(() -> record(seconds), "mic").start();
        finish();
    }

    public static void main(String[] args) {
        System.out.println(record(args.length > 0 ? Integer.parseInt(args[0]) : 3));
    }

    private static String record(int seconds) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r;
        try {
            r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 5 * 2));
        } catch (RuntimeException e) {
            Log.e(TAG, "cannot create AudioRecord: " + e);
            return "cannot create AudioRecord: " + e;
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (no RECORD_AUDIO permission?)");
            r.release();
            return "AudioRecord not initialized";
        }
        short[] buf = new short[RATE / 50];
        long frames = 0, sum = 0;
        int peak = 0;
        r.startRecording();
        long t0 = System.nanoTime();
        Log.i(TAG, "recording " + seconds + " s, min buffer " + min + " bytes");
        while (frames < (long) RATE * seconds) {
            int n = r.read(buf, 0, buf.length);
            if (n <= 0) {
                Log.e(TAG, "read returned " + n);
                break;
            }
            for (int i = 0; i < n; i++) {
                int v = Math.abs(buf[i]);
                peak = Math.max(peak, v);
                sum += (long) buf[i] * buf[i];
            }
            frames += n;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        r.stop();
        r.release();
        String done = "done: " + frames + " frames in " + ms + " ms, peak " + peak + ", rms "
                + (frames > 0 ? (int) Math.sqrt((double) sum / frames) : 0);
        Log.i(TAG, done);
        return done;
    }
}
