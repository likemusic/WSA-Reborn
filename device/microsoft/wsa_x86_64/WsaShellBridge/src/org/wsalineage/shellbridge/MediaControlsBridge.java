/*
 * "SystemMediaControlsChannel" channel: the Windows media flyout for Android media sessions.
 *
 * The bridge watches active media sessions (MediaSessionManager) and reports each one to the host:
 * appeared/gone, playing/paused, which buttons are available, title, artist, artwork. Windows
 * shows this in its media flyout (by the volume icon, on the lock screen) and sends
 * back presses of its buttons and of keyboard media keys — they go to the session's TransportControls.
 *
 * At Microsoft the same is done by SystemMediaControlsRedirectionHandler from UserApp.apk; this code is our own.
 * Differences from the reference are intentional:
 *   * after the channel reconnects, the state of all sessions is sent again — the host forgets it
 *     together with the channel, the reference does not care about that;
 *   * same on every new window of the package (ShellBridge.setHostWindowListener): the host keeps
 *     the media flyout tied to the window, does not create it without a window, removes it when the window closes; the reference announced the session
 *     once, and after reopening the window the player did not come back to the flyout;
 *   * a session the app recreated (new token for the same package) replaces the previous one —
 *     the reference kept listening to a dead controller;
 *   * all three metadata fields are always sent (missing ones as an empty string), otherwise Windows
 *     would keep the artist of the previous track;
 *   * artwork is taken from ALBUM_ART, then ART, then DISPLAY_ICON, scaled down to ART_MAX
 *     and not resent if unchanged; for a track without artwork it is reset with an empty
 *     image — the reference kept the previous track's artwork; state and buttons are also sent only on change
 *     (players report the playback position every second).
 * Analysis — docs/wsa-shell-protocol.md, section "SystemMediaControls channel".
 */
package org.wsalineage.shellbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Slog;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;
import org.wsalineage.shellbridge.proto.Smtc.EnabledButtonsMessage;
import org.wsalineage.shellbridge.proto.Smtc.MediaButtons;
import org.wsalineage.shellbridge.proto.Smtc.MediaControlsEnabledMessage;
import org.wsalineage.shellbridge.proto.Smtc.MediaTransportControlsMessage;
import org.wsalineage.shellbridge.proto.Smtc.MessageType;
import org.wsalineage.shellbridge.proto.Smtc.MetadataKey;
import org.wsalineage.shellbridge.proto.Smtc.PlaybackStateMessage;
import org.wsalineage.shellbridge.proto.Smtc.Result;
import org.wsalineage.shellbridge.proto.Smtc.SetArtworkMessage;
import org.wsalineage.shellbridge.proto.Smtc.SetMetadataMessage;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MediaControlsBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge.Media";
    private static final String CHANNEL = "SystemMediaControlsChannel";
    private static final long RECONNECT_MS = 2_000;
    /** Artwork side in pixels: the Windows media flyout shows it as a thumbnail. */
    private static final int ART_MAX = 512;

    private final Handler mH;
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);
    private final MediaSessionManager mMsm;
    /** Sessions, their callbacks and everything sent to the host — only on this thread. */
    private final Handler mWork;
    private final Map<String, Session> mSessions = new HashMap<>();

    public MediaControlsBridge(Context context, Handler handler) {
        mH = handler;
        mMsm = context.getSystemService(MediaSessionManager.class);
        HandlerThread t = new HandlerThread("WsaShellBridge.Media");
        t.start();
        mWork = t.getThreadHandler();
    }

    public void start() {
        mWork.post(() -> {
            // null instead of a notification listener: MEDIA_CONTENT_CONTROL grants access to all sessions.
            mMsm.addOnActiveSessionsChangedListener(this::onActiveSessionsChanged, null, mWork);
            onActiveSessionsChanged(mMsm.getActiveSessions(null));
        });
        // The host keeps the media flyout tied to the package window: without a window it rejects SetSmtcEnabled, and
        // removes it when the window closes. So a new window is a reason to announce the package session again.
        ShellBridge.setHostWindowListener(pkg -> mWork.post(() -> {
            Session s = mSessions.get(pkg);
            if (s != null) {
                Slog.i(TAG, "host window for " + pkg + ", announcing session again");
                // A repeated "enabled" is silently ignored by the host: it already lists the package with
                // a media flyout — the one from the closed window. So we disable it first.
                send(message(MessageType.SetSmtcEnabled, pkg).setEnabledMessage(
                        MediaControlsEnabledMessage.newBuilder().setEnabled(false)));
                s.announce();
            }
        }));
        mH.post(this::connect);
    }

    private void connect() {
        try {
            mChannel.open();
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "open failed: " + e.getMessage() + ", retry in " + RECONNECT_MS + "ms");
            mH.postDelayed(this::connect, RECONNECT_MS);
            return;
        }
        // The host knows about sessions only what it heard on this channel.
        mWork.post(() -> {
            for (Session s : mSessions.values()) s.announce();
        });
    }

    @Override
    public void onClosed() {
        mH.postDelayed(this::connect, RECONNECT_MS);
    }

    // ---------------------------------------------------------------- Android → host

    private void onActiveSessionsChanged(List<MediaController> controllers) {
        if (controllers == null) return;
        // A package may have several sessions, but the host distinguishes them only by package — the
        // media flyout gets the first in the list, i.e. the most recently active one.
        Set<String> seen = new HashSet<>();
        for (MediaController c : controllers) {
            String pkg = c.getPackageName();
            if (!seen.add(pkg)) continue;
            Session old = mSessions.get(pkg);
            if (old != null && old.mController.getSessionToken().equals(c.getSessionToken())) continue;
            if (old != null) old.release();
            Session s = new Session(pkg, c);
            mSessions.put(pkg, s);
            Slog.i(TAG, (old == null ? "session added: " : "session replaced: ") + pkg);
            s.announce();
        }
        Set<String> gone = new HashSet<>(mSessions.keySet());
        gone.removeAll(seen);
        for (String pkg : gone) {
            Session s = mSessions.remove(pkg);
            s.release();
            Slog.i(TAG, "session removed: " + pkg);
            send(message(MessageType.SetSmtcEnabled, pkg).setEnabledMessage(
                    MediaControlsEnabledMessage.newBuilder().setEnabled(false)));
        }
    }

    private final class Session extends MediaController.Callback {
        final String mPkg;
        final MediaController mController;
        // What the host already knows — so as not to resend the same thing.
        private MediaTransportControlsMessage.Builder mLastState;
        private MediaTransportControlsMessage.Builder mLastButtons;
        private MediaTransportControlsMessage.Builder mLastMeta;
        private Bitmap mLastArt;

        Session(String pkg, MediaController controller) {
            mPkg = pkg;
            mController = controller;
            controller.registerCallback(this, mWork);
        }

        void release() {
            mController.unregisterCallback(this);
        }

        /** Tell the host about the whole session: on appearance and after the channel reconnects. */
        void announce() {
            mLastState = mLastButtons = mLastMeta = null;
            mLastArt = null;
            send(message(MessageType.SetSmtcEnabled, mPkg).setEnabledMessage(
                    MediaControlsEnabledMessage.newBuilder().setEnabled(true)));
            onMetadataChanged(mController.getMetadata());
            onPlaybackStateChanged(mController.getPlaybackState());
        }

        @Override
        public void onSessionDestroyed() {
            // The list of active sessions arrives next and removes it; here we only stop listening.
            release();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state == null) return;
            MediaTransportControlsMessage.Builder st = message(MessageType.SetPlaybackState, mPkg)
                    .setPlaybackStateMessage(PlaybackStateMessage.newBuilder()
                            .setState(windowsState(state.getState())));
            if (!same(st, mLastState)) {
                Slog.d(TAG, mPkg + ": state " + state.getState() + " -> " + st.getPlaybackStateMessage().getState());
                mLastState = st;
                send(st);
            }
            MediaTransportControlsMessage.Builder bt = message(MessageType.SetEnabledButtons, mPkg)
                    .setEnabledButtonsMessage(EnabledButtonsMessage.newBuilder()
                            .setEnabledButtons(windowsButtons(state.getActions())));
            if (!same(bt, mLastButtons)) {
                mLastButtons = bt;
                send(bt);
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata md) {
            if (md == null) return;
            Bitmap art = artwork(md);
            if (art == null && mLastArt != null) {
                // An empty image makes the host reset the artwork; otherwise a track without artwork would keep
                // the previous one's. The reset goes BEFORE the metadata: the host stores the empty artwork
                // but does not call the media flyout's Update(), while after metadata it does, if they
                // changed. If a track without artwork did not change the metadata either, the reset is not visible
                // until the next change — this is a host bug, on the list for our own WsaClient.
                Slog.d(TAG, mPkg + ": artwork cleared");
                mLastArt = null;
                send(message(MessageType.SetArtwork, mPkg).setSetArtworkMessage(
                        SetArtworkMessage.newBuilder().setAlbumArtBitmap(ByteString.EMPTY)));
            }
            MediaTransportControlsMessage.Builder m = message(MessageType.SetMetadata, mPkg)
                    .addSetMetadataMessage(meta(MetadataKey.Title, md, MediaMetadata.METADATA_KEY_TITLE))
                    .addSetMetadataMessage(meta(MetadataKey.Artist, md, MediaMetadata.METADATA_KEY_ARTIST))
                    .addSetMetadataMessage(meta(MetadataKey.AlbumArtist, md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
            if (!same(m, mLastMeta)) {
                // Track titles are not logged — only lengths.
                Slog.d(TAG, mPkg + ": metadata, title " + m.getSetMetadataMessage(0).getValue().length() + " chars");
                mLastMeta = m;
                send(m);
            }
            if (art != null && (mLastArt == null || !art.sameAs(mLastArt))) {
                byte[] png = png(art);
                if (png != null) {
                    Slog.d(TAG, mPkg + ": artwork " + art.getWidth() + "x" + art.getHeight() + ", " + png.length + " bytes");
                    mLastArt = art;
                    send(message(MessageType.SetArtwork, mPkg).setSetArtworkMessage(
                            SetArtworkMessage.newBuilder().setAlbumArtBitmap(ByteString.copyFrom(png))));
                }
            }
        }

        void press(MediaButtons button) {
            MediaController.TransportControls tc = mController.getTransportControls();
            switch (button) {
                case KeyPrevious: tc.skipToPrevious(); break;
                case KeyRewind: tc.rewind(); break;
                case KeyPlay: tc.play(); break;
                case KeyPause: tc.pause(); break;
                case KeyStop: tc.stop(); break;
                case KeyFastForward: tc.fastForward(); break;
                case KeyNext: tc.skipToNext(); break;
            }
        }
    }

    // ---------------------------------------------------------------- host → Android

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        MediaTransportControlsMessage msg;
        try {
            msg = MediaTransportControlsMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad MediaTransportControlsMessage: " + e.getMessage(),
                    ResultCode.InvalidProtocolFormat);
        }
        if (msg.getMessageType() != MessageType.MediaKeyPress || !msg.hasButtonPressedMessage()) {
            throw new ApiChannel.ApiException("unexpected " + msg.getMessageType(),
                    ResultCode.InvalidProtocolFormat);
        }
        String pkg = msg.getPackageName();
        MediaButtons button = msg.getButtonPressedMessage().getButtonPressed();
        Slog.d(TAG, "host -> " + button + " for " + pkg);
        mWork.post(() -> {
            Session s = mSessions.get(pkg);
            if (s != null) {
                s.press(button);
            } else {
                Slog.w(TAG, button + " for " + pkg + ": no such session");
            }
        });
        // The reply to the host is its own message with the result, as in the reference.
        return msg.toBuilder().setResult(Result.newBuilder().setSucceeded(true)).build().toByteArray();
    }

    // ---------------------------------------------------------------- value conversion

    private static org.wsalineage.shellbridge.proto.Smtc.PlaybackState windowsState(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
                return org.wsalineage.shellbridge.proto.Smtc.PlaybackState.Playing;
            case PlaybackState.STATE_PAUSED:
                return org.wsalineage.shellbridge.proto.Smtc.PlaybackState.Paused;
            case PlaybackState.STATE_ERROR:
                return org.wsalineage.shellbridge.proto.Smtc.PlaybackState.Closed;
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return org.wsalineage.shellbridge.proto.Smtc.PlaybackState.Changing;
            default: // STATE_NONE, STATE_STOPPED
                return org.wsalineage.shellbridge.proto.Smtc.PlaybackState.Stopped;
        }
    }

    private static int windowsButtons(long actions) {
        int b = 0;
        if ((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) b |= MediaButtons.KeyPrevious_VALUE;
        if ((actions & PlaybackState.ACTION_REWIND) != 0) b |= MediaButtons.KeyRewind_VALUE;
        // PLAY_PAUSE is a single toggle button; Windows distinguishes two.
        if ((actions & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0) b |= MediaButtons.KeyPlay_VALUE;
        if ((actions & (PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) != 0) b |= MediaButtons.KeyPause_VALUE;
        if ((actions & PlaybackState.ACTION_STOP) != 0) b |= MediaButtons.KeyStop_VALUE;
        if ((actions & PlaybackState.ACTION_FAST_FORWARD) != 0) b |= MediaButtons.KeyFastForward_VALUE;
        if ((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) b |= MediaButtons.KeyNext_VALUE;
        return b;
    }

    private static SetMetadataMessage meta(MetadataKey key, MediaMetadata md, String androidKey) {
        CharSequence v = md.getText(androidKey);
        return SetMetadataMessage.newBuilder().setKey(key).setValue(v == null ? "" : v.toString()).build();
    }

    private static Bitmap artwork(MediaMetadata md) {
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    private static byte[] png(Bitmap art) {
        Bitmap b = art;
        int side = Math.max(art.getWidth(), art.getHeight());
        if (side > ART_MAX) {
            float k = (float) ART_MAX / side;
            b = Bitmap.createScaledBitmap(art, Math.max(1, Math.round(art.getWidth() * k)),
                    Math.max(1, Math.round(art.getHeight() * k)), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) {
            Slog.w(TAG, "artwork: PNG encoding failed");
            return null;
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- sending

    private static MediaTransportControlsMessage.Builder message(MessageType type, String pkg) {
        return MediaTransportControlsMessage.newBuilder().setMessageType(type).setPackageName(pkg);
    }

    private static boolean same(MediaTransportControlsMessage.Builder a,
            MediaTransportControlsMessage.Builder b) {
        return b != null && Objects.equals(a.build(), b.build());
    }

    private void send(MediaTransportControlsMessage.Builder msg) {
        if (!mChannel.isOpen()) return; // after connecting, announce() will resend everything
        try {
            mChannel.sendAsync(msg.build().toByteArray());
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, msg.getMessageType() + " for " + msg.getPackageName() + " send failed: " + e.getMessage());
        }
    }
}
