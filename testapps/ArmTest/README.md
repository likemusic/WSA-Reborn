# ArmTest — ARM translation test app

The native library is built only for `arm64-v8a` (`jni/ABIS`), so on x86_64 WSA it is executed by the `libhoudini` translator from Microsoft's `vendor`. The app loads the library, runs 10 million iterations and writes to `logcat` (tag `ArmTest`) the architecture the code was compiled for (`aarch64`), a checksum and the time.

```bash
bin/build-testapp.sh testapps/ArmTest --install
adb logcat -s ArmTest
adb shell am start -S -n org.wsalineage.armtest/.MainActivity
```
