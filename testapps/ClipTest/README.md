# ClipTest — a test app for the "Clipboard" channel

Twenty lines that do what cannot be done from `adb shell`: put text (or HTML) into the Android clipboard and print to `logcat` everything the clipboard holds — the `ClipData` label, mime types, text, HTML length, the `content://` link and the size of what can be read through it.

It is needed because `ClipboardService` in Android 14 has no shell command (checked in `frameworks/base/services/core/java/com/android/server/clipboard/ClipboardService.java` in the `android-14.0.0_r75` tree), and reading the clipboard is allowed only to the app "in focus" — that is, with a screen, not from `adb shell`. Writing, on the contrary, is allowed to anyone: in `clipboardAccessAllowed` the `OP_WRITE_CLIPBOARD` branch is an unconditional `allowed = true`.

## Build and install

```bash
bin/build-testapp.sh testapps/ClipTest --install
```

No AOSP tree needed: it is built with SDK tools in a couple of seconds (details and pitfalls — in the header of `bin/build-testapp.sh`).

## Usage

```bash
adb shell am force-stop org.wsalineage.cliptest     # otherwise am delivers the intent to onNewIntent of the live instance
adb shell am start -n org.wsalineage.cliptest/.MainActivity -e set "текст"     # put into the clipboard (Cyrillic on purpose)
adb shell am start -n org.wsalineage.cliptest/.MainActivity                    # just show the clipboard
adb shell am start -n org.wsalineage.cliptest/.MainActivity -e html '<b>жирный</b>'
adb logcat -d -s ClipTest:*
```

On the Windows side the other half of the experiment is `Get-Clipboard` and `Set-Clipboard` in PowerShell.
