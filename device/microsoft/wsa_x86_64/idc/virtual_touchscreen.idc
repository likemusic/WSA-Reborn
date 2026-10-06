# WSA: overrides Microsoft's /vendor/usr/idc/virtual_touchscreen.idc (.idc lookup in A17: /product, /system_ext, /odm, /vendor).
# Without touch.displayId = local:100: in Android 17 the display uniqueId is built from EDID (local:4619827122562783332),
# so the touchscreen (the host injects the mouse through it) was left without a display and touches were dropped (03.10.2026).
# Without an association it goes to the primary built-in display — WSA has only one.
touch.deviceType = touchScreen
touch.orientationAware = 1
