# GeoTest — a test app for the WSA geocoder

Makes a forward or reverse `Geocoder` query. The WSA provider is `GeocodeService` from Microsoft's `SystemApp`: it calls Azure Maps with a key the host hands out over the `Geocode` channel. Result and timing go to `logcat` with the tag `GeoTest`.

```bash
bin/build-testapp.sh testapps/GeoTest --install
adb logcat -s GeoTest GeocodeProviderImpl GeocodeRedirectionHandler

adb shell am start -S -n org.wsalineage.geotest/.MainActivity                      # default query "Минск" (Minsk)
adb shell am start -S -n org.wsalineage.geotest/.MainActivity -e q 'Paris'
adb shell am start -S -n org.wsalineage.geotest/.MainActivity -e lat 53.9 -e lon 27.56

# "PermissionUsage" channel: 20 s of GPS subscription — the host should show the location icon in Windows
adb shell pm grant org.wsalineage.geotest android.permission.ACCESS_FINE_LOCATION
adb shell am start -S -n org.wsalineage.geotest/.MainActivity -e loc 20
```
