# NotifTest — test app for the "Notification" channel

Posts notifications of every kind the channel can carry to the Windows notification center, and logs to `logcat` (tag `NotifTest`) what came back: a tap on the notification body, a button press, a reply typed in Windows.

```bash
bin/build-testapp.sh testapps/NotifTest --install
adb shell pm grant org.wsalineage.notiftest android.permission.POST_NOTIFICATIONS
adb shell am start -n org.wsalineage.notiftest/.MainActivity -e kind all
adb logcat -s NotifTest WsaShellBridge.Notification
adb shell am start -n org.wsalineage.notiftest/.MainActivity -e cancel 1
```

Kinds (`-e kind`): `simple`, `styled` (styled text — Microsoft's reference sends an empty one for it), `actions` (two buttons), `reply` (input line), `progress` (progress bar), `picture` and `pictureIcon` (`BigPictureStyle` the old way and the new way introduced in Android 13), `icon` (large icon), `all` — all at once, eight of them with `id` 1–8.

Since Android 13 the `POST_NOTIFICATIONS` permission is requested from the user with a dialog; the test app has no window, so it is granted via `pm grant`.
