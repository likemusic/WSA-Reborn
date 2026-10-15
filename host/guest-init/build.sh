#!/usr/bin/env bash
# Builds the own guest init (init.c, static) and its initrd: host/guest-init/build.sh <out.img>. 12.10.2026.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
gcc -static -Os -s -o "$tmp/init" "$here/init.c"
python3 "$here/mkcpio.py" "$tmp/init" "${1:?out.img}"
rm -rf "${tmp:?}"
ls -l "$1"
