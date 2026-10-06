/*
 * Channel "TSF": text in Android fields is entered by the Windows text service.
 *
 * Keys over the "UserInput" channel arrive as VK codes and are mapped in the guest with the US layout
 * — a Russian Windows layout yields Latin letters, composition input (IME), the Win+. emoji panel and
 * Win+H dictation do not work at all. So text takes a different path: the guest keeps a mirror of the
 * focused field at the host, the host attaches a TSF text store to it and sends the edits made by
 * Windows. On the Android side this is a regular input method (WindowsInputMethod), only its "keyboard"
 * is Windows.
 *
 * At Microsoft the same is done by TsfRedirectionHandler and MicrosoftInputMethod from UserApp.apk; this code is our own.
 * Differences from the reference are intentional:
 *   * all work with the field happens on one dedicated thread, not partly on the channel receive thread and
 *     partly on the input method thread — the order of host edits and field events is preserved by itself;
 *   * the echo of our own edits is filtered by comparison with the mirror, not by counters of "how many updates
 *     to skip": the reference's counters drift if the field sends one update fewer;
 *   * a field change always closes the previous session and opens a new one; in the reference a second field without
 *     an intermediate onWindowHidden is skipped ("an active session has started").
 * Protocol analysis — docs/protocol.md, section "TSF".
 */
package org.wsalineage.shellbridge;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.provider.Settings;
import android.os.HandlerThread;
import android.text.InputType;
import android.util.Slog;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import com.google.protobuf.InvalidProtocolBufferException;

import org.wsalineage.shellbridge.proto.ResultCodes.ResultCode;
import org.wsalineage.shellbridge.proto.Tsf.ClientText;
import org.wsalineage.shellbridge.proto.Tsf.CursorLocation;
import org.wsalineage.shellbridge.proto.Tsf.FocusRequest;
import org.wsalineage.shellbridge.proto.Tsf.RemoteText;
import org.wsalineage.shellbridge.proto.Tsf.Selection;
import org.wsalineage.shellbridge.proto.Tsf.TextRange;
import org.wsalineage.shellbridge.proto.Tsf.TsfMessage;

public final class TsfBridge implements ApiChannel.Handler {
    private static final String TAG = "WsaShellBridge.TSF";
    private static final String CHANNEL = "TSF";
    private static final long RECONNECT_MS = 2_000;

    /** How much text on each side of the cursor we give the host if the field does not expose all of it. */
    private static final int AROUND_CURSOR = 10_000;

    // Windows InputScope (inputscope.h) — determines the touch keyboard layout and suggestions.
    private static final int IS_DEFAULT = 0;
    private static final int IS_URL = 1;
    private static final int IS_EMAIL_SMTPEMAILADDRESS = 5;
    private static final int IS_PERSONALNAME_FULLNAME = 7;
    private static final int IS_ADDRESS_FULLPOSTALADDRESS = 13;
    private static final int IS_NUMBER = 29;
    private static final int IS_PASSWORD = 31;
    private static final int IS_TELEPHONE_FULLTELEPHONENUMBER = 32;
    private static final int IS_TEXT = 57;
    private static final int IS_NUMERIC_PASSWORD = 63;

    private final Context mContext;
    private final Handler mH;
    private final ApiChannel mChannel = new ApiChannel(CHANNEL, this);
    /** Everything touching the input field or the mirror — only on this thread. */
    private final Handler mWork;

    private static volatile TsfBridge sInstance;

    // ---- session: the focused field (thread mWork)
    private InputConnection mIc;
    private int mControlId;
    private boolean mSession;
    private boolean mComposing;
    /** The field does not expose all text — the mirror holds only the area around the cursor. */
    private boolean mPartial;
    // mirror: what, as far as we know, the host currently has
    private String mText = "";
    private int mSelStart;
    private int mSelEnd;

    public TsfBridge(Context context, Handler handler) {
        mContext = context;
        mH = handler;
        HandlerThread t = new HandlerThread("WsaShellBridge.TSF");
        t.start();
        mWork = t.getThreadHandler();
        sInstance = this;
    }

    /** For WindowsInputMethod: the input method lives in the same process as the bridge. */
    static TsfBridge get() {
        return sInstance;
    }

    public void start() {
        mH.post(() -> {
            makeDefault();
            connect();
        });
    }

    /**
     * Make WindowsInputMethod the default input method. Other enabled input methods
     * stay enabled — one can switch to them, but text in the Windows layout, IME and
     * emoji will work only with ours.
     */
    private void makeDefault() {
        String ours = new ComponentName(mContext, WindowsInputMethod.class).flattenToShortString();
        String enabled = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS);
        StringBuilder list = new StringBuilder(ours);
        if (enabled != null) {
            for (String id : enabled.split(":")) {
                if (!id.isEmpty() && !id.equals(ours)) list.append(':').append(id);
            }
        }
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS, list.toString());
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD, ours);
        Slog.i(TAG, "default input method: " + ours);
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
        // The host forgot the field together with the channel; after a reconnect the session starts with
        // the next focus change.
        mWork.post(() -> {
            mSession = false;
            mComposing = false;
        });
        mH.postDelayed(this::connect, RECONNECT_MS);
    }

    // ---------------------------------------------------------------- input method -> host

    /** Focus entered an input field. Called from WindowsInputMethod.onStartInput. */
    void startInput(InputConnection ic, EditorInfo info) {
        final int inputType = info.inputType;
        final boolean editable = info.initialSelStart >= 0 && info.initialSelEnd >= 0
                && inputType != InputType.TYPE_NULL;
        mWork.post(() -> {
            endSessionLocked();
            if (!editable || ic == null) return;
            mIc = ic;
            mControlId++;
            mComposing = false;
            Field f = readField(true);
            if (f == null) {
                Slog.w(TAG, "focus: cannot read the field, no session");
                return;
            }
            mText = f.text;
            mSelStart = f.selStart;
            mSelEnd = f.selEnd;
            boolean multiLine = (inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                    && (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
            FocusRequest focus = FocusRequest.newBuilder()
                    .setControlId(mControlId)
                    .setInputScope(inputScope(inputType))
                    .setMultiLine(multiLine)
                    .setPartialText(mPartial)
                    .setClientText(ClientText.newBuilder()
                            .setControlId(mControlId)
                            .setText(toWindows(mText))
                            .setSelection(range(mSelStart, mSelEnd)))
                    .build();
            if (send(TsfMessage.newBuilder()
                    .setMessageType(TsfMessage.MessageType.SetClientFocus)
                    .setClientFocus(focus))) {
                mSession = true;
                Slog.i(TAG, "focus on control " + mControlId + " scope=" + focus.getInputScope()
                        + " len=" + mText.length() + (mPartial ? " (partial)" : ""));
                ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE
                        | InputConnection.CURSOR_UPDATE_MONITOR);
            }
        });
    }

    /** Focus left the field. */
    void finishInput() {
        mWork.post(this::endSessionLocked);
    }

    private void endSessionLocked() {
        if (!mSession) return;
        mSession = false;
        mComposing = false;
        send(TsfMessage.newBuilder()
                .setMessageType(TsfMessage.MessageType.SetClientFocus)
                .setClientFocus(FocusRequest.newBuilder().setControlId(mControlId)));
        Slog.i(TAG, "focus off control " + mControlId);
        mIc = null;
    }

    /** The field's text or selection changed (app typing, paste, Backspace, arrows). */
    void fieldChanged() {
        mWork.post(this::syncFromField);
    }

    /** Cursor on the guest screen — Windows places the candidate window and emoji panel there. */
    void cursorMoved(float x, float y) {
        mWork.post(() -> {
            if (!mSession) return;
            send(TsfMessage.newBuilder()
                    .setMessageType(TsfMessage.MessageType.SetClientCursorLocation)
                    .setCursorLocation(CursorLocation.newBuilder().setX(x).setY(y)));
        });
    }

    /**
     * Run an action on the field on the channel thread — so it does not overtake host edits that arrived
     * earlier (Backspace right after a typed letter).
     */
    void runInOrder(Runnable r) {
        mWork.post(r);
    }

    boolean inSession() {
        return mSession;
    }

    /** Compare the field with the mirror and send the difference to the host. Left alone during composition. */
    private void syncFromField() {
        if (!mSession || mComposing) return;
        Field f = readField(false);
        if (f == null) return;
        if (!f.text.equals(mText)) {
            mText = f.text;
            mSelStart = f.selStart;
            mSelEnd = f.selEnd;
            send(TsfMessage.newBuilder()
                    .setMessageType(TsfMessage.MessageType.SetClientText)
                    .setClientText(ClientText.newBuilder()
                            .setControlId(mControlId)
                            .setText(toWindows(mText))
                            .setSelection(range(mSelStart, mSelEnd))));
        } else if (f.selStart != mSelStart || f.selEnd != mSelEnd) {
            mSelStart = f.selStart;
            mSelEnd = f.selEnd;
            send(TsfMessage.newBuilder()
                    .setMessageType(TsfMessage.MessageType.SetClientSelection)
                    .setClientSelection(Selection.newBuilder().setRange(range(mSelStart, mSelEnd))));
        }
    }

    // ---------------------------------------------------------------- host -> field

    @Override
    public byte[] onRequest(byte[] payload) throws ApiChannel.ApiException {
        TsfMessage msg;
        try {
            msg = TsfMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new ApiChannel.ApiException("bad TsfMessage: " + e, ResultCode.InvalidProtocolFormat);
        }
        // Reply to the host is an empty frame right away; the edit is applied on our own thread. The host does not
        // parse the reply, and there is no reason to wait for the field (it answers from the app's main thread).
        mWork.post(() -> apply(msg));
        return null;
    }

    private void apply(TsfMessage msg) {
        Slog.d(TAG, "host -> " + describe(msg));
        if (!mSession) {
            Slog.d(TAG, "host -> " + msg.getMessageType() + " without a session, ignored");
            return;
        }
        try {
            switch (msg.getMessageType()) {
                case SetRemoteText:
                    if (msg.hasRemoteText()) replaceText(msg.getRemoteText());
                    break;
                case SetRemoteSelection:
                    if (msg.hasRemoteSelection()) {
                        TextRange r = msg.getRemoteSelection().getRange();
                        int b = clamp(r.getBegin(), mText.length());
                        int e = clamp(r.hasEnd() ? r.getEnd() : b, mText.length());
                        check(mIc.setSelection(b, e), "setSelection");
                        mSelStart = b;
                        mSelEnd = e;
                    }
                    break;
                case TagRemoteText:
                    if (msg.hasTagRemoteText() && mComposing) {
                        TextRange r = msg.getTagRemoteText().getRange();
                        check(mIc.setComposingRegion(r.getBegin(), r.hasEnd() ? r.getEnd() : r.getBegin()),
                                "setComposingRegion");
                    }
                    break;
                case SetRemoteCompositionState:
                    boolean composing = msg.getCompositionState().getState() != 0;
                    if (mComposing && !composing) {
                        mComposing = false;
                        check(mIc.finishComposingText(), "finishComposingText");
                        syncFromField();
                    }
                    mComposing = composing;
                    break;
                case RemoteKeyboard:
                    syncFromField();
                    break;
                default:
                    Slog.w(TAG, "host -> unexpected " + msg.getMessageType());
            }
        } catch (IllegalStateException e) {
            // The field is gone (the app closed the connection) — the host learns about it with the focus change.
            Slog.w(TAG, "host -> " + msg.getMessageType() + ": " + e.getMessage());
        }
    }

    /**
     * Replace a part of the field with text from the host. Without replacement — insert into the current selection
     * (this is how the host sends regular typing). Empty text into an empty selection means Backspace.
     */
    private void replaceText(RemoteText rt) {
        if (rt.getControlId() != mControlId) {
            Slog.d(TAG, "SetRemoteText for stale control " + rt.getControlId());
            return;
        }
        Field f = readField(false);
        if (f == null) throw new IllegalStateException("cannot read the field");
        int len = f.text.length();
        int b, e;
        if (rt.hasReplacement()) {
            b = clamp(rt.getReplacement().getBegin(), len);
            e = clamp(rt.getReplacement().hasEnd() ? rt.getReplacement().getEnd() : b, len);
        } else {
            b = f.selStart;
            e = f.selEnd;
        }
        if (e < b) e = b;
        String text = toAndroid(rt.getText());

        check(mIc.beginBatchEdit(), "beginBatchEdit");
        try {
            check(mIc.finishComposingText(), "finishComposingText");
            check(mIc.setSelection(b, e), "setSelection");
            if (!text.isEmpty()) {
                check(mIc.commitText(text, 1), "commitText");
            } else if (b == e) {
                check(mIc.deleteSurroundingText(1, 0), "deleteSurroundingText");
            } else {
                check(mIc.commitText("", 1), "commitText");
            }
        } finally {
            mIc.endBatchEdit();
        }

        // The mirror is what the host expects; if the field did its own thing (input filter, maxLength),
        // outside composition we immediately send the host what actually came out.
        mText = f.text.substring(0, b) + text + f.text.substring(e);
        mSelStart = mSelEnd = b + text.length();
        if (!rt.hasReplacement()) {
            // An insert "into the current selection" is not added to the host's store — it waits for the guest
            // to report where it landed. Without this the host considers the field empty, and the next edit with
            // a range (emoji from Win+.) lands at the start of the field: caught on the first run.
            send(TsfMessage.newBuilder()
                    .setMessageType(TsfMessage.MessageType.SetClientText)
                    .setClientText(ClientText.newBuilder()
                            .setControlId(mControlId)
                            .setReplacement(range(b, e))
                            .setText(toWindows(text))
                            .setSelection(range(mSelStart, mSelEnd))));
        }
        if (!mComposing) syncFromField();
    }

    // ---------------------------------------------------------------- field

    private static final class Field {
        String text = "";
        int selStart;
        int selEnd;
    }

    private Field readField(boolean newControl) {
        InputConnection ic = mIc;
        if (ic == null) return null;
        Field f = new Field();
        if (newControl || !mPartial) {
            ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            mPartial = et == null || et.text == null;
            if (!mPartial) {
                f.text = et.text.toString();
                f.selStart = et.startOffset + et.selectionStart;
                f.selEnd = et.startOffset + et.selectionEnd;
            }
        }
        if (mPartial) {
            CharSequence before = ic.getTextBeforeCursor(AROUND_CURSOR, 0);
            CharSequence after = ic.getTextAfterCursor(AROUND_CURSOR, 0);
            CharSequence selected = ic.getSelectedText(0);
            String b = before != null ? before.toString() : "";
            f.text = b + (after != null ? after.toString() : "");
            f.selStart = b.length();
            f.selEnd = f.selStart + (selected != null ? selected.length() : 0);
        }
        f.selStart = clamp(f.selStart, f.text.length());
        f.selEnd = Math.max(f.selStart, clamp(f.selEnd, f.text.length()));
        return f;
    }

    // ---------------------------------------------------------------- odds and ends

    /** For the log: type, ranges and text LENGTH — the text itself (may be a password) is not written. */
    private static String describe(TsfMessage m) {
        StringBuilder b = new StringBuilder(m.getMessageType().name());
        if (m.hasRemoteText()) {
            RemoteText r = m.getRemoteText();
            b.append(" control=").append(r.getControlId()).append(" len=").append(r.getText().length());
            if (r.hasReplacement()) b.append(" repl=").append(str(r.getReplacement()));
            if (r.hasSelection()) b.append(" sel=").append(str(r.getSelection()));
        }
        if (m.hasRemoteSelection()) b.append(' ').append(str(m.getRemoteSelection().getRange()));
        if (m.hasTagRemoteText()) b.append(' ').append(str(m.getTagRemoteText().getRange()));
        if (m.hasCompositionState()) b.append(" state=").append(m.getCompositionState().getState());
        if (m.hasRemoteKey()) b.append(" vk=").append(m.getRemoteKey().getKeyCode())
                .append(m.getRemoteKey().getKeyDown() ? " down" : " up");
        return b.toString();
    }

    private static String str(TextRange r) {
        return "[" + r.getBegin() + (r.hasEnd() ? "," + r.getEnd() : "") + ")";
    }

    private boolean send(TsfMessage.Builder msg) {
        if (!mChannel.isOpen()) return false;
        try {
            mChannel.sendAsync(msg.build().toByteArray());
            return true;
        } catch (ApiChannel.ApiException e) {
            Slog.w(TAG, msg.getMessageType() + " send failed: " + e.getMessage());
            return false;
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException(what + " failed: input connection is gone");
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : Math.min(v, max);
    }

    private static TextRange range(int b, int e) {
        return TextRange.newBuilder().setBegin(b).setEnd(e).build();
    }

    // Line break: '\n' on Android, '\r' in Windows fields — same length, ranges do not shift.
    private static String toWindows(String s) {
        return s.replace('\n', '\r');
    }

    private static String toAndroid(String s) {
        return s.replace('\r', '\n');
    }

    /** Android field type -> Windows InputScope. */
    static int inputScope(int inputType) {
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        switch (inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
                return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        ? IS_NUMERIC_PASSWORD : IS_NUMBER;
            case InputType.TYPE_CLASS_PHONE:
                return IS_TELEPHONE_FULLTELEPHONENUMBER;
            case InputType.TYPE_CLASS_TEXT:
                switch (variation) {
                    case InputType.TYPE_TEXT_VARIATION_PASSWORD:
                    case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
                    case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                        return IS_PASSWORD;
                    case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
                    case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
                        return IS_EMAIL_SMTPEMAILADDRESS;
                    case InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS:
                        return IS_ADDRESS_FULLPOSTALADDRESS;
                    case InputType.TYPE_TEXT_VARIATION_URI:
                        return IS_URL;
                    case InputType.TYPE_TEXT_VARIATION_PERSON_NAME:
                        return IS_PERSONALNAME_FULLNAME;
                    default:
                        return IS_TEXT;
                }
            default:
                return IS_DEFAULT;
        }
    }
}
