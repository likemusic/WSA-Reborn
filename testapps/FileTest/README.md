# FileTest — test app for the "FileSystem" channel

Hands its own file to the bridge "to Windows", prints files that came from Windows through the clipboard, and accepts "Share" from Windows. Everything is logged to `logcat` with the tag `FileTest`: type, name and size from the provider, number of bytes read, SHA-256 and the beginning of the text.

```bash
bin/build-testapp.sh testapps/FileTest --install
adb logcat -s FileTest WsaShellBridge.FileSystem WsaShellBridge.Clipboard

# Android → Windows: the file lands in the Windows user's Downloads and opens there
adb shell am start -S -n org.wsalineage.filetest/.MainActivity -e kind send
adb shell am start -S -n org.wsalineage.filetest/.MainActivity -e kind send -e name big.bin --el size 10000003 -e how send

# Windows → Android: first copy a file in Windows (Ctrl+C in Explorer or Set-Clipboard -Path <file>)
adb shell am start -S -n org.wsalineage.filetest/.MainActivity -e kind paste
```

`send` parameters: `-e name` — file name (default `wsa-filetest.txt`), `--el size` — size in bytes (otherwise a short text with Cyrillic), `-e how send` — via `ACTION_SEND` instead of `ACTION_VIEW`, `--ez grant false` — do not grant the bridge read access.

The files live in the app's own non-public provider (`org.wsalineage.filetest.files`); read access is passed by an intent flag — just like a regular app with `FileProvider`. The bridge checks that the caller can read the file itself; to see the refusal, it is enough to ask the bridge from `shell`:

```bash
adb shell am start -a android.intent.action.VIEW -t text/plain \
    -d content://org.wsalineage.filetest.files/wsa-filetest.txt \
    -n org.wsalineage.shellbridge/.SendToWindowsActivity
```

`paste` opens a window: since Android 10 the clipboard can be read only while focused.
