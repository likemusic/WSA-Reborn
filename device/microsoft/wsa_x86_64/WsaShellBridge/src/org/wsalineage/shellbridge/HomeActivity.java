/*
 * An empty home screen — the counterpart of HomeApp from the Microsoft image.
 *
 * In WSA Android has no desktop of its own: apps are launched from the Windows Start menu, each in
 * its own window. A full-screen Launcher3 with a wallpaper under the windows gets in the way: the host shows the shared
 * display frame in a window that left full-screen mode (F11), and the wallpaper ends up there; clicks outside
 * the app window go to the launcher. Microsoft has an empty HOME activity instead of a launcher.
 * Analysis — docs/plan-20260921.md, §1. It is made the default home by DefaultHome (HOME role,
 * once); to bring Launcher3 back — cmd role add-role-holder android.app.role.HOME com.android.launcher3 0.
 */
package org.wsalineage.shellbridge;

import android.app.Activity;

public final class HomeActivity extends Activity {
}
