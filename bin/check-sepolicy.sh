#!/usr/bin/env bash
# Preflight check: will the SELinux policy compile from the chosen set of partitions.
#
#   bin/check-sepolicy.sh --system <tree> --system-ext <tree> [--product <tree>] \
#       [--vendor <tree>] [--vers 33.0] [--ref <Microsoft reference>]
#
# Arguments are the ROOTS of unpacked partitions (etc/selinux/… is expected inside; for system
# the nested GSI layout, where it is system/etc/selinux, is also accepted). Example for the
# aosp14-gsi slot:
#
#   bin/check-sepolicy.sh --system scratch/gsi14/split2/system \
#       --system-ext scratch/gsi14/split2/system_ext
#
# vendor defaults to the Microsoft reference (--ref, default $WSA_REF):
# we do not rebuild this partition in any slot, and it is its plat_pub_versioned.cil that
# declares the proprietary types (gskserver, rcmsvc, sysprep_service, logcat_forwarder…).
#
# Why. WSA keeps a precompiled policy in vendor, next to hashes of the partitions it was
# built for. Our build's hash matches none of them, so init has to compile the
# policy itself. If compilation fails, init panics BEFORE logd starts: from outside this looks like a failure
# without a single log line, indistinguishable from any other silent failure. The check repeats
# exactly the file set and flags init uses, and costs a fraction of a second instead of
# a run on the live WSA (grabbing the shared subsystem from a neighbour + an owner's command).
#
# THE CONTROL IS MANDATORY AND ALWAYS RUN. The script first compiles Microsoft's policy — known to
# work. If even that fails to compile, the tool is unfit (the host libsepol is not the same build
# as in the Android image), not the configuration under test; then any negative answer means
# nothing, and the script says so plainly.
#
# WHAT THE CHECK DOES NOT CATCH: neverallow. init compiles the policy with -N, and so do we — a neverallow
# violation does not break boot on the device (the platform build and CTS catch it). The compiler itself is
# bin/cil-compile.py (host libsepol via ctypes; the Android secilc from the image does not run
# under WSL, and the secilc package cannot be installed without a password).
set -euo pipefail
. "$(dirname "$0")/wsa-env.sh"

HERE=$(cd "$(dirname "$0")" && pwd)
SYS=""; EXT=""; PRD=""; VND=""; VERS="33.0"; REF="$WSA_REF"
while [ $# -gt 0 ]; do
    case "$1" in
        --system)     SYS="$2"; shift 2 ;;
        --system-ext) EXT="$2"; shift 2 ;;
        --product)    PRD="$2"; shift 2 ;;
        --vendor)     VND="$2"; shift 2 ;;
        --vers)       VERS="$2"; shift 2 ;;
        --ref)        REF="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 1 ;;
    esac
done
[ -n "$SYS" ] && [ -n "$EXT" ] || { sed -n '2,10p' "$0"; exit 1; }
VND="${VND:-$REF/vendor}"

# GSI and our images lay system out differently: etc/selinux is either at the partition root or in
# system/etc/selinux (that very nested /system/system that requires the symlinks).
seldir() {
    for c in "$1/etc/selinux" "$1/system/etc/selinux"; do
        [ -d "$c" ] && { echo "$c"; return; }
    done
    echo "etc/selinux not found in $1" >&2; exit 1
}

# The file set in exactly the same order and with the same presence rules as init
# (system/core/init/selinux.cpp): mapping is picked by the vendor plat policy version, *.compat.cil
# is included if present.
policy_set() { # <system> <system_ext> <product|""> <vendor>
    local s e p v; s=$(seldir "$1"); e=$(seldir "$2"); v="$4/etc/selinux"
    echo "$s/plat_sepolicy.cil"
    [ -f "$s/mapping/$VERS.cil" ]        && echo "$s/mapping/$VERS.cil"
    [ -f "$s/mapping/$VERS.compat.cil" ] && echo "$s/mapping/$VERS.compat.cil"
    echo "$e/system_ext_sepolicy.cil"
    [ -f "$e/mapping/$VERS.cil" ]        && echo "$e/mapping/$VERS.cil"
    [ -f "$e/mapping/$VERS.compat.cil" ] && echo "$e/mapping/$VERS.compat.cil"
    if [ -n "$3" ]; then
        p=$(seldir "$3")
        [ -f "$p/product_sepolicy.cil" ] && echo "$p/product_sepolicy.cil"
        [ -f "$p/mapping/$VERS.cil" ]    && echo "$p/mapping/$VERS.cil"
    fi
    echo "$v/plat_pub_versioned.cil"
    echo "$v/vendor_sepolicy.cil"
    return 0
}

echo "== CONTROL: Microsoft policy from $REF =="
mapfile -t CTL < <(policy_set "$REF/system" "$REF/system_ext" "$REF/product" "$REF/vendor")
if ! python3 "$HERE/cil-compile.py" "${CTL[@]}"; then
    echo >&2
    echo "the control set did NOT compile — the tool is unfit, not the configuration under test." >&2
    echo "Any result of the check below would be unreliable, so it is not run." >&2
    exit 2
fi

echo
echo "== CHECK: $SYS + $EXT${PRD:+ + $PRD} + $VND (vendor plat vers $VERS) =="
mapfile -t SET < <(policy_set "$SYS" "$EXT" "$PRD" "$VND")
printf '  %s\n' "${SET[@]}"
python3 "$HERE/cil-compile.py" "${SET[@]}"
