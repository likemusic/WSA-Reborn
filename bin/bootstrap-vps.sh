#!/usr/bin/env bash
# Prepares a clean Ubuntu on a rented machine for building LineageOS.
#
#   on the VPS:  sudo bash bootstrap-vps.sh [build directory]
#
# Installs AOSP dependencies, repo, ccache and prints the next step. Does not
# download anything from the tree — fetch-sources.sh does that, so that an install failure
# is not mixed up with a download failure.
set -euo pipefail

DIR="${1:-/build/lineage-21}"
USER_NAME="${SUDO_USER:-$(id -un)}"
# root's home is /root, not /home/root — take it from passwd instead of building the path.
USER_HOME="$(getent passwd "$USER_NAME" | cut -d: -f6)"
[ -n "$USER_HOME" ] || USER_HOME="/home/$USER_NAME"

echo "== Preparing the machine for the build =="
echo "user: $USER_NAME ($USER_HOME), build directory: $DIR"
echo

# --- packages ----------------------------------------------------------------
# Install one at a time and skip unavailable ones: the AOSP package set shifts between Ubuntu
# releases (24.04, for example, dropped libncurses5), and one missing package
# must not break the whole setup.
echo "-- AOSP dependencies"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq

PKGS="git git-lfs curl wget rsync python3 python3-pip
bc bison build-essential ccache flex g++-multilib gcc-multilib
gnupg gperf imagemagick lib32ncurses-dev lib32readline-dev lib32z1-dev
libncurses-dev libncurses5 libsdl1.2-dev libssl-dev libxml2 libxml2-utils
lzop pngcrush schedtool squashfs-tools xsltproc zip unzip zlib1g-dev
libc6-dev-i386 x11proto-core-dev libx11-dev libgl1-mesa-dev fontconfig
openjdk-21-jdk-headless e2fsprogs qemu-utils android-sdk-libsparse-utils"

installed=0; missing=""
for p in $PKGS; do
    if apt-get install -y -qq --no-install-recommends "$p" >/dev/null 2>&1; then
        installed=$((installed+1))
    else
        missing="$missing $p"
    fi
done
echo "   packages installed: $installed"

# Android 13 (lineage-20) builds RenderScript with the ancient clang 3.8 from prebuilts, and
# that needs libncurses.so.5 and python (not python3). Ubuntu 24.04 lacks the former
# entirely and the latter by name. In Android 14 RenderScript is removed, so this does
# not show up there — and I considered the missing libncurses5 harmless. A day later the
# 13 branch build failed exactly on it.
apt-get install -y -qq python-is-python3 >/dev/null 2>&1 || true
for pair in "libncurses.so.6 libncurses.so.5" "libtinfo.so.6 libtinfo.so.5"; do
    set -- $pair
    if [ -e "/usr/lib/x86_64-linux-gnu/$1" ] && [ ! -e "/usr/lib/x86_64-linux-gnu/$2" ]; then
        ln -sf "$1" "/usr/lib/x86_64-linux-gnu/$2"
        echo "   symlink for old clang: $2 -> $1"
    fi
done
[ -n "$missing" ] && echo "   not available in this release (usually harmless):$missing"

# --- repo without the package manager: the apt version is usually outdated ---
echo "-- repo"
install -d -o "$USER_NAME" -g "$USER_NAME" "$USER_HOME/bin"
curl -s https://storage.googleapis.com/git-repo-downloads/repo > "$USER_HOME/bin/repo"
chmod +x "$USER_HOME/bin/repo"
chown "$USER_NAME:$USER_NAME" "$USER_HOME/bin/repo"
grep -q 'HOME/bin' "$USER_HOME/.bashrc" 2>/dev/null || \
    echo 'export PATH=$HOME/bin:$PATH' >> "$USER_HOME/.bashrc"

# --- ccache: saves hours on repeated builds ----------------------------------
echo "-- ccache (50 GB)"
# Create the cache directory explicitly and check that it IS a directory. On a clean Ubuntu 24.04
# there was an empty file at this path, and the whole build failed with "ccache: error: Not a
# directory" on the very first libc++ object file.
CCDIR="$USER_HOME/.cache/ccache"
[ -e "$CCDIR" ] && [ ! -d "$CCDIR" ] && rm -f "$CCDIR"
mkdir -p "$CCDIR"
chown -R "$USER_NAME:$USER_NAME" "$USER_HOME/.cache"
su - "$USER_NAME" -c "ccache -M 50G" >/dev/null 2>&1 || true
grep -q 'USE_CCACHE' "$USER_HOME/.bashrc" 2>/dev/null || {
    echo 'export USE_CCACHE=1' >> "$USER_HOME/.bashrc"
    echo 'export CCACHE_EXEC=/usr/bin/ccache' >> "$USER_HOME/.bashrc"
}

# --- build directory ---------------------------------------------------------
mkdir -p "$DIR"
chown -R "$USER_NAME:$USER_NAME" "$DIR"

# --- git identity: repo refuses to work without it ---------------------------
su - "$USER_NAME" -c "git config --global user.email >/dev/null 2>&1" || \
    su - "$USER_NAME" -c "git config --global user.email 'build@localhost'"
su - "$USER_NAME" -c "git config --global user.name >/dev/null 2>&1" || \
    su - "$USER_NAME" -c "git config --global user.name 'Build'"
su - "$USER_NAME" -c "git config --global color.ui false"

echo
echo "== Done. Environment check: =="
echo "  bin/check-env.sh $DIR"
echo
echo "Next, in order:"
echo "  bin/fetch-sources.sh $DIR          # sources, ~2 h on a good link"
echo "  bin/setup-target.sh $DIR           # target configuration"
echo "  cd $DIR && source build/envsetup.sh"
echo "  lunch lineage_wsa_x86_64-ap2a-userdebug"
echo "  m -j\$(( \$(free -g | awk 'NR==2{print \$2}') / 3 ))   # one job per 3 GB of RAM"
echo
echo "Fetch the result (from the local machine):"
echo "  rsync -avP <vps>:$DIR/out/target/product/wsa_x86_64/{system,system_ext,product}.img ."
echo
echo "Do NOT transplant the Microsoft components here, do it locally: they are"
echo "proprietary binaries with no reason to leave your machine."
