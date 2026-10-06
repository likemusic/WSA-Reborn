package org.wsalineage.soundtest;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.util.Log;

/**
 * Sound test app (item 20 of the #700 checklist): plays N beeps of different pitch via AudioTrack
 * (0.4 s each, 0.4 s pauses) — the listener names their count, and that is the check "sound reached the
 * host speakers". In logcat (tag SoundTest) — how many frames the track accepted and how many underruns there were.
 *
 *   am start -S -n org.wsalineage.soundtest/.MainActivity [--ei beeps 3]
 */
public final class MainActivity extends Activity {
    private static final String TAG = "SoundTest";
    private static final int RATE = 48000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int beeps = getIntent().getIntExtra("beeps", 3);
        new Thread(() -> play(beeps), "sound").start();
        finish();
    }

    private static void play(int beeps) {
        int beep = RATE * 4 / 10;
        short[] pcm = new short[beep * 2 * beeps];
        for (int b = 0; b < beeps; b++) {
            double f = 440 * Math.pow(1.5, b % 4);
            for (int i = 0; i < beep; i++) {
                double env = Math.min(1, Math.min(i, beep - i) / (RATE * 0.01)); // no clicks
                pcm[b * beep * 2 + i] = (short) (Math.sin(2 * Math.PI * f * i / RATE) * 12000 * env);
            }
        }
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
        int w = t.write(pcm, 0, pcm.length);
        Log.i(TAG, "playing " + beeps + " beeps, wrote " + w + " frames, routed to " + describe(t));
        long t0 = System.nanoTime();
        t.play();
        try {
            Thread.sleep(pcm.length * 1000L / RATE + 500);
        } catch (InterruptedException ignored) {
        }
        Log.i(TAG, "done: head position " + t.getPlaybackHeadPosition() + "/" + pcm.length + " frames in "
                + (System.nanoTime() - t0) / 1_000_000 + " ms, underruns " + t.getUnderrunCount()
                + ", device " + describe(t));
        t.release();
    }

    private static String describe(AudioTrack t) {
        return t.getRoutedDevice() == null ? "null"
                : t.getRoutedDevice().getProductName() + " type " + t.getRoutedDevice().getType();
    }
}
