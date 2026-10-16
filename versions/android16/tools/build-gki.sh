#!/bin/bash
# Build the piano kernel: Google's GKI android15-6.6 and its system_dlkm
# modules, then copy them into this tree. Xiaomi's vendor modules
# (vendor_dlkm, vendor_ramdisk) stay stock; the frozen KMI keeps them loading.
#
# Usage: tools/build-gki.sh [commit]   (default: the pinned commit below)
set -euo pipefail

COMMIT=${1:-6761494007e8e8eb0e7c0a06a8afcc925203f17a}   # common-android15-6.6, 6.6.143
SRC=${GKI_SRC:-$HOME/kernel/gki-a15-6.6}
HERE=$(cd "$(dirname "$0")/.." && pwd)

if [ ! -d "$SRC/common" ]; then
    mkdir -p "$SRC" && cd "$SRC"
    repo init -u https://android.googlesource.com/kernel/manifest \
        -b common-android15-6.6 --depth=1
    repo sync -c -j4 --no-tags
fi
cd "$SRC/common"
git fetch --depth=1 https://android.googlesource.com/kernel/common "$COMMIT" 2>/dev/null || true
git checkout -q "$COMMIT"

# One pinned integration serves both Android versions; no vendor module changes.
TREE_ROOT=$(git -C "$HERE" rev-parse --show-toplevel)
python3 "$TREE_ROOT/integrate-ksunext.py" "$SRC"

cd "$SRC"
tools/bazel run --config=fast --config=stamp --jobs=2 //common:kernel_aarch64_dist -- --destdir=out/dist

# fsck.erofs comes from the Android tree (out/host/linux-x86/bin).
rm -rf "$HERE/system_dlkm" && mkdir -p "$HERE/system_dlkm/flatten"
fsck.erofs --extract="$HERE/system_dlkm" out/dist/system_dlkm.erofs.img
fsck.erofs --extract="$HERE/system_dlkm/flatten" out/dist/system_dlkm.flatten.erofs.img
cp out/dist/Image "$HERE/kernel"
strings "$HERE/kernel" | grep -m1 "Linux version"
