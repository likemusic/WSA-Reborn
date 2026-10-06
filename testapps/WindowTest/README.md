# WindowTest — test app for the window loose ends

Checks the items of plan §1 "Verify on a live WSA": minimizing and focus, PiP, fullscreen mode, placement of new windows in the host work area. Everything that happens to the window (lifecycle, `topResumed`, window focus, bounds, PiP) is written to `logcat` with the tag `WindowTest` and duplicated as a line in the window itself.

```bash
bin/build-testapp.sh testapps/WindowTest --install
adb logcat -s WindowTest WsaShellBridge
adb shell am start -n org.wsalineage.windowtest/.MainActivity -e do <action>
```

Actions (`-e do` or a button in the window): `pip` — enter picture-in-picture 16:9; `immersive`/`restore` — hide/restore the system bars; `back` — `moveTaskToBack`, the bridge should send the host `MinimizeClientWindow`; `newtask` — a second task of the same app; `big` — a task with `<layout>` 2000×1250 dp, larger than the work area, the bridge should maximize it; `tall` — 600×1270 dp, taller than the work area, the bridge should shrink and move it.

What to compare: the bridge lines `CreateTopLevelWindow task N … bounds=…` and `task N: fit A -> B`, the bounds in the `WindowTest` lines and the window on the desktop (`bin/wsa-window-diag.sh`).
