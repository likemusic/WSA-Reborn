package org.wsalineage.mediatest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Test app for the "SystemMediaControls" channel: a silent "player".
 * A window (MainActivity) is required: the host does not create a media panel entry for an app without a window. Holds a media session with
 * three tracks (title, artist, artwork), honestly changes state on commands and logs to
 * logcat every command that reaches the session, including those from the Windows media panel.
 *
 *   am start-foreground-service -n org.wsalineage.mediatest/.PlayerService              — start
 *   am start-foreground-service -n org.wsalineage.mediatest/.PlayerService -e cmd next  — next track
 *   ... -e cmd pause | play | prev | stop | noart | release
 *     noart   — remove artwork from the current track (check that Windows clears it);
 *     release — close the session and stop the service (the player must disappear from the media panel).
 */
public final class PlayerService extends Service {
    static final String TAG = "MediaTest";
    private static final String CHANNEL_ID = "mediatest";

    private static final String[][] TRACKS = {
        {"Первый трек", "Исполнитель Один", "Альбомный исполнитель"},
        {"Second track", "Artist Two", null},
        {"Третий — без обложки", "Исполнитель Три", null},
    };
    private static final int[] COLORS = {Color.rgb(200, 60, 60), Color.rgb(60, 120, 200), 0};

    private MediaSession mSession;
    private int mTrack;
    private int mState = PlaybackState.STATE_NONE;
    private boolean mNoArt;

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "MediaTest", NotificationManager.IMPORTANCE_LOW));
        mSession = new MediaSession(this, TAG);
        mSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { cmd("onPlay"); setState(PlaybackState.STATE_PLAYING); }
            @Override public void onPause() { cmd("onPause"); setState(PlaybackState.STATE_PAUSED); }
            @Override public void onStop() { cmd("onStop"); setState(PlaybackState.STATE_STOPPED); }
            @Override public void onSkipToNext() { cmd("onSkipToNext"); track(mTrack + 1); }
            @Override public void onSkipToPrevious() { cmd("onSkipToPrevious"); track(mTrack - 1); }
            @Override public void onFastForward() { cmd("onFastForward"); }
            @Override public void onRewind() { cmd("onRewind"); }
            @Override public boolean onMediaButtonEvent(Intent i) {
                cmd("onMediaButtonEvent " + i.getParcelableExtra(Intent.EXTRA_KEY_EVENT));
                return super.onMediaButtonEvent(i);
            }
        });
        mSession.setActive(true);
        Log.i(TAG, "session created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String c = intent == null ? null : intent.getStringExtra("cmd");
        if (c == null) c = "start";
        Log.i(TAG, "adb cmd: " + c);
        switch (c) {
            case "start": track(0); setState(PlaybackState.STATE_PLAYING); break;
            case "play": setState(PlaybackState.STATE_PLAYING); break;
            case "pause": setState(PlaybackState.STATE_PAUSED); break;
            case "stop": setState(PlaybackState.STATE_STOPPED); break;
            case "next": track(mTrack + 1); break;
            case "prev": track(mTrack - 1); break;
            case "noart": mNoArt = true; track(mTrack); break;
            case "release": stopSelf(); return START_NOT_STICKY;
            default: Log.w(TAG, "unknown cmd " + c);
        }
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mSession.release();
        Log.i(TAG, "session released");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void cmd(String what) {
        Log.i(TAG, "SESSION COMMAND: " + what);
    }

    private void track(int n) {
        mTrack = (n + TRACKS.length) % TRACKS.length;
        if (n != mTrack) mNoArt = false;
        String[] t = TRACKS[mTrack];
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t[0])
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t[1])
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 180_000);
        if (t[2] != null) md.putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, t[2]);
        if (COLORS[mTrack] != 0 && !mNoArt) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art(mTrack));
        mSession.setMetadata(md.build());
        Log.i(TAG, "track " + (mTrack + 1) + ": " + t[0]);
        setState(mState == PlaybackState.STATE_NONE ? PlaybackState.STATE_PLAYING : mState);
    }

    private void setState(int state) {
        mState = state;
        mSession.setPlaybackState(new PlaybackState.Builder()
                .setState(state, 0, state == PlaybackState.STATE_PLAYING ? 1f : 0f, SystemClock.elapsedRealtime())
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .build());
        Log.i(TAG, "state " + state);
    }

    /** 800×800 artwork — larger than the bridge limit, to test downscaling. */
    private static Bitmap art(int n) {
        Bitmap b = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(COLORS[n]);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(480);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(String.valueOf(n + 1), 400, 570, p);
        return b;
    }

    private Notification notification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(TRACKS[mTrack][0])
                .setContentText(TRACKS[mTrack][1])
                .setStyle(new Notification.MediaStyle().setMediaSession(mSession.getSessionToken()))
                .build();
    }
}
