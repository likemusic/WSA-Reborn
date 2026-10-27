#!/system/bin/sh
# Two entry points: without an argument — as uid system (pm disable); with the argument settings — as uid shell
# (init.wsa-no-setupwizard.rc, service wsa_settings). On Android 16 `settings put` as uid system
# crashes with NullPointerException in AppOpsService.checkPackage (no calling package), and the screen
# went off after 15 s — the host shut the subsystem down (30.09.2026, aosp16-vanilla).
if [ "$1" = settings ]; then
    log -t wsa_no_setupwizard "settings start uid=$(id)"
    for kv in "global device_provisioned 1" "secure user_setup_complete 1" \
            "system screen_off_timeout 1800000" "global stay_on_while_plugged_in 7"; do
        set -- $kv
        OUT=$(settings put "$1" "$2" "$3" 2>&1)
        log -t wsa_no_setupwizard "settings put $kv exit=$? out=[$OUT]"
    done
    exit 0
fi
log -t wsa_no_setupwizard "start uid=$(id)"
OUT=$(pm disable --user 0 org.lineageos.setupwizard/.SetupWizardActivity 2>&1)
RC=$?
log -t wsa_no_setupwizard "pm-disable-exit=$RC out=[$OUT]"
# Android 17: from 03.10.2026 this script disabled Launcher3 here — it crash-looped on the taskbar (NPE
# TaskbarControllers.cueBarController in TaskbarNavButtonController, ~600 times in 5 minutes, 02.10.2026).
# patches/aosp17/packages_apps_Launcher3/0001 fixed the crash (12.10.2026), and the own host needs Launcher3 as the desktop of the
# one-window mode, so now the old disable is undone on /data pools that still carry it (shell cannot do that, uid 1000 can).
if [ "$(getprop ro.build.version.sdk)" -ge 37 ] 2>/dev/null && pm list packages -d 2>/dev/null | grep -qx 'package:com.android.launcher3'; then
    OUT=$(pm enable --user 0 com.android.launcher3 2>&1)
    log -t wsa_no_setupwizard "launcher3-enable-exit=$? out=[$OUT]"
fi
# Settings (provisioned, and keeping the screen on: otherwise the guest reports displayRequired=false to the host and Windows
# stops creating windows — found 11.09.2026) are set by the settings entry point above, as uid shell.
log -t wsa_no_setupwizard "done"
