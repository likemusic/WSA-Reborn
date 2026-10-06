#!/usr/bin/env bash
# Bakes into the image the display settings without which WSA does not show windows.
#
#   bin/add-display-settings.sh <system_ext tree>
#
# WHY. The guest tells the host whether a display is needed, and this reflects the state
# of the Android screen (SCREEN_ON → displayRequired=true). With the 15-second timeout
# that GSI sets by default, the screen turns off, displayRequired=false is sent,
# and Windows stops creating windows.
#
# The settings live in /data, not in the image, so they are set by an init service after
# boot — otherwise nobody would set them on a clean install.
set -euo pipefail
DEST="${1:?specify the system_ext tree}"
mkdir -p "$DEST/etc/init" "$DEST/bin"

cat > "$DEST/bin/wsa-display-settings.sh" <<'INNER'
#!/system/bin/sh
# The screen must not turn off: otherwise the guest tells the host displayRequired=false
# and app windows stop being created in Windows.
settings put system screen_off_timeout 1800000
settings put global stay_on_while_plugged_in 7
INNER
chmod 755 "$DEST/bin/wsa-display-settings.sh"

cat > "$DEST/etc/init/wsa-display-settings.rc" <<'INNER'
service wsa_display_settings /system_ext/bin/wsa-display-settings.sh
    class late_start
    user system
    group system
    oneshot
    disabled
    seclabel u:r:system_app:s0

on property:sys.boot_completed=1
    start wsa_display_settings
INNER

echo "added:"
echo "  $DEST/bin/wsa-display-settings.sh"
echo "  $DEST/etc/init/wsa-display-settings.rc"
