#!/usr/bin/env bash
# Restores the AOSP signature of the IStatusBar interface in our framework.
#
#   bin/patch-istatusbar.sh <system tree>
#
# WHY. LineageOS adds a third parameter to
#   com.android.internal.statusbar.IStatusBar.showShutdownUi(boolean, String)
# while Microsoft's SystemUIApp is compiled against AOSP and implements the two-parameter version.
# When called through Binder it crashes with AbstractMethodError.
#
# Two things are fixed at once, otherwise the system would not be logically consistent:
#   * the method declaration in framework.jar (the interface);
#   * the call in services.jar (StatusBarManagerService$1 passes three arguments).
#
# LineageOS's own SystemUI is not used in WSA — Microsoft's SystemUIApp is there
# instead, so the third parameter is needed by nobody except LineageOS code in
# the system server, which we patch right here.
set -euo pipefail
SRC="${1:?specify the system tree}"
T="${SMALI_TOOLS:-/mnt/los/tools}"
CP="$T/baksmali.jar:$T/dexlib2-2.5.2.jar:$T/util-2.5.2.jar:$T/guava-31.1-jre.jar:$T/jcommander-1.82.jar"
CPS="$T/smali.jar:$T/dexlib2-2.5.2.jar:$T/util-2.5.2.jar:$T/guava-31.1-jre.jar:$T/jcommander-1.82.jar:$T/antlr-runtime-3.5.2.jar:$T/ST4-4.3.4.jar"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

patch_jar() {  # <jar path> <which dex> <smali file> <sed expression> <description>
    local jar="$1" dexname="$2" target="$3" expr="$4" what="$5"
    local d="$WORK/$(basename "$jar" .jar)"
    mkdir -p "$d" && (cd "$d" && 7z x -y "$jar" 'classes*.dex' >/dev/null 2>&1)
    [ -f "$d/$dexname" ] || { echo "no $dexname in $(basename "$jar")" >&2; return 1; }
    java -cp "$CP" org.jf.baksmali.Main disassemble "$d/$dexname" -o "$d/smali" 2>/dev/null
    local f="$d/smali/$target"
    [ -f "$f" ] || { echo "no $target" >&2; return 1; }
    sed -i "$expr" "$f"
    java -cp "$CPS" org.jf.smali.Main assemble "$d/smali" -o "$d/$dexname" --api 33 2>/dev/null
    # put the modified dex back, keeping the rest of the contents
    (cd "$d" && 7z u -tzip "$jar" "$dexname" >/dev/null 2>&1)
    echo "  $what — done"
}

echo "== patching the interface in framework.jar"
patch_jar "$SRC/system/framework/framework.jar" classes4.dex \
    com/android/internal/statusbar/IStatusBar.smali \
    's|showShutdownUi(ZLjava/lang/String;Z)V|showShutdownUi(ZLjava/lang/String;)V|' \
    "IStatusBar.showShutdownUi → AOSP signature"

echo "== patching the call in services.jar"
patch_jar "$SRC/system/framework/services.jar" classes2.dex \
    com/android/server/statusbar/StatusBarManagerService\$1.smali \
    's|invoke-interface {v0, p1, p2, p3}, Lcom/android/internal/statusbar/IStatusBar;->showShutdownUi(ZLjava/lang/String;Z)V|invoke-interface {v0, p1, p2}, Lcom/android/internal/statusbar/IStatusBar;->showShutdownUi(ZLjava/lang/String;)V|' \
    "call from StatusBarManagerService"

echo
echo "verification:"
7z x -so "$SRC/system/framework/framework.jar" classes4.dex 2>/dev/null | grep -ac 'showShutdownUi' | sed 's/^/  occurrences in framework: /'
