# Common environment for all scripts in bin/ — sourced, not executed:
#
#   . "$(dirname "$0")/wsa-env.sh"
#
# Every variable below can be overridden from the environment or from the user config
# ${XDG_CONFIG_HOME:-~/.config}/wsa-reborn/env (plain shell assignments, sourced first).
# Values that need a call to Windows (install location, %LOCALAPPDATA%) are detected once and cached
# in that same directory as env.detected; delete it to re-detect (e.g. after moving the WSA install).
#
#   WSA_INSTALL_DIR  WSA install folder (where AppxManifest.xml and the live *.vhdx are), WSL path
#   WSA_PACKAGE_DIR  %LOCALAPPDATA%\Packages\<WSA package family> — LocalCache (/data), LocalState (logs)
#   WSA_LOCALCACHE   $WSA_PACKAGE_DIR/LocalCache — live userdata.2.vhdx / metadata.2.vhdx
#   WSA_DIAG         $WSA_PACKAGE_DIR/LocalState/diagnostics — host-side logcat, ETW, tombstones
#   WSA_WORK         working directory for reference images and keys (default ~/wsa-work)
#   WSA_REF          reference partitions of the stock WSA image, unpacked (default $WSA_WORK/backup-full)
#   WSA_KEYS         signing keys: platform.{pk8,x509.pem} of your build tree, talkback.jks (default $WSA_WORK/keys)
#   WSA_STORAGE      big storage for /data pools, slot work images, archives — ideally NOT the system drive,
#                    because WSL itself lives on C: (default $WSA_WORK/storage)
#   WSA_POOLS        /data pools of switch-data.sh (default $WSA_STORAGE/wsa-data-pools)
#   WSA_VARIANTS_DIR other WSA installs for tests on WSABuilds variants (default: WSA-variants next to the install)
#   WSA_ADB          adb serial of the running subsystem (default <WSL gateway IP>:58526 — WSA listens on
#                    all interfaces of its VM, so from WSL it is reached through the gateway, not 127.0.0.1)
#   ADB              adb binary (default: adb from PATH, else $ANDROID_HOME/platform-tools/adb)
#   WSA_PACKAGE_FAMILY  package family name of WSA (fixed by Microsoft, same for all WSABuilds variants)

_wsa_cfg_dir="${XDG_CONFIG_HOME:-$HOME/.config}/wsa-reborn"
# shellcheck disable=SC1091
[ -r "$_wsa_cfg_dir/env" ] && . "$_wsa_cfg_dir/env"
# shellcheck disable=SC1091
[ -r "$_wsa_cfg_dir/env.detected" ] && . "$_wsa_cfg_dir/env.detected"

WSA_PACKAGE_FAMILY="${WSA_PACKAGE_FAMILY:-MicrosoftCorporationII.WindowsSubsystemForAndroid_8wekyb3d8bbwe}"

_wsa_ps() { powershell.exe -NoProfile -Command "$1" 2>/dev/null | tr -d '\r'; }

# Detection needs Windows (WSL interop); offline scripts (image tools) also run on plain Linux without it.
if { [ -z "${WSA_INSTALL_DIR:-}" ] || [ -z "${WSA_PACKAGE_DIR:-}" ]; } && command -v powershell.exe >/dev/null 2>&1; then
    _loc="$(_wsa_ps "(Get-AppxPackage MicrosoftCorporationII.WindowsSubsystemForAndroid).InstallLocation")"
    _lad="$(_wsa_ps '$env:LOCALAPPDATA')"
    [ -n "${WSA_INSTALL_DIR:-}" ] || { [ -n "$_loc" ] && WSA_INSTALL_DIR="$(wslpath "$_loc")"; }
    [ -n "${WSA_PACKAGE_DIR:-}" ] || { [ -n "$_lad" ] && WSA_PACKAGE_DIR="$(wslpath "$_lad")/Packages/$WSA_PACKAGE_FAMILY"; }
    if [ -n "${WSA_INSTALL_DIR:-}" ] && [ -n "${WSA_PACKAGE_DIR:-}" ]; then
        mkdir -p "$_wsa_cfg_dir"
        printf 'WSA_INSTALL_DIR=%q\nWSA_PACKAGE_DIR=%q\n' "$WSA_INSTALL_DIR" "$WSA_PACKAGE_DIR" > "$_wsa_cfg_dir/env.detected"
    fi
    unset _loc _lad
fi
WSA_INSTALL_DIR="${WSA_INSTALL_DIR:-}"
WSA_PACKAGE_DIR="${WSA_PACKAGE_DIR:-}"
WSA_LOCALCACHE="${WSA_LOCALCACHE:-${WSA_PACKAGE_DIR:+$WSA_PACKAGE_DIR/LocalCache}}"
WSA_DIAG="${WSA_DIAG:-${WSA_PACKAGE_DIR:+$WSA_PACKAGE_DIR/LocalState/diagnostics}}"
WSA_WORK="${WSA_WORK:-$HOME/wsa-work}"
WSA_REF="${WSA_REF:-$WSA_WORK/backup-full}"
WSA_KEYS="${WSA_KEYS:-$WSA_WORK/keys}"
WSA_STORAGE="${WSA_STORAGE:-$WSA_WORK/storage}"
WSA_POOLS="${WSA_POOLS:-$WSA_STORAGE/wsa-data-pools}"
# Other WSA installs (WSABuilds variants) next to the main one, each in its own folder (docs/variants.md).
WSA_VARIANTS_DIR="${WSA_VARIANTS_DIR:-${WSA_INSTALL_DIR:+$(dirname "$WSA_INSTALL_DIR")/WSA-variants}}"

if [ -z "${ADB:-}" ]; then
    ADB="$(command -v adb 2>/dev/null || true)"
    [ -n "$ADB" ] || ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}/platform-tools/adb"
fi
if [ -z "${WSA_ADB:-}" ]; then
    _gw="$(ip route show default 2>/dev/null | awk '{print $3; exit}')"
    WSA_ADB="${_gw:-127.0.0.1}:58526"
    unset _gw
fi

# Scripts that talk to the installed subsystem call this first: wsa_require WSA_INSTALL_DIR WSA_LOCALCACHE …
wsa_require() {
    local v
    for v in "$@"; do
        [ -n "${!v:-}" ] || { echo "$v is not set: WSA not found (is it registered?) — set it in ${XDG_CONFIG_HOME:-$HOME/.config}/wsa-reborn/env" >&2; exit 1; }
    done
}

# Newest installed Android SDK build-tools (d8 from 34.0.0 crashes on some of our classes — see build-bridge-local.sh).
wsa_build_tools() {
    ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"/build-tools/* 2>/dev/null | sort -V | tail -1
}

export WSA_VARIANTS_DIR WSA_INSTALL_DIR WSA_PACKAGE_DIR WSA_LOCALCACHE WSA_DIAG WSA_WORK WSA_REF WSA_KEYS WSA_STORAGE WSA_POOLS WSA_ADB ADB WSA_PACKAGE_FAMILY
unset _wsa_cfg_dir
