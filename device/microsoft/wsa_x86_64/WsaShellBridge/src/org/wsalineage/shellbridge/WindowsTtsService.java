/*
 * Android text-to-speech engine whose voices are Windows voices (channel "TextToSpeech", see
 * TtsBridge). The bridge makes it the default engine if no other one is selected.
 *
 * Microsoft has LatteTextToSpeechService from UserApp.apk; this code is our own. Deviations from the reference
 * are deliberate:
 *   * long text is split into sentence chunks spoken one after another: the first sound
 *     arrives after synthesizing one sentence, not the whole text, and no host reply
 *     hits the channel frame limit (the reference allows 4000 characters, about ten megabytes of audio in one
 *     frame);
 *   * stop (onStop) interrupts speech between chunks; the reference's onStop is empty;
 *   * volume is not passed to the host: Android applies it on playback, whereas the reference
 *     applied it twice;
 *   * if the host returns a WAV with a header, the format is taken from the header and the header itself is not
 *     played as a click;
 *   * onGetLanguage answers with the loaded language, not null.
 */
package org.wsalineage.shellbridge;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Slog;

import org.wsalineage.shellbridge.proto.Tts.GetVoices;
import org.wsalineage.shellbridge.proto.Tts.SynthesizeText;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;

public final class WindowsTtsService extends TextToSpeechService {
    private static final String TAG = "WsaShellBridge.TTS";
    /** Max text chunk in characters: one or two sentences, a few seconds of audio. */
    private static final int MAX_CHUNK = 500;

    private volatile boolean mStopped;
    private volatile String[] mLanguage;

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        mStopped = false;
        TtsBridge tts = TtsBridge.get();
        String text = request.getCharSequenceText().toString();
        String voice = request.getVoiceName();
        if (voice == null || voice.isEmpty()) {
            voice = onGetDefaultVoiceNameFor(request.getLanguage(), request.getCountry(), request.getVariant());
        }
        if (tts == null) {
            Slog.w(TAG, "TTS bridge is not running");
            callback.error(TextToSpeech.ERROR_SERVICE);
            return;
        }
        Locale locale = voiceLocale(tts, voice);
        List<String> chunks = split(text, locale);
        // Do not log the text, only lengths.
        Slog.d(TAG, "synthesize " + text.length() + " chars in " + chunks.size() + " chunks, voice " + voice);
        int rate = -1;
        for (String chunk : chunks) {
            if (mStopped) break;
            Pcm pcm;
            try {
                SynthesizeText.Output out = tts.synthesize(chunk, voice, request.getPitch(), request.getSpeechRate());
                pcm = Pcm.of(out.getAudioBuffer().toByteArray(), out.getSampleRateHz());
            } catch (ApiChannel.ApiException e) {
                Slog.w(TAG, "synthesis failed: " + e.getMessage());
                callback.error(TextToSpeech.ERROR_SERVICE);
                return;
            }
            if (rate < 0) {
                rate = pcm.rate;
                if (callback.start(pcm.rate, pcm.encoding, pcm.channels) != TextToSpeech.SUCCESS) return;
            } else if (pcm.rate != rate) {
                Slog.w(TAG, "sample rate changed mid-utterance: " + rate + " -> " + pcm.rate);
            }
            int max = callback.getMaxBufferSize();
            for (int off = pcm.offset; off < pcm.end; ) {
                int n = Math.min(max, pcm.end - off);
                if (callback.audioAvailable(pcm.data, off, n) != TextToSpeech.SUCCESS) return;
                off += n;
            }
        }
        if (rate < 0 && !mStopped) {
            // Empty text: start and finish immediately, otherwise the app never gets onDone.
            callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        }
        callback.done();
    }

    @Override
    protected void onStop() {
        // Logged to see whether the app (TalkBack) asks to cut speech off when focus moves.
        Slog.d(TAG, "stop");
        mStopped = true;
    }

    // ---------------------------------------------------------------- voices and languages

    @Override
    public List<Voice> onGetVoices() {
        TtsBridge tts = TtsBridge.get();
        if (tts == null) return Collections.emptyList();
        List<Voice> out = new ArrayList<>();
        for (GetVoices.Voice v : tts.voices()) {
            out.add(new Voice(v.getName(), Locale.forLanguageTag(v.getLanguageTag()),
                    Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet()));
        }
        return out;
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
        for (Voice v : onGetVoices()) {
            if (v.getName().equals(voiceName)) return TextToSpeech.SUCCESS;
        }
        return TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String voiceName) {
        return onIsValidVoiceName(voiceName);
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return match(lang, country, variant).level;
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        int level = onIsLanguageAvailable(lang, country, variant);
        if (level >= TextToSpeech.LANG_AVAILABLE) {
            mLanguage = new String[] {lang, country == null ? "" : country, variant == null ? "" : variant};
        }
        return level;
    }

    @Override
    protected String[] onGetLanguage() {
        String[] l = mLanguage;
        if (l != null) return l;
        Locale d = Locale.getDefault();
        try {
            return new String[] {d.getISO3Language(), d.getISO3Country(), d.getVariant()};
        } catch (MissingResourceException e) {
            return new String[] {"eng", "USA", ""};
        }
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return match(lang, country, variant).voice;
    }

    private static final class Match {
        final String voice;
        final int level;
        Match(String voice, int level) { this.voice = voice; this.level = level; }
    }

    /** Best voice for a language: first language+country+variant, then language+country, then language. */
    private Match match(String lang, String country, String variant) {
        if (lang == null || lang.isEmpty()) return new Match(null, TextToSpeech.LANG_NOT_SUPPORTED);
        Locale want = new Locale(lang, country == null ? "" : country, variant == null ? "" : variant);
        String wl = iso3Language(want), wc = iso3Country(want), wv = want.getVariant();
        Match best = new Match(null, TextToSpeech.LANG_NOT_SUPPORTED);
        for (Voice v : onGetVoices()) {
            Locale l = v.getLocale();
            if (!iso3Language(l).equals(wl)) continue;
            int level = TextToSpeech.LANG_AVAILABLE;
            if (!wc.isEmpty() && iso3Country(l).equals(wc)) {
                level = wv.isEmpty() || !l.getVariant().equals(wv)
                        ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE;
            }
            if (level > best.level) best = new Match(v.getName(), level);
        }
        return best;
    }

    private static Locale voiceLocale(TtsBridge tts, String voice) {
        for (GetVoices.Voice v : tts.voices()) {
            if (v.getName().equals(voice)) return Locale.forLanguageTag(v.getLanguageTag());
        }
        return Locale.getDefault();
    }

    // The engine and Android compare languages as three-letter ISO codes, but apps send anything.
    private static String iso3Language(Locale l) {
        try { return l.getISO3Language(); } catch (MissingResourceException e) { return l.getLanguage(); }
    }

    private static String iso3Country(Locale l) {
        try { return l.getISO3Country(); } catch (MissingResourceException e) { return l.getCountry(); }
    }

    // ---------------------------------------------------------------- text chunks and audio

    /** Split text into chunks no longer than MAX_CHUNK at sentence (or word) boundaries. */
    static List<String> split(String text, Locale locale) {
        List<String> out = new ArrayList<>();
        BreakIterator it = BreakIterator.getSentenceInstance(locale);
        it.setText(text);
        StringBuilder cur = new StringBuilder();
        for (int b = it.first(), e = it.next(); e != BreakIterator.DONE; b = e, e = it.next()) {
            String s = text.substring(b, e);
            if (cur.length() + s.length() > MAX_CHUNK && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            while (s.length() > MAX_CHUNK) {
                // Sentence longer than the limit: cut at the last space before it.
                int cut = s.lastIndexOf(' ', MAX_CHUNK);
                if (cut <= 0) cut = MAX_CHUNK;
                out.add(s.substring(0, cut));
                s = s.substring(cut);
            }
            cur.append(s);
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString());
        return out;
    }

    /** Audio of the host reply: raw 16-bit mono PCM or, if the host sends one, WAV with a header. */
    static final class Pcm {
        byte[] data;
        int offset;
        int end;
        int rate;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int channels = 1;

        static Pcm of(byte[] buf, int rate) {
            Pcm p = new Pcm();
            p.data = buf;
            p.end = buf.length;
            p.rate = rate;
            if (buf.length >= 12 && tag(buf, 0, "RIFF") && tag(buf, 8, "WAVE")) {
                ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                for (int pos = 12; pos + 8 <= buf.length; ) {
                    int size = bb.getInt(pos + 4);
                    if (tag(buf, pos, "fmt ") && size >= 16) {
                        p.channels = bb.getShort(pos + 10);
                        p.rate = bb.getInt(pos + 12);
                        p.encoding = bb.getShort(pos + 22) == 8
                                ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;
                    } else if (tag(buf, pos, "data")) {
                        p.offset = pos + 8;
                        p.end = Math.min(buf.length, p.offset + Math.max(0, size));
                        break;
                    }
                    pos += 8 + size + (size & 1);
                }
            }
            return p;
        }

        private static boolean tag(byte[] b, int at, String t) {
            for (int i = 0; i < 4; i++) if (b[at + i] != t.charAt(i)) return false;
            return true;
        }
    }
}
