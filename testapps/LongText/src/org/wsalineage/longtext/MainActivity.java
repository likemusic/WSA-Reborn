package org.wsalineage.longtext;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Screen reader test app: three buttons and a long (focusable) paragraph below them.
 * Checks TalkBack speech interruption: focus the paragraph, then immediately a button — speech about the paragraph should
 * be cut off. Focus via the Tab key (adb shell input keyevent TAB) or by hovering the mouse.
 *
 *   am start -S -n org.wsalineage.longtext/.MainActivity
 */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView para = new TextView(this);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            sb.append("Sentence number ").append(i)
              .append(" of a long paragraph for checking whether the screen reader interrupts reading when moving to another element. ");
        }
        para.setText(sb);
        para.setFocusable(true);
        para.setTextSize(18);
        // Buttons go above the paragraph: the paragraph is long and pushes everything below it past the window edge.
        for (String s : new String[] {"First button", "Second button", "Third button"}) {
            Button b = new Button(this);
            b.setText(s);
            root.addView(b);
        }
        root.addView(para);
        setContentView(root);
    }
}
