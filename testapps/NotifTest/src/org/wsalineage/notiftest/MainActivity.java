package org.wsalineage.notiftest;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.Html;
import android.util.Log;
import android.widget.Toast;

/**
 * Test app for the "Notification" channel: posts notifications of every kind the channel
 * can carry, and logs to logcat what came back from Windows.
 *
 *   am start -n org.wsalineage.notiftest/.MainActivity -e kind simple
 *   ... kind = simple | styled | actions | reply | progress | picture | pictureIcon | icon | all
 *   am start ... -e kind reply -e button Reply
 *                                — reply button label (default "Ответить"). The host does not escape
 *                                  the label in the toast arguments, and a button with a non-ASCII label is
 *                                  dead in Windows — see docs/wsa-shell-protocol.md, notification buttons section
 *   am start ... -e cancel 1     — remove all posted notifications
 *   am start ... -e kind toast [-e text '…'] [-e long 1]
 *                                — a popup message (Toast); in Windows it is shown by
 *                                  SystemUI with patch 0012 via the bridge, see docs/wsa-shell-protocol.md
 *
 * Since Android 13 the notification permission is requested from the user, and we have no window:
 *   pm grant org.wsalineage.notiftest android.permission.POST_NOTIFICATIONS
 */
public final class MainActivity extends Activity {
    private static final String TAG = ActionReceiver.TAG;
    private static final String CHANNEL_ID = "notiftest";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        int opened = intent.getIntExtra("opened", -1);
        if (opened >= 0) {
            Log.i(TAG, "CONTENT INTENT FIRED: id=" + opened);
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "NotifTest", NotificationManager.IMPORTANCE_HIGH));

        if (intent.getStringExtra("cancel") != null) {
            nm.cancelAll();
            Log.i(TAG, "cancelled all");
            return;
        }
        String kind = intent.getStringExtra("kind");
        if (kind == null) kind = "simple";
        if ("toast".equals(kind)) {
            String text = intent.getStringExtra("text");
            boolean isLong = intent.getStringExtra("long") != null;
            Toast.makeText(this, text != null ? text : "Всплывающее сообщение из Android",
                    isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            Log.i(TAG, "toast shown, long=" + isLong);
            return;
        }
        boolean all = "all".equals(kind);
        if (all || "simple".equals(kind)) post(nm, 1, simple());
        if (all || "styled".equals(kind)) post(nm, 2, styled());
        if (all || "actions".equals(kind)) post(nm, 3, actions());
        if (all || "reply".equals(kind)) post(nm, 4, reply(intent.getStringExtra("button")));
        if (all || "progress".equals(kind)) post(nm, 5, progress());
        if (all || "picture".equals(kind)) post(nm, 6, picture(false));
        if (all || "pictureIcon".equals(kind)) post(nm, 7, picture(true));
        if (all || "icon".equals(kind)) post(nm, 8, largeIcon());
    }

    private void post(NotificationManager nm, int id, Notification.Builder b) {
        // A distinct action per notification and SINGLE_TOP — otherwise the intent does NOT reach
        // the app: Intent.filterEquals ignores extras, and the system, seeing "the same"
        // intent, just brings the task to the front without calling onCreate or onNewIntent.
        Intent open = new Intent(this, MainActivity.class)
                .setAction("open-" + id)
                .putExtra("opened", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setContentIntent(PendingIntent.getActivity(this, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        nm.notify(id, b.build());
        Log.i(TAG, "posted id=" + id);
    }

    private Notification.Builder base(String title, String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(Icon.createWithBitmap(square(48, Color.rgb(0, 120, 215))))
                .setContentTitle(title)
                .setContentText(text);
    }

    private Notification.Builder simple() {
        return base("Проверка связи", "Обычное уведомление, только текст");
    }

    /** Styled title and text: at Microsoft these arrive in Windows empty. */
    private Notification.Builder styled() {
        return base("", "").setContentTitle(Html.fromHtml("<b>Жирный</b> заголовок",
                        Html.FROM_HTML_MODE_COMPACT))
                .setContentText(Html.fromHtml("текст <i>курсивом</i>", Html.FROM_HTML_MODE_COMPACT));
    }

    private Notification.Builder actions() {
        return base("Две кнопки", "Нажми любую в Windows")
                .addAction(action("Принять"))
                .addAction(action("Отклонить"));
    }

    private Notification.Builder reply(String button) {
        if (button == null) button = "Ответить";
        RemoteInput input = new RemoteInput.Builder(ActionReceiver.REPLY_KEY)
                .setLabel(button).build();
        Notification.Action a = new Notification.Action.Builder(
                Icon.createWithBitmap(square(24, Color.GRAY)), button, broadcast(button))
                .addRemoteInput(input)
                .build();
        return base("Строка ввода", "Ответь прямо из Windows").addAction(a);
    }

    private Notification.Builder progress() {
        return base("Загрузка", "42 из 100").setProgress(100, 42, false);
    }

    private Notification.Builder picture(boolean asIcon) {
        Bitmap big = gradient(320, 180);
        Notification.BigPictureStyle style = new Notification.BigPictureStyle();
        // Two ways to set the picture. The second appeared in Android 13 and puts it under a different key —
        // that is exactly where Microsoft's reference, built for API 33, loses the picture.
        if (asIcon) style.bigPicture(Icon.createWithBitmap(big)); else style.bigPicture(big);
        return base(asIcon ? "Картинка (Icon)" : "Картинка (Bitmap)", "BigPictureStyle")
                .setLargeIcon(square(96, Color.rgb(200, 60, 60)))
                .setStyle(style);
    }

    private Notification.Builder largeIcon() {
        return base("Большой значок", "setLargeIcon без картинки")
                .setLargeIcon(square(96, Color.rgb(60, 160, 60)));
    }

    private Notification.Action action(String title) {
        return new Notification.Action.Builder(
                Icon.createWithBitmap(square(24, Color.GRAY)), title, broadcast(title)).build();
    }

    private PendingIntent broadcast(String title) {
        Intent intent = new Intent(this, ActionReceiver.class)
                .setPackage(getPackageName())
                .putExtra("action", title);
        // Mutable: otherwise the reply typed in Windows is not filled into the intent.
        return PendingIntent.getBroadcast(this, title.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    private static Bitmap square(int size, int color) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawColor(color);
        return bitmap;
    }

    private static Bitmap gradient(int w, int h) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        for (int x = 0; x < w; x++) {
            paint.setColor(Color.rgb(x * 255 / w, 80, 255 - x * 255 / w));
            canvas.drawRect(x, 0, x + 1, h, paint);
        }
        return bitmap;
    }
}
