#!/usr/bin/env bash
# Honest resource check before building LineageOS.
#
#   bin/check-env.sh [build directory]     (default $LOS_DIR or ~/lineage-21)
#
# The main reason it exists: in WSL `df` shows the size of the VIRTUAL disk,
# not the available space. The real ceiling is the free space on the Windows drive
# holding ext4.vhdx. A mistake here on 07.09.2026 brought the work machine down: df promised
# 562 GB while only 48 were really free on C:.
set -uo pipefail

DIR="${1:-${LOS_DIR:-$HOME/lineage-21}}"
NEED_GB=400          # sources ~150 + out ~250
NEED_RAM_GB=16       # headroom is needed at startup too, not just "on average": a neighbouring
                     # pipeline twice hit OOM in the first seconds of a build (burst demand from
                     # gradle/yarn) with a healthy-looking load average and free swap.
                     # Two cases on one machine in a day are a reason not to trust the average,
                     # but NOT grounds to claim that startup specifically is dangerous: those
                     # builds simply did not survive to the end.
ok=0; warn=0; fail=0

say()  { printf '%-24s %s\n' "$1" "$2"; }
good() { say "$1" "OK — $2"; ok=$((ok+1)); }
soft() { say "$1" "WARNING — $2"; warn=$((warn+1)); }
hard() { say "$1" "UNSUITABLE — $2"; fail=$((fail+1)); }

echo "== Build environment check =="
echo "build directory: $DIR"
echo

# --- where are we: WSL or plain Linux ----------------------------------------
IS_WSL=0
grep -qiE "microsoft|wsl" /proc/version 2>/dev/null && IS_WSL=1
say "environment" "$([ $IS_WSL = 1 ] && echo 'WSL2 — space is measured on the Windows drive' || echo 'plain Linux')"

# --- disk space --------------------------------------------------------------
mkdir -p "$DIR" 2>/dev/null
avail_gb=$(df -BG --output=avail "$DIR" 2>/dev/null | tail -1 | tr -dc '0-9')

if [ "$IS_WSL" = 1 ] && [[ "$DIR" != /mnt/* ]]; then
    # Directory inside the WSL virtual disk. Its growth is limited by the space on
    # the Windows drive holding the vhdx — that is the number to look at.
    host_avail_gb=$(df -BG --output=avail /mnt/c 2>/dev/null | tail -1 | tr -dc '0-9')
    say "df inside WSL" "${avail_gb:-?} GB (this is the virtual disk size, NOT the limit)"
    if [ -n "${host_avail_gb:-}" ]; then
        if   [ "$host_avail_gb" -ge "$NEED_GB" ]; then good "real limit (C:)" "${host_avail_gb} GB, need ${NEED_GB}"
        elif [ "$host_avail_gb" -ge 100 ];        then soft "real limit (C:)" "${host_avail_gb} GB of ${NEED_GB} needed — enough for sources only"
        else hard "real limit (C:)" "${host_avail_gb} GB of ${NEED_GB} needed — nowhere to build"
        fi
    else
        soft "real limit" "could not read /mnt/c — check manually"
    fi
    echo "                       (a directory on /mnt/... is measured on its own drive)"
else
    if   [ "${avail_gb:-0}" -ge "$NEED_GB" ]; then good "space" "${avail_gb} GB, need ${NEED_GB}"
    elif [ "${avail_gb:-0}" -ge 100 ];        then soft "space" "${avail_gb} GB of ${NEED_GB} needed — enough for sources only"
    else hard "space" "${avail_gb:-0} GB of ${NEED_GB} needed"
    fi
fi

# Filesystem: AOSP does not work on exFAT/NTFS — no symlinks and no case sensitivity.
fstype=$(df -PT "$DIR" 2>/dev/null | tail -1 | awk '{print $2}')
case "$fstype" in
    ext4|xfs|btrfs|zfs|overlay) good "filesystem" "$fstype" ;;
    exfat|fuseblk|ntfs|vfat|msdos|drvfs|9p)
        hard "filesystem" "$fstype — no symlinks and no case sensitivity, the AOSP tree will not build on it" ;;
    *)  soft "filesystem" "$fstype — check symlinks and case sensitivity" ;;
esac

# Check in practice, not by FS name: case and symlink.
probe="$DIR/.env-probe"; rm -rf "$probe" 2>/dev/null; mkdir -p "$probe" 2>/dev/null
if : > "$probe/Case" 2>/dev/null && : > "$probe/case" 2>/dev/null; then
    [ "$(ls "$probe" | wc -l)" -ge 2 ] && good "case sensitivity" "Case and case are different files" \
                                         || hard "case sensitivity" "Case and case merged into one file"
else
    soft "case sensitivity" "could not check"
fi
ln -s Case "$probe/link" 2>/dev/null && good "symbolic links" "can be created" \
                                       || hard "symbolic links" "cannot be created"
rm -rf "$probe" 2>/dev/null

# --- memory ------------------------------------------------------------------
ram_gb=$(free -g | awk 'NR==2{print $2}')
swap_gb=$(free -g | awk 'NR==3{print $2}')
total=$((ram_gb + swap_gb))
if   [ "$ram_gb" -ge 32 ]; then good "memory" "${ram_gb} GB RAM + ${swap_gb} swap"
elif [ "$total"  -ge "$NEED_RAM_GB" ]; then soft "memory" "${ram_gb} GB RAM + ${swap_gb} swap — limit parallelism (-j$(( total / 3 > 0 ? total / 3 : 1 )))"
else hard "memory" "${ram_gb} GB RAM + ${swap_gb} swap of ${NEED_RAM_GB} needed"
fi
[ "$IS_WSL" = 1 ] && echo "                       (in WSL a hang occurs at 100% swap and is cured only by a reboot)"

# --- CPU ---------------------------------------------------------------------
cpus=$(nproc)
[ "$cpus" -ge 8 ] && good "CPU" "${cpus} threads" || soft "CPU" "${cpus} threads — the build will be slow"

# --- network -----------------------------------------------------------------
speed=$(timeout 30 curl -sL -o /dev/null -w '%{speed_download}' \
        https://codeload.github.com/LineageOS/android_build/tar.gz/refs/heads/lineage-21.0 2>/dev/null)
if [ -n "${speed:-}" ] && [ "${speed%.*}" -gt 0 ] 2>/dev/null; then
    mbs=$(awk -v s="$speed" 'BEGIN{printf "%.1f", s/1048576}')
    hours=$(awk -v s="$speed" 'BEGIN{printf "%.0f", 120*1073741824/s/3600}')
    # IMPORTANT for interpretation: this is the speed of ONE connection to one server.
    # repo downloads in several streams from different hosts and is limited by the link,
    # not by the server. On a rented machine on 08.09.2026 this measurement gave
    # 7.7 MB/s, while the actual sync speed was 238 MB/s — thirty times
    # higher. So the hour estimate here is only a lower bound, not a forecast.
    if awk -v s="$speed" 'BEGIN{exit !(s > 10485760)}'; then good "network" "${mbs} MB/s per connection (faster with several streams)"
    else soft "network" "${mbs} MB/s per connection — worst case 120 GB in ~${hours} h, usually much faster"
    fi
else
    soft "network" "could not measure"
fi

# --- tools -------------------------------------------------------------------
missing=()
for t in git curl python3 make bison flex ccache rsync unzip zip; do
    command -v "$t" >/dev/null || missing+=("$t")
done
command -v repo >/dev/null || [ -x "$HOME/bin/repo" ] || missing+=("repo")
if [ ${#missing[@]} -eq 0 ]; then good "tools" "all present"
else soft "tools" "missing: ${missing[*]}"
     echo "                       sudo apt install -y bison flex ccache rsync zip unzip git-lfs libssl-dev"
     echo "                       repo without sudo: curl -s https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo && chmod +x ~/bin/repo"
fi

echo
echo "result: OK $ok, warnings $warn, blockers $fail"
[ "$fail" -gt 0 ] && { echo "Cannot build here — first fix the items marked UNSUITABLE."; exit 1; }
[ "$warn" -gt 0 ] && { echo "Building is possible, with the caveats above."; exit 0; }
echo "Environment is ready."
