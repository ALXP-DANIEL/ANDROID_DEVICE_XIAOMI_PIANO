#!/usr/bin/env python3
"""Materialize one of two pinned Android variants from a shared piano tree.

The Git-tracked variants live under versions/android16 and versions/android17.
Only generated (ignored) files appear at their original root locations. We
copy build scripts and hard-link immutable large blobs where possible to avoid
duplicating gigabytes on the build host. Never overwrite pre-existing files.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile

ROOT = Path(__file__).resolve().parent
MARKER = ROOT / ".piano-selected-android.json"
RESERVED = {".git", ".gitignore", "README.md", "versions", "select-version.py"}


def staged_name(name):
    return name.removesuffix(".in") if name in ("Android.bp.in", "Android.mk.in") else name


def sources(version):
    variant = ROOT / "versions" / f"android{version}"
    if not variant.is_dir():
        raise RuntimeError(f"missing variant for Android {version}: {variant}")
    entries = {}
    for entry in sorted(variant.iterdir()):
        name = staged_name(entry.name)
        if name in RESERVED or name.startswith(".piano-"):
            continue
        entries[name] = entry
    return entries


def load_marker():
    if not MARKER.exists():
        return None
    info = json.loads(MARKER.read_text())
    if not isinstance(info, dict) or info.get("kind") != "piano-generated-android-variant":
        raise RuntimeError("unknown variant marker: refusing to change the tree")
    if not isinstance(info.get("files"), list) or any("/" in s or s in RESERVED for s in info["files"]):
        raise RuntimeError("malformed variant marker: refusing to change the tree")
    return info


def copy_regular(source, destination):
    if source.is_symlink():
        destination.symlink_to(os.readlink(source))
    elif source.is_dir():
        destination.mkdir()
        for child in source.iterdir():
            copy_regular(child, destination / staged_name(child.name))
    elif source.is_file():
        destination.parent.mkdir(parents=True, exist_ok=True)
        # Build inputs are immutable, but scripts may be edited by developers:
        # copy text normally, and use hardlinks only for large binary payloads.
        if source.stat().st_size >= 1024 * 1024 and source.suffix in (
            ".so", ".apk", ".ko", ".mbn", ".img", ".bin", ".pmd", ".dat", ".apex"
        ):
            try:
                os.link(source, destination)
                return
            except OSError:
                pass
        shutil.copy2(source, destination)
    else:
        raise RuntimeError(f"unsupported source entry: {source}")


def remove_generated(destination):
    if destination.is_symlink() or destination.is_file():
        destination.unlink()
    elif destination.is_dir():
        shutil.rmtree(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("android", choices=("16", "17"))
    parser.add_argument("--check", action="store_true", help="verify selected generated tree without modifying it")
    args = parser.parse_args()
    wanted = sources(args.android)
    prior = load_marker()
    if args.check:
        if prior is None or prior.get("android") != args.android or set(prior["files"]) != set(wanted):
            parser.error("selected Android variant is not staged")
        for name, origin in wanted.items():
            target = ROOT / name
            if not target.exists() or (origin.is_dir() != target.is_dir()):
                parser.error("missing staged file: " + name)
        print("Verified staged Android", args.android, "tree")
        return

    old_names = set(prior["files"]) if prior else set()
    collisions = [name for name in wanted if (ROOT / name).exists() and name not in old_names]
    if collisions:
        parser.error("refusing to overwrite untracked local files: " + ", ".join(collisions))
    for name in old_names:
        remove_generated(ROOT / name)
    created = []
    try:
        for name, source in wanted.items():
            created.append(name)
            copy_regular(source, ROOT / name)
    except Exception:
        for name in created:
            remove_generated(ROOT / name)
        MARKER.unlink(missing_ok=True)
        raise

    marker = {"kind": "piano-generated-android-variant", "android": args.android,
              "files": sorted(created)}
    with tempfile.NamedTemporaryFile(mode="w", dir=ROOT, delete=False) as tmp:
        json.dump(marker, tmp, indent=2)
        tmp.write("\n")
    Path(tmp.name).replace(MARKER)
    print(f"Staged Android {args.android}: {len(created)} top-level entries")


if __name__ == "__main__":
    main()
