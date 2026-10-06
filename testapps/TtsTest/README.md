# TtsTest — a test app for the "TextToSpeech" channel

Prints speech synthesis engines and voices, speaks text aloud or writes it to a WAV; everything goes to `logcat` with the tag `TtsTest` (time to first audio, sample rate, format, end).

```bash
bin/build-testapp.sh testapps/TtsTest --install
adb logcat -s TtsTest WsaShellBridge.TTS

adb shell am start -S -n org.wsalineage.ttstest/.MainActivity                     # voices and the default Russian greeting aloud
adb shell am start -S -n org.wsalineage.ttstest/.MainActivity -e file 1           # to files/tts.wav
adb shell run-as org.wsalineage.ttstest cat files/tts.wav > tts.wav               # fetch the file
adb shell am start -S -n org.wsalineage.ttstest/.MainActivity -e text 'Hello' -e lang en-US
adb shell am start -S -n org.wsalineage.ttstest/.MainActivity --ei repeat 30 -e stop 1   # long text and stop
```

`run-as` works because the app is declared `debuggable`.
