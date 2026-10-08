#!/bin/bash
#
# Remove proven-dead payload from the recovery ramdisk staging directory.
#
# Invoked from BOARD_RECOVERY_IMAGE_PREPARE, which runs as the last step of the
# INTERNAL_RECOVERY_RAMDISK_FILES_TIMESTAMP recipe in build/make/core/Makefile
# — after every module has been installed into $(TARGET_RECOVERY_ROOT_OUT) and
# after ramdisk-files.txt / ramdisk-files.sha256sum have been generated. Both
# manifests are therefore regenerated here, using the same commands the build
# uses, so they continue to describe the ramdisk that is actually packed.
#
# Why this exists: piano's bootloader refuses any recovery whose compressed
# ramdisk exceeds a measured bound of ~29.21 MB (bracketed by seven controlled
# flashes: boots at 29,210,310 bytes, fails at 29,228,841). Enabling crypto
# needs roughly 3 MB that the image does not have. Compression cannot supply it
# — the bootloader accepts only legacy LZ4, proven by a gzip image that
# bootlooped at the splash screen.
#
# Removal criteria. A file is pruned only when all three hold:
#
#   1. No ELF in the ramdisk lists it in DT_NEEDED.
#   2. No file in the ramdisk contains its soname as a string, so it cannot be
#      reached by dlopen either.
#   3. No process had it mapped on a booted image (checked against /proc/*/maps
#      on hardware).
#
# The ELF closure gate (piano16-elf-closure-audit.py) runs after this script on
# the packed image and fails the build if a pruned file was in fact needed.

set -euo pipefail

ROOT="${1:?usage: piano16-ramdisk-prune.sh <TARGET_RECOVERY_ROOT_OUT>}"

# ICU — REMOVED FROM THE PRUNE LIST. Kept here as a record of why.
#
# This was pruned on the evidence of the no-crypto build, where nothing in the
# image references ICU. That evidence does not survive TW_INCLUDE_CRYPTO:
# system/bin/recovery then names libandroidicu.so in DT_NEEDED directly, and
# the crypto image died with
#   CANNOT LINK EXECUTABLE "/system/bin/recovery":
#     library "libandroidicu.so" not found: needed by main executable
# after init had already started, leaving the device in fastboot.
#
# The list below is therefore a proposal, and piano16-prune-verify.py is the
# authority: it recomputes the evidence from the tree actually being packed and
# fails the build if any listed file is still referenced.
#
# Historical note on the measurement: with ICU removed the no-crypto image was
# 26,721,747 bytes compressed, against 28,964,896 with it.
#
# They are installed because recovery, libxml2, libsqlite and libkeystoreinfo
# each depend on libandroidicu at build time — but on libandroidicu_static, the
# static shim. Verified with llvm-readelf: none of those four binaries has any
# ICU entry in DT_NEEDED. The only DT_NEEDED references to libicuuc.so and
# libicui18n.so in the entire ramdisk come from libandroidicu.so itself, and
# nothing references libandroidicu.so. The three libraries form a closed island.
#
# Confirmed on hardware against image p67: a scan of /proc/*/maps for every
# running process returned no match for libicuuc, libicui18n or libandroidicu,
# and a string scan of every file under /system/bin, /system/lib64,
# /vendor/lib64, /vendor/bin and /odm/lib64 matched only the three libraries
# themselves.
PRUNE=()

# Group 2 — MEASURED, THEN FOUND UNNECESSARY. Retained as a record.
#
# These were established dead the same three ways as ICU and were about to be
# removed to make crypto fit. Then the premise collapsed: the size ceiling the
# pruning existed to satisfy turned out not to apply to TWRP16 at all. A
# 31,265,294-byte image - 2,054,984 above the bound measured on TWRP14 - boots
# fully into TWRP with crypto enabled. See docs/twrp16-test-matrix.md.
#
# So nothing is pruned. Removing working code to solve a constraint that does
# not bind would be pure risk. The list below stays only so the analysis is not
# lost if a real ceiling is ever found:
#
#   system/lib64/libadbd_services.so   2,115,944   nothing references it
#   system/lib64/libcutils_sockets.so    102,184   only libadbd_services does
#   system/lib64/libxml2.so            1,175,936   unmapped while servicemanager
#                                                  parsed the VINTF manifests
#   system/lib64/libperfetto_c.so        883,896   tracing has no consumer
#   system/bin/keystore_cli_v2           121,040   inert, no .rc starts it
#   system/lib64/libchrome.so          1,243,064   only keystore_cli_v2 names it
#   system/lib64/libevent.so             332,592   only reachable via libchrome
#   system/lib64/libnl.so                269,840   no consumer
#
# Any future use of this list must go through piano16-prune-verify.py, which
# recomputes the evidence against the tree being packed. The ICU note above
# explains why a static list is not trustworthy on its own.



echo "----- Pruning dead payload from recovery ramdisk ------"

if [ ${#PRUNE[@]} -eq 0 ]; then
    echo "  prune list empty, nothing to do"
    exit 0
fi

# Verify before deleting. This must not be skipped: the list is written from
# an analysis of one build, and a configuration change can invalidate it
# silently. See the ICU note above for the failure that made this mandatory.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 "$HERE/piano16-prune-verify.py" "$ROOT" "${PRUNE[@]}"

freed=0
for rel in "${PRUNE[@]}"; do
    f="$ROOT/$rel"
    if [ -f "$f" ]; then
        size=$(stat -c %s "$f")
        freed=$((freed + size))
        rm -f "$f"
        echo "  pruned $rel ($size bytes)"
    else
        # Not an error: the file may already be absent in a configuration that
        # does not install it. The closure gate is what enforces correctness.
        echo "  absent $rel"
    fi
done
echo "  freed $freed bytes uncompressed"

# Regenerate the two manifests, byte-for-byte the same commands as
# build/make/core/Makefile.
cd "$ROOT"
find . | sed "s/.\///" | sed "/lib\/modules\//d" > ramdisk-files.txt
find -type f \
    | sed "s/.\/ramdisk-files.sha256sum//" \
    | sed "/lib\/modules/d" \
    | sed "/prop.default/d" \
    | sed "/ld\.config\.txt/d" \
    | xargs sha256sum > ramdisk-files.sha256sum

echo "  manifests regenerated"
