#!/usr/bin/env python3
"""Verify that every file the prune list names is actually unreferenced.

Called by piano16-ramdisk-prune.sh before anything is deleted. Exits non-zero,
failing the build, if a listed file is still needed.

This check exists because of a real failure. The prune list was validated by
inspecting the non-crypto build, where system/bin/recovery does not link ICU.
Enabling TW_INCLUDE_CRYPTO changes recovery's link set: it then names
libandroidicu.so in DT_NEEDED directly. The stale list removed it anyway, and
the image booted as far as init before

  F linker : CANNOT LINK EXECUTABLE "/system/bin/recovery":
             library "libandroidicu.so" not found: needed by main executable

left the device in fastboot. A conclusion drawn under one build configuration
was carried across a change that invalidated it, which a static list cannot
notice. So the list is now a proposal and this is the authority: the evidence
is recomputed from the tree being packed, on every build.

Two kinds of reference are checked:

  DT_NEEDED  - a link edge from any ELF in the tree.
  by name    - the file's basename appearing as a string in any other file,
               which is how dlopen and fork/exec reach a target. The ramdisk
               manifests are excluded because they list every path by
               construction, as is the file itself.
"""
import os, re, subprocess, sys

ROOT = sys.argv[1]
TARGETS = sys.argv[2:]

MANIFESTS = {"ramdisk-files.txt", "ramdisk-files.sha256sum"}

def is_elf(path):
    try:
        with open(path, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False

def needed(path):
    for tool in ("llvm-readelf", "readelf"):
        try:
            out = subprocess.run([tool, "-d", path], capture_output=True, text=True).stdout
            return re.findall(r"Shared library: \[([^\]]+)\]", out)
        except FileNotFoundError:
            continue
    print("ERROR: no readelf available; cannot verify the prune list", file=sys.stderr)
    sys.exit(1)

target_abs = {os.path.join(ROOT, t) for t in TARGETS}
target_base = {os.path.basename(t) for t in TARGETS}

link_refs = {}   # basename -> [referrers]
name_refs = {}

for dirpath, _, names in os.walk(ROOT):
    for n in names:
        p = os.path.join(dirpath, n)
        if os.path.islink(p) or not os.path.isfile(p):
            continue
        if p in target_abs or n in MANIFESTS:
            continue
        rel = os.path.relpath(p, ROOT)
        if is_elf(p):
            for dep in needed(p):
                if dep in target_base:
                    link_refs.setdefault(dep, []).append(rel)
        try:
            with open(p, "rb") as f:
                blob = f.read()
        except OSError:
            continue
        for b in target_base:
            if b.encode() in blob:
                name_refs.setdefault(b, []).append(rel)

problems = []
for t in TARGETS:
    b = os.path.basename(t)
    if b in link_refs:
        problems.append((t, "DT_NEEDED", link_refs[b]))
    elif b in name_refs:
        problems.append((t, "named by", name_refs[b]))

if problems:
    print("\nPRUNE VERIFICATION FAILED — refusing to remove files that are still referenced.\n")
    for t, kind, refs in problems:
        print(f"  {t}")
        print(f"      {kind}: {', '.join(sorted(set(refs))[:6])}")
    print("\nThe prune list in piano16-ramdisk-prune.sh no longer matches this build")
    print("configuration. Re-run the reachability analysis before building again.")
    sys.exit(1)

print(f"  verified {len(TARGETS)} files unreferenced (no DT_NEEDED edge, no name reference)")
