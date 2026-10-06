#!/usr/bin/env bash
# Turns the build into a user build, as at Microsoft.
#
#   bin/make-user-build.sh <system tree>
#
# WHY. GSI is built as userdebug with ro.debuggable=1, while Microsoft's WSA is built
# as user with ro.debuggable=0. Debug mode enables a whole layer of mechanisms
# that the stock subsystem does not have: in logs this shows up via TransactionTracing
# ("Could not find layer handle" — 23 times for us, zero at Microsoft).
#
# Side effect: `adb root` and some debug properties go away. In WSA `adb
# root` is forbidden anyway (production build), so the loss is small for us.
set -euo pipefail
SRC="${1:?specify the system tree}"
P="$SRC/system/build.prop"
[ -w "$P" ] || { echo "not writable: $P" >&2; exit 1; }

cp -a "$P" "$P.userdebug-backup"
sed -i 's/^ro\.build\.type=userdebug$/ro.build.type=user/' "$P"
sed -i 's/^ro\.debuggable=1$/ro.debuggable=0/' "$P"

echo "converted to user:"
grep -E '^ro\.(build\.type|debuggable|secure|adb\.secure)=' "$P" | sed 's/^/  /'
echo "original saved: $(basename "$P").userdebug-backup"
