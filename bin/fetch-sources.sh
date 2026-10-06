#!/usr/bin/env bash
# Download LineageOS 21 sources — resilient to interruptions, works the same locally and on a VPS.
#
#   bin/fetch-sources.sh [dir]            (default $LOS_DIR or ~/lineage-21)
#   LOS_JOBS=4 bin/fetch-sources.sh       (number of jobs; fewer is gentler on network and memory)
#
# An interrupted run does NOT lose work: repo resumes where it stopped, the script
# can be restarted any number of times.
set -uo pipefail

DIR="${1:-${LOS_DIR:-$HOME/lineage-21}}"
JOBS="${LOS_JOBS:-4}"
# The branch is set from outside. Version mapping:
#   lineage-20.0 → Android 13 (the same as in WSA now)
#   lineage-21.0 → Android 14
#   lineage-22.x → Android 15,  lineage-23.x → 16,  lineage-24.0 → 17
BRANCH="${LOS_BRANCH:-lineage-21.0}"
MANIFEST="${LOS_MANIFEST:-https://github.com/LineageOS/android.git}"
REPO_BIN="$(command -v repo || echo "$HOME/bin/repo")"

[ -x "$REPO_BIN" ] || {
    echo "repo is missing. Install without sudo:"
    echo "  mkdir -p ~/bin && curl -s https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo && chmod +x ~/bin/repo"
    exit 1; }

# The environment check is mandatory: it catches lack of disk space BEFORE it
# runs out mid-download. Skip it only deliberately.
if [ "${LOS_SKIP_CHECK:-0}" != "1" ]; then
    "$(dirname "$0")/check-env.sh" "$DIR" || {
        echo
        echo "Environment is not suitable (see above). If you are sure — LOS_SKIP_CHECK=1 $0"
        exit 1; }
    echo
fi

mkdir -p "$DIR" && cd "$DIR" || exit 1

if [ ! -d .repo ]; then
    echo "== repo init ($BRANCH) =="
    # --depth=1 cuts history: the build does not need it, and the size halves.
    # clone.bundle is NOT disabled: these are ready-made packs fetched as a single HTTP file
    # and they survive a bad network better than a thousand git connections.
    "$REPO_BIN" init -u "$MANIFEST" -b "$BRANCH" --depth=1 --git-lfs || exit 1
else
    echo "== .repo already exists, resuming download =="
fi

echo "== repo sync: $JOBS jobs, dir $DIR =="
echo "   start: $(date +%H:%M:%S). Interrupting does not lose what was downloaded — just run again."
echo

# Disk space guard. On 07.09.2026 the download filled the system disk to 99% and brought
# the machine down: `df` inside WSL showed 562 GB while the real limit was 48. The guard
# watches the REAL disk and stops the download before it reaches zero.
GUARD_GB="${LOS_GUARD_GB:-25}"
GUARD_PATH="/mnt/c"; [ -d "$GUARD_PATH" ] || GUARD_PATH="$DIR"
guard() {
    while :; do
        free_gb=$(df -BG --output=avail "$GUARD_PATH" 2>/dev/null | tail -1 | tr -dc '0-9')
        if [ -n "${free_gb:-}" ] && [ "$free_gb" -lt "$GUARD_GB" ]; then
            echo
            echo "!! GUARD: ${free_gb} GB left on $GUARD_PATH (threshold ${GUARD_GB}) — stopping the download."
            echo "!! What was downloaded is kept; the run can be resumed after freeing space."
            pkill -TERM -P $$ -f "repo" 2>/dev/null
            kill -TERM $$ 2>/dev/null
            return
        fi
        sleep 60
    done
}
guard & GUARD_PID=$!
trap 'kill $GUARD_PID 2>/dev/null' EXIT
echo "space guard: watching $GUARD_PATH, threshold ${GUARD_GB} GB"
echo

attempt=1
max_attempts="${LOS_ATTEMPTS:-5}"
while [ "$attempt" -le "$max_attempts" ]; do
    echo "--- attempt $attempt of $max_attempts ---"
    # --fail-fast is NOT set: one failed repo must not bring down the whole run.
    # --retry-fetches lets repo itself retry failed downloads.
    if "$REPO_BIN" sync -c -j"$JOBS" --retry-fetches=3 --no-tags --force-sync \
                        --optimized-fetch --prune; then
        echo
        echo "== downloaded =="
        du -sh "$DIR" 2>/dev/null
        echo "finish: $(date +%H:%M:%S)"
        exit 0
    fi
    echo "attempt $attempt did not fully succeed — retrying only what is missing"
    attempt=$((attempt+1))
done

echo
echo "After $max_attempts attempts some repositories are still not synced."
echo "Nothing is lost: run the script again, it will resume where it stopped."
exit 1
