#!/usr/bin/env python3
"""Stage the matching stock camera build declarations without changing blobs."""
import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent


def generated(android):
    variant = ROOT / "versions" / f"android{android}"
    if not variant.is_dir():
        raise ValueError("missing camera variant: " + str(variant))
    text = (ROOT / "Android.bp.in").read_text().replace("@CAMERA_VARIANT@", f"versions/android{android}")
    if "@CAMERA_VARIANT@" in text:
        raise ValueError("unexpanded camera template")
    for name in ("MiuiCamera.apk.part0", "MiuiCamera.apk.part1"):
        if not (variant / "proprietary/product/priv-app/MiuiCamera" / name).is_file():
            raise ValueError("missing camera APK segment: " + name)
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("android", choices=("16", "17"))
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = generated(args.android)
    target = ROOT / "Android.bp"
    if args.check:
        if not target.is_file() or target.read_text() != expected:
            raise SystemExit(f"camera Soong config not staged for Android {args.android}; run select-camera.py")
    else:
        target.write_text(expected)
    print(f"MiuiCamera Android {args.android} source selection OK")


if __name__ == "__main__":
    main()
