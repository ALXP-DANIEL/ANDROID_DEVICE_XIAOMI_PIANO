#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-2-Clause-Patent
# Port Mu-Silicium's OnePlus 13 (dodge, SM8750) driver patches onto the
# piano drivers made by extract-qcom-dxe.py, and point their INFs at the
# patched copies. Run from the repository root after extracting.
set -euo pipefail
MU=deps/Mu-Silicium/Binaries
R=$MU/dodge/QcomPkg/Drivers
T=$MU/piano/QcomPkg/Drivers

use_patched() {
	sed -i.bak "s/|$1\.efi|/|$1.patched.efi|/" "$T/$1/$1.inf" && rm "$T/$1/$1.inf.bak"
}

# Patches that match piano's builds directly. Runs that do not match are
# OnePlus-only (charger and battery hooks) or absent from piano's builds.
for pair in SPMIDxe/SPMIDxe:SPMI ButtonsDxe/ButtonsDxe:ButtonsDxe \
	UsbConfigDxe/UsbConfigDxe:UsbConfigDxe UsbMsdDxe/UsbMsdDxe:UsbMsdDxe \
	PmicDxe/PmicDxeLa:PmicDxe QcomChargerDxe/QcomChargerDxeLA:QcomChargerDxeLA; do
	ref=${pair%%:*} name=${pair##*:}
	echo "== $name"
	python3 tools/port-patches.py "$R/$ref.efi" "$R/$ref.patched.efi" \
		"$T/$name/$name.efi" -o "$T/$name/$name.patched.efi" || true
	use_patched "$name"
done

# TzDxeLA: the TZ applet flag sits at the same offset (0xf5b0) in piano's
# build; the reference patch sets it to TRUE.
python3 - "$T/TzDxeLA/TzDxeLA.efi" <<'PY'
import sys
d = bytearray(open(sys.argv[1], 'rb').read())
if d[0xf5b0] != 0:
    sys.exit('TzDxeLA: unexpected byte at 0xf5b0')
d[0xf5b0] = 1
open(sys.argv[1].replace('.efi', '.patched.efi'), 'wb').write(d)
print('== TzDxeLA\n  0xf5b0: 00 -> 01')
PY
use_patched TzDxeLA

# ClockDxe: on start it drops the MM rail (MMCX), which turns piano's
# video-mode panel white. Renaming the rail to /vcs/vdd_mx sends those
# votes to the MX rail instead and leaves MMCX as ABL set it.
python3 - "$T/ClockDxe/ClockDxe.efi" <<'PY'
import sys
d = bytearray(open(sys.argv[1], 'rb').read())
name = b'/vcs/vdd_mm\0'
if d.count(name) != 1:
    sys.exit('ClockDxe: vdd_mm rail name not found once')
i = d.index(name) + len(name) - 2
d[i] = ord('x')
open(sys.argv[1].replace('.efi', '.patched.efi'), 'wb').write(d)
print('== ClockDxe\n  /vcs/vdd_mm -> /vcs/vdd_mx')
PY
use_patched ClockDxe

# UFSDxe: ABL leaves the UFS link up, and link startup fails (error 0x3b,
# then 0x55). Port the reference fix: reset the link first. Piano's build
# has no spare section, so the reset replaces the error branch in place
# and the two calls are no longer checked. 0xafa8 matches the reference's
# 0xa998 change.
python3 - "$T/UFSDxe/UFSDxe.efi" <<'PY'
import struct, sys
d = bytearray(open(sys.argv[1], 'rb').read())

def target(va):
    off = struct.unpack_from('<I', d, va)[0] & 0x3ffffff
    return va + (off - (1 << 26) if off & (1 << 25) else off) * 4

def bl(va, to):
    return struct.pack('<I', 0x94000000 | (((to - va) // 4) & 0x3ffffff))

if target(0x124a8) != 0x11e10 or target(0xafa8) != 0xc678:
    sys.exit('UFSDxe: unexpected code at the patch sites')
code = {
    0xafa8: bytes.fromhex('21008052'),      # mov w1, #1
    0xafac: bl(0xafac, 0xc7a4),
    0x124a4: bytes.fromhex('7fa61239'),     # strb wzr, [x19, #0x4a9]
    0x124a8: bytes.fromhex('e00313aa'),     # mov x0, x19
    0x124ac: bytes.fromhex('e1031f2a'),     # mov w1, wzr
    0x124b0: bl(0x124b0, 0x111a8),          # link reset
    0x124b4: bytes.fromhex('e00313aa'),     # mov x0, x19
    0x124b8: bl(0x124b8, 0x11e10),          # link startup
}
for va, ins in code.items():
    d[va:va + 4] = ins
open(sys.argv[1].replace('.efi', '.patched.efi'), 'wb').write(d)
print('== UFSDxe\n  link reset before startup')
PY
use_patched UFSDxe
