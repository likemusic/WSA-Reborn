/*
 * Reply to android.speech.tts.engine.CHECK_TTS_DATA: which languages the Windows voices have. This is
 * used by old apps and the Text-to-speech screen in Settings.
 *
 * In Microsoft's image this is CheckVoiceData from UserApp.apk; this code is our own.
 */
package org.wsalineage.shellbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import org.wsalineage.shellbridge.proto.Tts.GetVoices;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Set;

public final class WindowsTtsCheckData extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Set<String> langs = new LinkedHashSet<>();
        TtsBridge tts = TtsBridge.get();
        if (tts != null) {
            for (GetVoices.Voice v : tts.voices()) {
                Locale l = Locale.forLanguageTag(v.getLanguageTag());
                try {
                    // Reply format is "eng-USA" (three-letter ISO codes joined by a hyphen).
                    StringBuilder s = new StringBuilder(l.getISO3Language());
                    if (!l.getCountry().isEmpty()) s.append('-').append(l.getISO3Country());
                    if (!l.getVariant().isEmpty()) s.append('-').append(l.getVariant());
                    langs.add(s.toString());
                } catch (MissingResourceException ignored) {
                    // Android cannot describe a language without a three-letter code
                }
            }
        }
        Intent result = new Intent();
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, new ArrayList<>(langs));
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, new ArrayList<>());
        setResult(langs.isEmpty() ? TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
                : TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result);
        finish();
    }
}
