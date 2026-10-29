# DecodeTest — does a video decode in the guest, and with which codec

Decodes a file with `MediaCodec` onto an offscreen `SurfaceTexture` (frames dropped) and writes `RESULT codec=… frames=… ms=…` or `FAILED …` to `logcat`. Made for the Latte hardware decoder on the own host (`docs/own-host.md`, 28.10.2026).

```bash
bin/build-testapp.sh testapps/DecodeTest --install
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -t 10 -c:v libx264 -pix_fmt yuv420p test.mp4
adb push test.mp4 /data/local/tmp/ && adb shell chmod 644 /data/local/tmp/test.mp4
adb shell am start -n org.wsalineage.decodetest/.MainActivity                                    # the system's codec for the type
adb shell am start -n org.wsalineage.decodetest/.MainActivity --es codec c2.android.avc.decoder  # a named one; --es file <path>
adb shell am start -n org.wsalineage.decodetest/.MainActivity --ez show true          # on screen, at the clip's pace
adb logcat -s DecodeTest
```

`--ez list true` lists the decoders (`CODEC <name> <types> hw sw alias`); `--ez show true` shows the frames on a `SurfaceView` in real time; `--ez nocsd true` drops `csd-0/1/2` from the format, so no codec-config buffer is queued (VP9 from WebM on Android 15+, `docs/own-host.md`). `RESULT` also has `inputs=` and `first_output_after=`.
