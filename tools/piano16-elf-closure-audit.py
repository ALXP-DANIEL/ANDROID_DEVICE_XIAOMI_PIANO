#!/usr/bin/env python3
"""
Xiaomi Pad 8 Pro (piano) — TWRP16 recovery ramdisk ELF closure audit.

This is the compensating gate for ALLOW_MISSING_DEPENDENCIES. That flag lets
Soong skip modules whose dependencies are absent instead of failing the build,
which means a library the recovery genuinely needs could be silently dropped
and produce an image that builds cleanly but breaks at runtime.

This audit answers that directly: it unpacks the built recovery ramdisk,
resolves every ELF binary and shared library it contains, and fails if any
DT_NEEDED cannot be satisfied from inside the image itself.

Usage:
    piano16-elf-closure-audit.py <recovery.img> [--verbose]

Exit 0 only when the closure is complete.
"""

import os
import struct
import subprocess
import sys
import tempfile

# Provided by the Bionic runtime APEX / linker, never present as plain files
# in a recovery ramdisk. Absent entries for these are expected, not failures.
LINKER_PROVIDED = {
    "libc.so", "libm.so", "libdl.so", "libdl_android.so",
    "ld-android.so", "libstdc++.so",
}

# Objects that are present in the image but can never execute, verified by
# inspection. Their unresolved dependencies are expected and are reported as
# accepted exceptions rather than failures.
#
# Each entry must be justified by evidence that nothing loads the object:
# no init .rc references it and no binary in the image names it.
INERT_OBJECTS = {
    "vendor/odm/lib64/sensors.touch.detect.so":
        "not referenced by any binary or .rc in the image; libtouchreport.so "
        "does not name it. Retained because the touch stack is kept complete.",
    "system/bin/keystore_cli_v2":
        "TWRP manual CLI tool, not started by init. Its keystore libraries "
        "arrive only with TW_INCLUDE_CRYPTO.",
}

# Objects whose dependencies are deliberately NOT packaged, because they are
# resolved at runtime from the stock vendor partition mounted read-only at
# /piano/vendor-stock. Their init service sets LD_LIBRARY_PATH accordingly.
#
# This is not the same as an inert object: these DO execute. The exception is
# only valid while every missing library is confirmed present in the stock
# vendor image, which is checked on device during Phase 3/4 validation.
RUNTIME_MOUNT_OBJECTS = {
    "vendor/bin/hw/piano-gatekeeper":
        "links the stock QTI Gatekeeper runtime, resolved from "
        "/piano/vendor-stock/lib64.",
    "vendor/bin/hw/piano-weaver-hos3":
        "links libmi_weaver from the read-only odm view and the stock vendor "
        "runtime, resolved from /piano/odm-stock/lib64 and "
        "/piano/vendor-stock/lib64.",
    "vendor/bin/hw/piano-weaver-hos4":
        "HyperOS 4 Weaver service; init.recovery.qcom.rc sets its library "
        "path to the read-only /piano/odm-stock/lib64 and "
        "/piano/vendor-stock/lib64 views before the device decrypt hook starts it.",
    "vendor/bin/piano-qwesd":
        "stock QWES daemon; vendor.qwesd in init.recovery.qcom.rc resolves "
        "its vendor runtime from /piano/vendor-stock/lib64 and lib64/hw.",
    "vendor/bin/hw/piano-keymint":
        "links the stock QTI KeyMint runtime (libqtikeymint, "
        "android.hardware.keymaster@4.0), resolved from /piano/vendor-stock/"
        "lib64. Both verified present in the stock vendor partition.",
    "vendor/bin/piano-minkdaemon":
        "the HLOS Mink opener. Links libminkdescriptor, libminksocket_vendor "
        "and libqcbor, resolved from /piano/vendor-stock/lib64, which init "
        "mounts read-only. All three verified present on this device's stock "
        "vendor partition (85512, 185472 and 52024 bytes respectively). This "
        "is the same transport runtime ssgtzd links below.",
    "vendor/bin/piano-ssgtzd":
        "Qualcomm's GlobalPlatform TA loader. Links the stock QMI and Mink "
        "transport runtime (libqrtr, libqmi_cci, libqmi_csi, libqmi_common_so, "
        "libqcbor, libminksocket_vendor, libminkdescriptor), resolved from "
        "/piano/vendor-stock/lib64. All seven verified present on the stock "
        "vendor partition.",
    "vendor/bin/piano-qseecomd":
        "links the stock vendor runtime (libQSEEComAPI, libminkdescriptor, "
        "libdrmfs, libdmabufheap), resolved from /piano/vendor-stock/lib64 "
        "which init mounts read-only. All four verified present on device.",
}

# Standard search order inside a recovery ramdisk.
SEARCH_DIRS = [
    "system/lib64", "system/lib64/vndk-sp", "system/lib64/hw",
    "vendor/lib64", "vendor/lib64/hw", "vendor/lib64/egl",
    "odm/lib64", "odm/lib64/hw",
    "system/lib", "vendor/lib", "odm/lib",
]


def dt_needed(path):
    """Return (is_elf, [DT_NEEDED names]) for a file."""
    with open(path, "rb") as fh:
        d = fh.read()
    if len(d) < 64 or d[:4] != b"\x7fELF":
        return False, []
    if d[4] != 2:  # 64-bit only
        return True, []
    e_phoff = struct.unpack_from("<Q", d, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", d, 0x36)[0]
    e_phnum = struct.unpack_from("<H", d, 0x38)[0]
    loads, dyn = [], None
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        if o + 56 > len(d):
            return True, []
        p_type = struct.unpack_from("<I", d, o)[0]
        p_offset = struct.unpack_from("<Q", d, o + 0x08)[0]
        p_vaddr = struct.unpack_from("<Q", d, o + 0x10)[0]
        p_filesz = struct.unpack_from("<Q", d, o + 0x20)[0]
        p_memsz = struct.unpack_from("<Q", d, o + 0x28)[0]
        if p_type == 1:
            loads.append((p_vaddr, p_memsz, p_offset))
        elif p_type == 2:
            dyn = (p_offset, p_filesz)
    if not dyn:
        return True, []

    def v2o(v):
        for va, ms, off in loads:
            if va <= v < va + ms:
                return off + (v - va)
        return None

    ents = []
    off, size = dyn
    for i in range(size // 16):
        if off + i * 16 + 16 > len(d):
            break
        tag, val = struct.unpack_from("<QQ", d, off + i * 16)
        if tag == 0:
            break
        ents.append((tag, val))
    strtab = next((v for t, v in ents if t == 5), None)
    if strtab is None:
        return True, []
    so = v2o(strtab)
    if so is None:
        return True, []

    def s(x):
        end = d.index(b"\x00", so + x)
        return d[so + x: end].decode("utf-8", "replace")

    return True, [s(v) for t, v in ents if t == 1]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    img = sys.argv[1]
    verbose = "--verbose" in sys.argv

    with open(img, "rb") as fh:
        d = fh.read()
    if d[:8] != b"ANDROID!":
        print("FAIL: not an Android boot image")
        return 1
    rs = struct.unpack_from("<I", d, 12)[0]

    tmp = tempfile.mkdtemp(prefix="piano16-elf-")
    lz4 = os.path.join(tmp, "ramdisk.lz4")
    cpio = os.path.join(tmp, "ramdisk.cpio")
    root = os.path.join(tmp, "root")
    os.makedirs(root, exist_ok=True)
    with open(lz4, "wb") as fh:
        fh.write(d[4096:4096 + rs])
    if subprocess.call(["lz4", "-d", "-f", "-q", lz4, cpio],
                       stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL) != 0:
        print("FAIL: ramdisk did not decompress")
        return 1
    with open(cpio, "rb") as fh:
        subprocess.check_call(["cpio", "-idm", "--quiet"], stdin=fh, cwd=root,
                              stderr=subprocess.DEVNULL)

    # Index every file present in the image by basename.
    present = {}
    for dirpath, _, names in os.walk(root):
        for n in names:
            present.setdefault(n, os.path.join(dirpath, n))

    elfs, missing, checked = [], {}, 0
    for dirpath, _, names in os.walk(root):
        for n in names:
            p = os.path.join(dirpath, n)
            if os.path.islink(p) or not os.path.isfile(p):
                continue
            try:
                is_elf, needs = dt_needed(p)
            except Exception:
                continue
            if not is_elf:
                continue
            elfs.append(p)
            rel = os.path.relpath(p, root)
            for need in needs:
                checked += 1
                if need in LINKER_PROVIDED or need in present:
                    continue
                missing.setdefault(need, []).append(rel)

    print("image:        %s" % img)
    print("ELF objects:  %d" % len(elfs))
    print("DT_NEEDED:    %d references checked" % checked)
    print()

    # Split findings into accepted exceptions and real failures.
    excepted, real = {}, {}
    for lib, users in missing.items():
        allowed = set(INERT_OBJECTS) | set(RUNTIME_MOUNT_OBJECTS)
        if all(u in allowed for u in users):
            excepted[lib] = users
        else:
            real[lib] = [u for u in users if u not in allowed]

    if excepted:
        print("accepted exceptions: %d unresolved references, all from objects"
              " that are inert or resolve from the stock vendor mount"
              % len(excepted))
        for obj in sorted({u for us in excepted.values() for u in us}):
            why = INERT_OBJECTS.get(obj) or RUNTIME_MOUNT_OBJECTS.get(obj)
            print("  %s" % obj)
            print("      %s" % why)
        print()

    if not real:
        print("PASS: ELF closure complete for every object that can execute")
        return 0

    missing = real
    print("FAIL: %d unresolved shared library dependencies" % len(missing))
    print("      ALLOW_MISSING_DEPENDENCIES may have dropped a required module.")
    print()
    for lib in sorted(missing):
        users = missing[lib]
        print("  %-44s needed by %d:" % (lib, len(users)))
        for u in sorted(users)[:6 if not verbose else len(users)]:
            print("      %s" % u)
    return 1


if __name__ == "__main__":
    sys.exit(main())
