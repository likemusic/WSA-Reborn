package org.wsalineage.ttstest;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * Test app for the "TextToSpeech" channel: prints engines and voices, speaks text aloud or
 * writes it to a WAV — all with a report in logcat (tag TtsTest).
 *
 *   am start -S -n org.wsalineage.ttstest/.MainActivity                        — list, default Russian greeting aloud
 *   ... -e text 'Any text' -e lang ru-RU -e voice '<voice name>'
 *   ... -e file 1  — not aloud but to /data/data/org.wsalineage.ttstest/files/tts.wav
 *   ... --ei repeat 30 — repeat the text N times (long text, chunking check)
 *   ... -e stop 1 — stop 2 s after the start
 */
public final class MainActivity extends Activity {
    private static final String TAG = "TtsTest";
    private TextToSpeech mTts;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String text0 = getIntent().getStringExtra("text");
        final String lang = getIntent().getStringExtra("lang");
        final String voice = getIntent().getStringExtra("voice");
        final boolean toFile = getIntent().getStringExtra("file") != null;
        final boolean stop = getIntent().getStringExtra("stop") != null;
        int repeat = getIntent().getIntExtra("repeat", 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repeat; i++) {
            sb.append(text0 != null ? text0 : "Привет! Это голос Windows в Android.");
            if (i + 1 < repeat) sb.append(' ');
        }
        final String text = sb.toString();
        final long t0 = System.currentTimeMillis();
        mTts = new TextToSpeech(this, status -> {
            Log.i(TAG, "init status " + status + ", default engine " + mTts.getDefaultEngine());
            for (TextToSpeech.EngineInfo e : mTts.getEngines()) Log.i(TAG, "engine " + e.name + " (" + e.label + ")");
            if (status != TextToSpeech.SUCCESS) return;
            if (mTts.getVoices() != null) {
                for (Voice v : mTts.getVoices()) Log.i(TAG, "voice " + v.getName() + " " + v.getLocale().toLanguageTag());
            }
            Log.i(TAG, "default voice " + mTts.getDefaultVoice());
            if (lang != null) Log.i(TAG, "setLanguage " + lang + " -> " + mTts.setLanguage(Locale.forLanguageTag(lang)));
            if (voice != null) {
                for (Voice v : mTts.getVoices()) if (v.getName().equals(voice)) Log.i(TAG, "setVoice -> " + mTts.setVoice(v));
            }
            mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { Log.i(TAG, "onStart +" + (System.currentTimeMillis() - t0) + "ms"); }
                @Override public void onBeginSynthesis(String id, int rate, int fmt, int ch) {
                    Log.i(TAG, "onBeginSynthesis rate=" + rate + " format=" + fmt + " channels=" + ch + " +" + (System.currentTimeMillis() - t0) + "ms");
                }
                @Override public void onDone(String id) { Log.i(TAG, "onDone +" + (System.currentTimeMillis() - t0) + "ms"); }
                @Override public void onStop(String id, boolean interrupted) { Log.i(TAG, "onStop interrupted=" + interrupted); }
                @Override public void onError(String id) { Log.i(TAG, "onError"); }
                @Override public void onError(String id, int code) { Log.i(TAG, "onError " + code); }
            });
            Log.i(TAG, "speak " + text.length() + " chars" + (toFile ? " to file" : ""));
            if (toFile) {
                File f = new File(getFilesDir(), "tts.wav");
                mTts.synthesizeToFile(text, null, f, "u1");
            } else {
                mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u1");
            }
            if (stop) new android.os.Handler().postDelayed(() -> Log.i(TAG, "stop -> " + mTts.stop()), 2000);
        });
    }

    @Override
    protected void onDestroy() {
        if (mTts != null) mTts.shutdown();
        super.onDestroy();
    }
}
