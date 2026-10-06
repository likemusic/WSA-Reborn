/*
 * An Android input method whose "keyboard" is Windows ("TSF" channel, see TsfBridge).
 *
 * It shows no window of its own: the user does not see the guest screen, and the touch keyboard, the
 * IME candidate window and the emoji panel are drawn by Windows at its own window. The input method's job is to tell
 * the bridge which field has focus and to pass on changes to that field. The bridge makes it the default
 * input method at startup (TsfBridge.makeDefault).
 *
 * At Microsoft — MicrosoftInputMethod from UserApp.apk; this code is our own.
 */
package org.wsalineage.shellbridge;

import android.graphics.Matrix;
import android.inputmethodservice.InputMethodService;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputConnection;

public final class WindowsInputMethod extends InputMethodService {
    private static final String TAG = "WsaShellBridge.TSF";

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        // There is no fullscreen extracted-text mode: there is nobody to show it to.
        info.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        super.onStartInput(info, restarting);
        TsfBridge tsf = TsfBridge.get();
        if (tsf == null) {
            Slog.w(TAG, "TSF bridge is not running");
            return;
        }
        tsf.startInput(getCurrentInputConnection(), info);
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        TsfBridge tsf = TsfBridge.get();
        if (tsf != null) tsf.finishInput();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        TsfBridge tsf = TsfBridge.get();
        if (tsf != null) tsf.fieldChanged();
    }

    @Override
    public void onUpdateExtractedText(int token, ExtractedText text) {
        super.onUpdateExtractedText(token, text);
        TsfBridge tsf = TsfBridge.get();
        if (tsf != null) tsf.fieldChanged();
    }

    @Override
    public void onUpdateCursorAnchorInfo(CursorAnchorInfo info) {
        float x = info.getInsertionMarkerHorizontal();
        float y = info.getInsertionMarkerBottom();
        TsfBridge tsf = TsfBridge.get();
        if (tsf == null || Float.isNaN(x) || Float.isNaN(y)) return;
        // Cursor coordinates are in the field's coordinate system; the matrix converts them to screen ones.
        float[] p = {x, y};
        Matrix m = info.getMatrix();
        m.mapPoints(p);
        tsf.cursorMoved(p[0], p[1]);
    }

    /**
     * Backspace arrives as a key over the "UserInput" channel, while the text typed before it arrives as an edit over
     * the "TSF" channel. If the key were passed to the app immediately, it could overtake a not yet applied
     * letter and erase the wrong thing. So within a session Backspace is executed in the channel queue.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        TsfBridge tsf = TsfBridge.get();
        if (keyCode == KeyEvent.KEYCODE_DEL && tsf != null && tsf.inSession()) {
            tsf.runInOrder(() -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        TsfBridge tsf = TsfBridge.get();
        if (keyCode == KeyEvent.KEYCODE_DEL && tsf != null && tsf.inSession()) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        // There is no Android on-screen keyboard — Windows plays its role.
        return false;
    }
}
