#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Build the recovery ZIPs: one that puts the selector and UEFI into
# boot_a/boot_b and Visor on the ESP, and one that takes them out again.
#
#   tools/inject/make-zip.sh MU-PIANO.IMG OUT.ZIP [WALLPAPER...]
#   tools/inject/make-zip.sh --esp DIR MU-PIANO.IMG OUT.ZIP
#
# Writes OUT.ZIP and OUT-uninstall.zip. WALLPAPERs go to tools/esp/make-esp.sh;
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

flags="-O2 -static -s -Wall -Wextra -Werror -o out/piano-inject tools/inject/piano-inject.c"
mkdir -p out
if [ -n "${CC:-}" ]; then
	$CC $flags
else
	docker run --rm -v "$PWD:/p" -w /p piano-uefi-build aarch64-linux-gnu-gcc $flags
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

mkdir -p "$stage/META-INF/com/google/android"
cp tools/inject/update-binary "$stage/META-INF/com/google/android/update-binary"
echo '#MAGISK' > "$stage/META-INF/com/google/android/updater-script"
cp out/piano-inject out/selector.bin "$stage/"

rm -f "$zip_out" "${zip_out%.zip}-uninstall.zip"
(cd "$stage" && zip -q -r -X "$zip_out" META-INF piano-inject selector.bin uefi.bin esp)
touch "$stage/uninstall"
(cd "$stage" && zip -q -r -X "${zip_out%.zip}-uninstall.zip" META-INF piano-inject uninstall)
echo "$zip_out"
echo "${zip_out%.zip}-uninstall.zip"
