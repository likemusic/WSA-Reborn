/*
 * "TextToSpeech" channel: speech synthesis with Windows voices.
 *
 * Transport for the WindowsTtsService speech engine: the voice list and speaking text. Requests
 * come only from the guest, the host replies; audio comes back whole in a single buffer.
 *
 * In Microsoft's image the same is done by TextToSpeechRedirectionHandler and LatteTextToSpeechService from
 * UserApp.apk; this code is our own. Analysis: docs/wsa-shell-protocol.md, section on the TextToSpeech channel.
 */
package org.wsalineage.shellbridge;

import android.content.Context;
import android.os.Handler;
import android.provider.Settings;
import android.util.Slog;

import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;
import org.wsalineage.shellbridge.proto.Tts.GetVoices;
import org.wsalineage.shellbridge.proto.Tts.SynthesizeText;
import org.wsalineage.shellbridge.proto.Tts.TextToSpeechMessage;

import java.util.Collections;
import java.util.List;

public final class TtsBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge.TTS";
    private static final String CHANNEL = "TextToSpeech";
    private static final long RECONNECT_MS = 2_000;
    private static final long VOICES_TIMEOUT_MS = 10_000;
    /** Windows synthesizes a chunk of text in a fraction of a second; the margin is for a voice's cold start. */
    private static final long SYNTH_TIMEOUT_MS = 60_000;
    /**
     * The voice list lives for a minute: the engine asks for it on every language check, and a voice can be
     * installed in Windows without restarting the subsystem.
     */
    private static final long VOICES_TTL_MS = 60_000;

    private final Context mContext;
    private final Handler mH;
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);

    private static volatile TtsBridge sInstance;

    private List<GetVoices.Voice> mVoices;
    private long mVoicesAt;

    public TtsBridge(Context context, Handler handler) {
        mContext = context;
        mH = handler;
        sInstance = this;
    }

    /** For WindowsTtsService: the engine lives in the same process as the bridge. */
    static TtsBridge get() {
        return sInstance;
    }

    public void start() {
        mH.post(() -> {
            makeDefault();
            connect();
        });
    }

    /**
     * Make this engine the default speech synthesizer if no other one is selected. Vanilla AOSP has no
     * engine of its own at all; the user's choice (Settings → Text-to-speech) is not overridden.
     */
    private void makeDefault() {
        String current = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.TTS_DEFAULT_SYNTH);
        if (current != null && !current.isEmpty()) {
            Slog.i(TAG, "default TTS engine stays " + current);
            return;
        }
        String ours = mContext.getPackageName();
        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.TTS_DEFAULT_SYNTH, ours);
        Slog.i(TAG, "default TTS engine: " + ours);
    }

    private void connect() {
        try {
            mChannel.open();
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "open failed: " + e.getMessage() + ", retry in " + RECONNECT_MS + "ms");
            mH.postDelayed(this::connect, RECONNECT_MS);
        }
    }

    @Override
    public void onClosed() {
        synchronized (this) {
            mVoices = null;
        }
        mH.postDelayed(this::connect, RECONNECT_MS);
    }

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        throw new ApiChannel.ApiException("host requests are not expected on " + CHANNEL,
                ResultCode.InvalidProtocolFormat);
    }

    /** Windows voices; an empty list if the channel is not open or the host did not reply. */
    List<GetVoices.Voice> voices() {
        synchronized (this) {
            if (mVoices != null && System.currentTimeMillis() - mVoicesAt < VOICES_TTL_MS) return mVoices;
        }
        try {
            TextToSpeechMessage reply = call(TextToSpeechMessage.newBuilder()
                    .setMessageType(TextToSpeechMessage.MessageType.GetVoices)
                    .setGetVoices(GetVoices.getDefaultInstance()), VOICES_TIMEOUT_MS);
            if (!reply.hasGetVoices()) throw new ApiChannel.ApiException("no voices in reply");
            List<GetVoices.Voice> v = reply.getGetVoices().getVoicesList();
            synchronized (this) {
                if (mVoices == null || mVoices.size() != v.size()) Slog.i(TAG, "voices: " + v.size());
                mVoices = v;
                mVoicesAt = System.currentTimeMillis();
            }
            return v;
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "GetVoices failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Speak text. Volume is not passed: Android itself applies it on playback. */
    SynthesizeText.Output synthesize(String text, String voice, int pitch, int rate)
            throws ApiChannel.ApiException {
        SynthesizeText.Builder req = SynthesizeText.newBuilder()
                .setText(text).setPitch(pitch).setSpeechRate(rate);
        if (voice != null) req.setVoiceName(voice);
        TextToSpeechMessage reply = call(TextToSpeechMessage.newBuilder()
                .setMessageType(TextToSpeechMessage.MessageType.SynthesizeText)
                .setSynthesizeText(req), SYNTH_TIMEOUT_MS);
        if (!reply.hasSynthesizeText() || !reply.getSynthesizeText().hasOutput()) {
            throw new ApiChannel.ApiException("no audio in reply", ResultCode.InvalidProtocolFormat);
        }
        return reply.getSynthesizeText().getOutput();
    }

    private TextToSpeechMessage call(TextToSpeechMessage.Builder msg, long timeoutMs)
            throws ApiChannel.ApiException {
        if (!mChannel.isOpen()) throw new ApiChannel.ApiException("channel not open", ResultCode.NotReady);
        byte[] payload = mChannel.send(msg.build().toByteArray(), timeoutMs);
        TextToSpeechMessage reply;
        try {
            reply = TextToSpeechMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad reply: " + e.getMessage(), ResultCode.InvalidProtocolFormat);
        }
        if (!reply.getResult()) throw new ApiChannel.ApiException(msg.getMessageType() + " failed on host");
        return reply;
    }
}
