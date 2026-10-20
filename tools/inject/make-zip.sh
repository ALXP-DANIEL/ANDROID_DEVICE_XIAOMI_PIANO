#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Build recovery ZIPs: install Visor on a real ESP and patch boot_a/boot_b;
# ordinary uninstall restores kernels and leaves ESP intact; reclaim is two-stage.
#
#   tools/inject/make-zip.sh MU-PIANO.IMG OUT.ZIP [WALLPAPER...]
#   tools/inject/make-zip.sh --esp DIR MU-PIANO.IMG OUT.ZIP
#
# Writes OUT-installer.zip, OUT-uninstall.zip and OUT-uninstall-clean.zip.
# WALLPAPERs go to tools/esp/make-esp.sh;
# --esp takes an ESP that make-esp.sh already built (and the caller changed).
# Needs out/selector.bin (make -C selector). The static arm64 injector is
# built with $CC when set (an arm64 host), else in the builder image
# (docker/Dockerfile, tagged piano-uefi-build).
set -euo pipefail
cd "$(dirname "$0")/../.."

esp=
if [ "${1:-}" = --esp ]; then esp=$(cd "$2" && pwd); shift 2; fi
[ $# -ge 2 ] || { echo "usage: make-zip.sh [--esp DIR] MU-PIANO.IMG OUT.ZIP [WALLPAPER...]"; exit 1; }
uefi_img=$1
zip_out=$(cd "$(dirname "$2")" && pwd)/$(basename "$2" .zip).zip
shift 2
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

flags="-O2 -static -s -Wall -Wextra -Werror"
mkdir -p out
if [ -n "${CC:-}" ]; then
	$CC $flags -o out/piano-inject tools/inject/piano-inject.c
	$CC $flags -o out/piano-esp tools/inject/piano-esp.c
else
	docker run --rm -v "$PWD:/p" -w /p piano-uefi-build aarch64-linux-gnu-gcc $flags -o out/piano-inject tools/inject/piano-inject.c
	docker run --rm -v "$PWD:/p" -w /p piano-uefi-build aarch64-linux-gnu-gcc $flags -o out/piano-esp tools/inject/piano-esp.c
fi

python3 - "$uefi_img" "$stage/uefi.bin" <<'PY'
import sys
sys.path.insert(0, 'tools')
import pack
open(sys.argv[2], 'wb').write(pack.read_boot(open(sys.argv[1], 'rb').read())['kernel'])
PY

if [ -n "$esp" ]; then
	cp -r "$esp" "$stage/esp"
else
	tools/esp/make-esp.sh "$stage/esp" "$@"
fi
# FAT image is written only into a newly created ESP, never into boot.
python3 tools/esp/make-fat.py "$stage/esp" "$stage/esp.img"

mkdir -p "$stage/META-INF/com/google/android"
cp tools/inject/update-binary "$stage/META-INF/com/google/android/update-binary"
echo '#MAGISK' > "$stage/META-INF/com/google/android/updater-script"
cp out/piano-inject out/piano-esp out/selector.bin "$stage/"

rm -f "${zip_out%.zip}-installer.zip" "${zip_out%.zip}-uninstall.zip"
(cd "$stage" && zip -q -r -X "${zip_out%.zip}-installer.zip" META-INF piano-inject piano-esp selector.bin uefi.bin esp esp.img)
touch "$stage/uninstall"
(cd "$stage" && zip -q -r -X "${zip_out%.zip}-uninstall.zip" META-INF piano-inject uninstall)
cp tools/inject/reclaim.sh "$stage/"
touch "$stage/reclaim"
rm -f "${zip_out%.zip}-uninstall-clean.zip"
(cd "$stage" && zip -q -r -X "${zip_out%.zip}-uninstall-clean.zip" META-INF piano-inject piano-esp uninstall reclaim reclaim.sh)
echo "${zip_out%.zip}-uninstall-clean.zip"
echo "${zip_out%.zip}-installer.zip"
echo "${zip_out%.zip}-uninstall.zip"
