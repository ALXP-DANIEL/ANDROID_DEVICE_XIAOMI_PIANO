#!/usr/bin/env bash
#
# Xiaomi Pad 8 Pro (piano) — TWRP16 offline recovery image audit.
#
# Runs on the image BEFORE it is ever written to the device. Verifies image
# identity and the boot-image invariants this device requires, and enforces the
# measured recovery ramdisk size ceiling.
#
# Usage:  piano16-preflash-audit.sh <recovery.img>
#
# Exit 0 only if every check passes. Nothing here touches the device.

set -u

IMG=${1:-}
[ -n "$IMG" ] && [ -f "$IMG" ] || { echo "usage: $0 <recovery.img>" >&2; exit 2; }

# Size limits.
#
# The TWRP14 tree measured a hard bound by flashing: 29,210,310 bytes booted,
# 29,228,841 did not. That bound was recorded as a property of the bootloader.
# It is not one. Two TWRP16 images above it have since been flashed to
# recovery_a and both loaded, the larger of the two booting fully into TWRP
# with crypto enabled:
#
#   31,403,528  loaded, init ran, adbd came up (recovery then hit a link error)
#   31,265,294  boots into TWRP, crypto enabled, UI up
#   33,652,931  boots, decrypts /data, repeatedly across many reboot cycles
#
# So the old figure is not a limit for images of this shape, and the true
# ceiling is unmeasured. This gate therefore asserts only what is known:
#
#   - above the largest image proven to boot, warn. It may well be fine; we
#     have no evidence either way, and refusing to build would be a guess
#     dressed as a rule.
#   - above the partition, fail. That one is arithmetic.
#
# Raise PROVEN_BOOT when a larger image is confirmed to boot on hardware, and
# only then.
PROVEN_BOOT=33652931
PART_SIZE=104857600

pass=0; fail=0
ok()   { printf '  \033[1;32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[1;31mFAIL\033[0m  %s\n' "$1"; fail=$((fail+1)); }

echo "image: $IMG"
sha=$(shasum -a 256 "$IMG" 2>/dev/null | cut -d' ' -f1 || sha256sum "$IMG" | cut -d' ' -f1)
echo "sha256: $sha"

read -r MAGIC HV KS RS FMT FILESZ <<EOF
$(python3 - "$IMG" <<'PY'
import struct, sys
d = open(sys.argv[1], 'rb').read()
magic = d[:8].decode('latin1')
ks, rs = struct.unpack_from('<II', d, 8)
hv = struct.unpack_from('<I', d, 40)[0]
rd = d[4096:4096+rs]
if rd[:4] == bytes.fromhex('02214c18'):
    fmt = 'lz4-legacy'
elif rd[:4] == bytes.fromhex('04224d18'):
    fmt = 'lz4-modern'
elif rd[:2] == b'\x1f\x8b':
    fmt = 'gzip'
else:
    fmt = 'other-' + rd[:4].hex()
print(magic, hv, ks, rs, fmt, len(d))
PY
)
EOF

echo "header v$HV  kernel_size=$KS  ramdisk=$RS  format=$FMT  file=$FILESZ"
echo

[ "$MAGIC" = "ANDROID!" ] && ok "ANDROID! magic" || bad "bad magic: $MAGIC"
[ "$HV" = "4" ]           && ok "header version 4" || bad "header version $HV, expected 4"
[ "$KS" = "0" ]           && ok "kernel_size 0 (uses active-slot ROM kernel)" \
                          || bad "kernel_size $KS, expected 0"
[ "$RS" -gt 0 ]           && ok "ramdisk present" || bad "no ramdisk"
# Legacy LZ4 only. A gzip ramdisk was tested on hardware and BOOTLOOPS at the
# splash screen, despite the kernel reporting CONFIG_RD_GZIP=y - the bootloader
# requires the format stock ships. Anything else is rejected here so it can
# never reach the device again.
[ "$FMT" = "lz4-legacy" ] && ok "legacy LZ4 ramdisk" \
    || bad "ramdisk format $FMT - only lz4-legacy boots on this device (gzip bootloops)"
[ "$FILESZ" -le "$PART_SIZE" ] && ok "image fits 100 MiB partition" \
                               || bad "image $FILESZ exceeds $PART_SIZE"

# Size.
if [ "$RS" -le "$PROVEN_BOOT" ]; then
    ok "ramdisk $RS at or below the largest image proven to boot ($PROVEN_BOOT)"
else
    printf '  \033[1;33mWARN\033[0m  ramdisk %s exceeds the largest proven-booting image %s by %s\n' \
        "$RS" "$PROVEN_BOOT" "$((RS - PROVEN_BOOT))"
    printf '        untested size. Flash to recovery_a only, and keep a known-good\n'
    printf '        image to hand; if the bootloader rejects it the device returns\n'
    printf '        to fastboot and slot A can be reset with: fastboot set_active a\n'
    pass=$((pass+1))
fi

echo
echo "PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ]
