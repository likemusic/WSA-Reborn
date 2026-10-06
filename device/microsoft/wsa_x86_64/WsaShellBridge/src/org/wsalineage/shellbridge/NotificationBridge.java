/*
 * Channel "Notification": Android notifications in the Windows notification center.
 *
 * Guest → host: a notification appeared (SendNotification) or went away (CancelNotification).
 * Host → guest: the user clicked the notification or one of its buttons (Activation), swiped it away
 * (Dismissal), saw it (ReportNotificationSeenEvent).
 *
 * Schema: proto/notification.proto (genuine, from the host WsaClient.exe). Reference for the guest
 * half: NotificationRedirectionHandler from SystemUIApp.apk; the code here is our own. Deviations from the reference
 * are deliberate and marked in place: text is read as CharSequence, the BigPictureStyle picture
 * is also taken in its new form (Icon), PendingIntent is launched on behalf of the app with explicit
 * permission for a background start, picture size is capped. Analysis: docs/wsa-shell-protocol.md,
 * the section on the "Notification" channel.
 *
 * Toasts do not go through NotificationManager: SystemUI (ToastUI) shows them,
 * and they can only be intercepted from inside it. Platform patch docs/patches/0012 makes
 * ToastUI not draw the Toast but forward it here with an explicit broadcast (ACTION_TOAST_SHOW/HIDE);
 * the receiver is protected by STATUS_BAR_SERVICE, so only SystemUI can send it. From there the Toast
 * goes to the host as a message of type Toast, and on hide as CancelNotification, like Microsoft's WsaToastUI.
 */
package org.wsalineage.shellbridge;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.Common.ActivityId;
import org.wsalineage.shellbridge.proto.Common.PNG;
import org.wsalineage.shellbridge.proto.Notification.NotificationAction;
import org.wsalineage.shellbridge.proto.Notification.NotificationActivation;
import org.wsalineage.shellbridge.proto.Notification.NotificationDetail;
import org.wsalineage.shellbridge.proto.Notification.NotificationImage;
import org.wsalineage.shellbridge.proto.Notification.NotificationMessage;
import org.wsalineage.shellbridge.proto.Notification.NotificationProgress;
import org.wsalineage.shellbridge.proto.Notification.Result;
import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.UUID;

public final class NotificationBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge.Notification";
    private static final String CHANNEL = "Notification";
    private static final long RECONNECT_MS = 2_000;

    /**
     * Cap on the picture in a single notification. Microsoft has none, and this is not nitpicking: the icon and
     * picture go over the channel WHOLE, in one frame, and BigPictureStyle brings a photo at
     * full resolution. The host will not accept a frame larger than MAX_FRAME, and the channel will break out of the blue.
     */
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    /** Fallback size for an icon that cannot report its own dimensions (ColorDrawable). */
    private static final int FALLBACK_ICON_PX = 64;

    /** Broadcasts from SystemUI with patch 0012 (WsaToastForwarder); names must match it. */
    private static final String ACTION_TOAST_SHOW = "org.wsalineage.shellbridge.action.TOAST_SHOW";
    private static final String ACTION_TOAST_HIDE = "org.wsalineage.shellbridge.action.TOAST_HIDE";

    /**
     * Display duration in Windows. Numbers from Microsoft's WsaToastUI: in Android a short Toast
     * lives 2 s, a long one 3.5 s, but a Windows toast barely manages to slide in within that time.
     */
    private static final int TOAST_SHORT_MS = 4_000;
    private static final int TOAST_LONG_MS = 7_000;

    private final Context mContext;
    private final Handler mH;
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);
    private final PackageManager mPm;

    /**
     * id of all Toasts on the host; they are told apart by tag (the Toast token). Random, like Microsoft's, so as
     * not to collide with the id of a real notification of the same app.
     */
    private final int mToastId = new Random().nextInt(10_000);

    private volatile Listener mListener;
    private volatile IStatusBarService mStatusBar;

    public NotificationBridge(Context context, Handler handler) {
        mContext = context;
        mH = handler;
        mPm = context.getPackageManager();
    }

    public void start() {
        mH.post(() -> {
            registerListener();
            registerToastReceiver();
            connect();
        });
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
        mH.postDelayed(this::connect, RECONNECT_MS);
    }

    /**
     * The notification listener is registered NOT via the "notification access" setting but directly through
     * registerAsSystemService: it needs neither a service declared in the manifest, nor user
     * consent, nor an entry in enabled_notification_listeners. Access is by uid: the check in
     * NotificationManagerService is "system or SystemUI", and the bridge runs as uid system.
     * USER_ALL so notifications come from all profiles, including the work profile.
     */
    private void registerListener() {
        if (ServiceManager.checkService(Context.NOTIFICATION_SERVICE) == null) {
            // We must not wait here with a blocking waitForService: the thread is shared by all bridge channels,
            // and until NotificationManagerService shows up, reconnecting "Shell" would stall too.
            mH.postDelayed(this::registerListener, RECONNECT_MS);
            return;
        }
        try {
            Listener listener = new Listener();
            listener.registerAsSystemService(mContext,
                    new ComponentName(mContext, NotificationBridge.class), UserHandle.USER_ALL);
            mListener = listener;
            Slog.i(TAG, "notification listener registered");
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "listener registration failed: " + e + ", retry in " + RECONNECT_MS + "ms");
            mH.postDelayed(this::registerListener, RECONNECT_MS);
        }
    }

    private final class Listener extends NotificationListenerService {
        @Override
        public void onListenerConnected() {
            Slog.i(TAG, "listener connected");
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            if (sbn == null || sbn.getNotification() == null) return;
            // Windows shows media notifications not in the notification center but in its own player:
            // that is the separate channel "SystemMediaControlsChannel". A group summary duplicates what
            // has already been sent in parts.
            if (isMediaStyle(sbn) || isGroupSummary(sbn)) return;
            mH.post(() -> sendNotification(sbn));
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn) {
            if (sbn == null || sbn.getNotification() == null) return;
            if (isMediaStyle(sbn)) return;
            mH.post(() -> sendRemoved(sbn));
        }
    }

    private void registerToastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TOAST_SHOW);
        filter.addAction(ACTION_TOAST_HIDE);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onToast(intent);
            }
        }, filter, android.Manifest.permission.STATUS_BAR_SERVICE, mH, Context.RECEIVER_EXPORTED);
    }

    private static boolean isMediaStyle(StatusBarNotification sbn) {
        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return false;
        String template = extras.getString(Notification.EXTRA_TEMPLATE);
        return template != null && template.contains(Notification.MediaStyle.class.getName());
    }

    private static boolean isGroupSummary(StatusBarNotification sbn) {
        return (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    // ---------------------------------------------------------------- guest → host

    private void sendNotification(StatusBarNotification sbn) {
        if (!mChannel.isOpen()) {
            Slog.d(TAG, "channel is not open, dropping notification from " + sbn.getPackageName());
            return;
        }
        Notification n = sbn.getNotification();
        Bundle extras = n.extras != null ? n.extras : Bundle.EMPTY;
        NotificationMessage.Builder msg = newMessage(NotificationMessage.MessageType.SendNotification,
                sbn.getPackageName(), sbn.getId(), sbn.getTag());

        // Microsoft reads title and text with getString. For a notification built from
        // styled text (Html.fromHtml, setContentText with a Spannable), the value is not a String but a
        // CharSequence, and getString silently returns null: Windows gets an empty string.
        // Caught by reading the reference; getCharSequence handles both.
        msg.setDetail(NotificationDetail.newBuilder()
                .setAppName(appLabel(sbn.getPackageName()))
                .setTitle(text(extras, Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG))
                .setText(text(extras, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT))
                .build());

        int max = extras.getInt(Notification.EXTRA_PROGRESS_MAX);
        boolean indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE);
        if (max > 0 || indeterminate) {
            msg.setProgress(NotificationProgress.newBuilder()
                    .setMax(max)
                    .setProgress(extras.getInt(Notification.EXTRA_PROGRESS))
                    .setIndeterminate(indeterminate)
                    .build());
        }

        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (action == null || action.title == null) continue;
                NotificationAction.Builder a =
                        NotificationAction.newBuilder().setTitle(action.title.toString());
                RemoteInput[] inputs = action.getRemoteInputs();
                if (inputs != null) {
                    for (RemoteInput input : inputs) a.addInputKey(input.getResultKey());
                }
                msg.addAction(a.build());
            }
        }

        NotificationImage image = imageOf(n, extras);
        if (image != null) msg.setImage(image);

        Slog.i(TAG, "posted " + sbn.getPackageName() + " id=" + sbn.getId()
                + " tag=" + sbn.getTag() + " actions=" + msg.getActionCount()
                + (image != null ? " +image" : ""));
        send(msg.build());
    }

    private void sendRemoved(StatusBarNotification sbn) {
        if (!mChannel.isOpen()) return;
        Slog.i(TAG, "removed " + sbn.getPackageName() + " id=" + sbn.getId());
        send(newMessage(NotificationMessage.MessageType.CancelNotification,
                sbn.getPackageName(), sbn.getId(), sbn.getTag()).build());
    }

    private void onToast(Intent intent) {
        String pkg = intent.getStringExtra("package");
        String token = intent.getStringExtra("token");
        if (pkg == null || token == null) return;
        if (!mChannel.isOpen()) {
            Slog.d(TAG, "channel is not open, dropping toast from " + pkg);
            return;
        }
        if (ACTION_TOAST_HIDE.equals(intent.getAction())) {
            Slog.i(TAG, "toast hidden " + pkg);
            send(newMessage(NotificationMessage.MessageType.CancelNotification, pkg, mToastId, token)
                    .build());
            return;
        }
        String text = intent.getStringExtra("text");
        // duration: Toast.LENGTH_SHORT (0) or LENGTH_LONG (1).
        int ms = intent.getIntExtra("duration", 0) == 1 ? TOAST_LONG_MS : TOAST_SHORT_MS;
        String app = appLabel(pkg);
        Slog.i(TAG, "toast " + pkg + " " + ms + "ms");
        send(newMessage(NotificationMessage.MessageType.Toast, pkg, mToastId, token)
                .setToastDurationInMilliseconds(ms)
                .setDetail(NotificationDetail.newBuilder()
                        .setAppName(app)
                        .setTitle(app)
                        .setText(text != null ? text : "")
                        .build())
                .build());
    }

    private void send(NotificationMessage msg) {
        try {
            mChannel.sendAsync(msg.toByteArray());
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, "send failed: " + e.getMessage());
        }
    }

    private NotificationMessage.Builder newMessage(NotificationMessage.MessageType type,
            String pkg, int id, String tag) {
        return NotificationMessage.newBuilder()
                .setMessageType(type)
                .setActivityId(ActivityId.newBuilder()
                        .setValue(UUID.randomUUID().toString()).build())
                .setPackageName(pkg != null ? pkg : "")
                .setId(id)
                .setTag(tag != null ? tag : "");
    }

    private String appLabel(String pkg) {
        if (pkg == null) return "";
        try {
            return mPm.getApplicationLabel(mPm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Slog.w(TAG, "no label for " + pkg + ": " + e);
            return "";
        }
    }

    /** Value of the key as text; if empty, from the fallback key (expanded notification view). */
    private static String text(Bundle extras, String key, String fallbackKey) {
        CharSequence cs = extras.getCharSequence(key);
        if (TextUtils.isEmpty(cs)) cs = extras.getCharSequence(fallbackKey);
        return cs != null ? cs.toString() : "";
    }

    // ---------------------------------------------------------------- images

    private NotificationImage imageOf(Notification n, Bundle extras) {
        NotificationImage.Builder image = NotificationImage.newBuilder();
        boolean any = false;

        // BigPictureStyle. Since Android 13 the picture can be set not as a Bitmap but as an Icon, and then it
        // lives under a different key. Microsoft's reference is built for API 33 and knows only the old one:
        // for an app built for 14, the picture simply never reaches Windows.
        Bitmap picture = extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap.class);
        if (picture == null) {
            Icon pictureIcon = extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon.class);
            picture = bitmapOf(pictureIcon);
        }
        if (picture != null) {
            PNG png = pngOf(picture);
            if (png != null) {
                image.setImage(png);
                any = true;
            }
        }

        // Icon: the expanded view has its own (EXTRA_LARGE_ICON_BIG), otherwise the common one.
        Icon icon = any ? extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG, Icon.class) : null;
        if (icon == null) icon = n.getLargeIcon();
        PNG iconPng = pngOf(bitmapOf(icon));
        if (iconPng != null) {
            image.setIcon(iconPng);
            any = true;
        }
        return any ? image.build() : null;
    }

    private Bitmap bitmapOf(Icon icon) {
        if (icon == null) return null;
        Drawable drawable;
        try {
            drawable = icon.loadDrawable(mContext);
        } catch (RuntimeException e) {
            Slog.w(TAG, "cannot load icon: " + e);
            return null;
        }
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) return bitmap;
        }
        // There may be no dimensions at all (ColorDrawable returns -1), and createBitmap throws on that.
        int w = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : FALLBACK_ICON_PX;
        int h = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : FALLBACK_ICON_PX;
        try {
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, w, h);
            drawable.draw(canvas);
            return bitmap;
        } catch (RuntimeException e) {
            Slog.w(TAG, "cannot draw icon " + w + "x" + h + ": " + e);
            return null;
        }
    }

    private PNG pngOf(Bitmap bitmap) {
        if (bitmap == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                Slog.w(TAG, "cannot encode bitmap " + bitmap.getWidth() + "x" + bitmap.getHeight());
                return null;
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "cannot encode bitmap: " + e);
            return null;
        }
        if (out.size() > MAX_IMAGE_BYTES) {
            Slog.w(TAG, "image is " + out.size() + " bytes, over the " + MAX_IMAGE_BYTES
                    + " limit — sending the notification without it");
            return null;
        }
        return PNG.newBuilder()
                .setWidth(bitmap.getWidth())
                .setHeight(bitmap.getHeight())
                .setFormat(formatOf(bitmap.getConfig()))
                .setBytes(ByteString.copyFrom(out.toByteArray()))
                .setSize(out.size())
                .build();
    }

    private static PNG.Format formatOf(Bitmap.Config config) {
        if (config == null) return PNG.Format.ARGB_8888;
        switch (config) {
            case ALPHA_8: return PNG.Format.ALPHA_8;
            case ARGB_4444: return PNG.Format.ARGB_4444;
            case HARDWARE: return PNG.Format.HARDWARE;
            case RGBA_F16: return PNG.Format.RGBA_F16;
            case RGB_565: return PNG.Format.RGB_565;
            default: return PNG.Format.ARGB_8888;
        }
    }

    // ---------------------------------------------------------------- host → guest

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        NotificationMessage msg;
        try {
            msg = NotificationMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad NotificationMessage: " + e,
                    ResultCode.InvalidProtocolFormat);
        }
        Slog.i(TAG, "host -> " + msg.getMessageType() + " " + msg.getPackageName()
                + " id=" + msg.getId()
                + (msg.getMessageType() == NotificationMessage.MessageType.Activation
                        ? describe(msg.getActivation(), msg.hasActivation()) : ""));
        Result.Builder result = Result.newBuilder();
        try {
            switch (msg.getMessageType()) {
                case Activation:
                    result.setSucceeded(handleActivation(msg));
                    break;
                case Dismissal:
                    result.setSucceeded(handleDismissal(msg));
                    break;
                case ReportNotificationSeenEvent:
                    result.setSucceeded(reportSeen(msg));
                    break;
                default:
                    throw new ApiChannel.ApiException(
                            "unexpected message type " + msg.getMessageType(),
                            ResultCode.InvalidProtocolFormat);
            }
        } catch (ApiChannel.ApiException | RuntimeException e) {
            // The error goes in the result field, not as the frame's return code: the reference does this, and this way
            // the host keeps the text for its log.
            Slog.w(TAG, "request failed: " + e);
            result.setSucceeded(false).setErrorMessage(String.valueOf(e.getMessage()));
        }
        return msg.toBuilder().setResult(result.build()).build().toByteArray();
    }

    /** What exactly was clicked in Windows; without this a button click cannot be told from a body click. */
    private static String describe(NotificationActivation activation, boolean present) {
        if (!present) return " (no activation: body click)";
        return " action=\"" + activation.getAction() + "\""
                + " replyKey=\"" + activation.getReplyKey() + "\""
                + " replyData=\"" + activation.getReplyData() + "\"";
    }

    private boolean handleActivation(NotificationMessage msg) throws ApiChannel.ApiException {
        StatusBarNotification sbn = find(msg);
        if (sbn == null) {
            // The notification already went away; the only sensible action is to open the app.
            Slog.i(TAG, "no such notification, launching " + msg.getPackageName());
            return launch(msg.getPackageName());
        }
        Notification n = sbn.getNotification();
        cancel(sbn);

        PendingIntent pi = n.contentIntent;
        Intent fillIn = null;
        if (msg.hasActivation() && n.actions != null) {
            NotificationActivation activation = msg.getActivation();
            Notification.Action action = findAction(n, activation);
            if (action != null) {
                Slog.i(TAG, "matched action button \"" + action.title + "\"");
                pi = action.actionIntent;
                RemoteInput[] inputs = action.getRemoteInputs();
                if (inputs != null && !activation.getReplyKey().isEmpty()) {
                    Bundle reply = new Bundle();
                    reply.putString(activation.getReplyKey(), activation.getReplyData());
                    fillIn = new Intent();
                    RemoteInput.addResultsToIntent(inputs, fillIn, reply);
                }
            }
        }
        if (pi == null) {
            Slog.i(TAG, "notification has no intent, launching " + msg.getPackageName());
            return launch(msg.getPackageName());
        }
        return fire(pi, fillIn);
    }

    /**
     * Which notification button the host meant.
     *
     * First by the INPUT FIELD KEY, then by title, like the reference. The host puts the title into the toast
     * arguments without escaping and then parses them itself via WwwFormUrlDecoder, so the
     * action field already carries its reading of the label, not the app's original string. The
     * RemoteInput key does not depend on this: it came from the app itself in inputKey and returned
     * back. The host does not deliver a button with a non-ASCII label at all, see docs/wsa-shell-protocol.md,
     * section on notification buttons.
     */
    private static Notification.Action findAction(Notification n, NotificationActivation activation) {
        String replyKey = activation.getReplyKey();
        if (!replyKey.isEmpty()) {
            for (Notification.Action action : n.actions) {
                RemoteInput[] inputs = action != null ? action.getRemoteInputs() : null;
                if (inputs == null) continue;
                for (RemoteInput input : inputs) {
                    if (replyKey.equals(input.getResultKey())) return action;
                }
            }
        }
        String title = activation.getAction();
        if (title.isEmpty()) return null;
        for (Notification.Action action : n.actions) {
            if (action != null && action.title != null && action.title.toString().equals(title)) {
                return action;
            }
        }
        return null;
    }

    /**
     * Send the app's PendingIntent. For broadcast and service intents the reference
     * RE-CREATES the PendingIntent under its own name with the IMMUTABLE flag; first, this loses
     * the app's identity (the intent goes out from uid system), second, an immutable PendingIntent
     * ignores fill-in, so exactly the reply typed by the user never arrives. Here
     * the original one is sent.
     *
     * ActivityOptions are needed because of Android 14: since 14 the PendingIntent sender must
     * explicitly allow a background activity start, otherwise the intent from a notification click silently
     * leads nowhere. The reference is built for 13 and does not know about this.
     */
    private boolean fire(PendingIntent pi, Intent fillIn) {
        Bundle options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle();
        try {
            pi.send(mContext, 0, fillIn, null, null, null, options);
            return true;
        } catch (PendingIntent.CanceledException e) {
            Slog.w(TAG, "pending intent cancelled: " + e.getMessage());
            return false;
        }
    }

    private boolean launch(String pkg) {
        Intent intent = mPm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Slog.e(TAG, "no launch intent for " + pkg);
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            mContext.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            Slog.e(TAG, "cannot launch " + pkg + ": " + e);
            return false;
        }
    }

    private boolean handleDismissal(NotificationMessage msg) throws ApiChannel.ApiException {
        StatusBarNotification sbn = find(msg);
        if (sbn == null) return false;
        cancel(sbn);
        return true;
    }

    /**
     * Tell the system the notification was shown to the user: without this Android considers it
     * unseen and, for example, does not clear the status bar icon and keeps different accounting in
     * NotificationManagerService. Allowed by the STATUS_BAR_SERVICE permission (signature level:
     * the platform signature is enough, no privapp-allowlist entry needed).
     */
    private boolean reportSeen(NotificationMessage msg) throws ApiChannel.ApiException {
        // The host also confirms a Toast was shown, but NotificationManager has no record of it: nothing to mark.
        if (msg.getId() == mToastId) return true;
        StatusBarNotification sbn = find(msg);
        if (sbn == null) return false;
        IStatusBarService statusBar = statusBar();
        if (statusBar == null) return false;
        NotificationVisibility visible = NotificationVisibility.obtain(sbn.getKey(), 0, 1, true,
                NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA);
        try {
            statusBar.onNotificationVisibilityChanged(
                    new NotificationVisibility[] { visible }, new NotificationVisibility[0]);
            return true;
        } catch (RemoteException e) {
            Slog.w(TAG, "onNotificationVisibilityChanged failed: " + e);
            mStatusBar = null;
            return false;
        }
    }

    private IStatusBarService statusBar() {
        IStatusBarService statusBar = mStatusBar;
        if (statusBar == null) {
            IBinder binder = ServiceManager.getService(Context.STATUS_BAR_SERVICE);
            if (binder == null) {
                Slog.w(TAG, "statusbar service is not up");
                return null;
            }
            statusBar = IStatusBarService.Stub.asInterface(binder);
            mStatusBar = statusBar;
        }
        return statusBar;
    }

    /** Find a notification by the triple (package, id, tag), which travels over the channel instead of the key. */
    private StatusBarNotification find(NotificationMessage msg) throws ApiChannel.ApiException {
        Listener listener = mListener;
        if (listener == null) {
            throw new ApiChannel.ApiException("notification listener is not registered",
                    ResultCode.NotReady);
        }
        StatusBarNotification[] active;
        try {
            active = listener.getActiveNotifications();
        } catch (RuntimeException e) {
            throw new ApiChannel.ApiException("getActiveNotifications failed: " + e,
                    ResultCode.Fail);
        }
        if (active == null) return null;
        for (StatusBarNotification sbn : active) {
            String tag = sbn.getTag() != null ? sbn.getTag() : "";
            if (sbn.getId() == msg.getId()
                    && tag.equals(msg.getTag())
                    && sbn.getPackageName().equalsIgnoreCase(msg.getPackageName())) {
                return sbn;
            }
        }
        Slog.i(TAG, "notification not found: " + msg.getPackageName() + " id=" + msg.getId()
                + " tag=" + msg.getTag());
        return null;
    }

    private void cancel(StatusBarNotification sbn) {
        Listener listener = mListener;
        if (listener != null) listener.cancelNotification(sbn.getKey());
    }
}
