#!/bin/sh
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Build the device tree the boot menu hands the mainline kernel, the same
# way blu-sharky/debian-piano boots it: the stock piano base DT (from
# vendor_boot) plus their overlay chain, then ABL's memory map and our
# initrd placeholders (tools/linux-dtb.py).
#   build-linux-dtb.sh stock-piano.dtb live.dts debian-piano/ linux-piano/include out.dtb
# stock-piano.dtb holds every base DT ABL can pick; BASE picks one by its
# model (default: the SunP v2 Alt. Thermal Profile base, which matches piano).
# OVERLAY is the top of the chain (default dtbo-piano-power.dts, all features).
set -eu
abs() { echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
base=$(abs "$1"); live=$(abs "$2"); dp=$(abs "$3"); inc=$(abs "$4"); out=$(abs "$5")
here=$(cd "$(dirname "$0")/.." && pwd)
BASE=${BASE:-"SunP v2 Alt. Thermal Profile SoC"}
OVERLAY=${OVERLAY:-dtbo-piano-power.dts}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$base" "$work/all.dtb"; cp "$live" "$work/live.dts"
cp "$dp"/boot/dtbo-piano-*.dts "$work/"; cp -R "$inc" "$work/include"

docker run --rm --platform linux/arm64 -v "$work:/w" -e BASE="$BASE" -e OVERLAY="$OVERLAY" \
	debian:trixie sh -euc '
	apt-get update -qq >/dev/null
	apt-get install -y -qq device-tree-compiler cpp python3 >/dev/null 2>&1
	cd /w
	python3 - <<PY
import struct, subprocess
d = open("all.dtb", "rb").read()
off = n = 0
while off + 8 < len(d) and d[off:off + 4] == b"\xd0\x0d\xfe\xed":
    size = struct.unpack_from(">I", d, off + 4)[0]
    open(f"base{n}.dtb", "wb").write(d[off:off + size])
    off += size; n += 1
import os
for i in range(n):
    model = subprocess.run(["fdtget", f"base{i}.dtb", "/", "model"],
                           capture_output=True, text=True).stdout.strip()
    if model.endswith(os.environ["BASE"]):
        os.rename(f"base{i}.dtb", "base.dtb"); break
else:
    raise SystemExit("no base DT named " + os.environ["BASE"])
PY
	cpp -nostdinc -undef -x assembler-with-cpp -I include -o ov.dts "$OVERLAY"
	dtc -@ -q -I dts -O dtb -o ov.dtb ov.dts
	fdtoverlay -i base.dtb -o comp.dtb ov.dtb 2>/dev/null
	dtc -q -I dtb -O dts -o comp.dts comp.dtb'
python3 "$here/tools/linux-dtb.py" "$work/comp.dts" "$work/live.dts" "$work/menu.dts"
docker run --rm --platform linux/arm64 -v "$work:/w" debian:trixie sh -c \
	'apt-get update -qq >/dev/null; apt-get install -y -qq device-tree-compiler >/dev/null 2>&1; dtc -q -I dts -O dtb -o /w/menu.dtb /w/menu.dts'
cp "$work/menu.dtb" "$out"
ls -l "$out"
