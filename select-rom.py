#!/usr/bin/env python3
"""Generate one PianoParts Soong app for the selected ROM's Settings category."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MANIFESTS = {"lineage": "AndroidManifest.xml", "evox": "AndroidManifestEvox.xml"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("rom", choices=tuple(MANIFESTS))
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    manifest = MANIFESTS[args.rom]
    assert (ROOT / manifest).is_file()
    expected = (ROOT / "Android.bp.in").read_text().replace("@PIANO_PARTS_MANIFEST@", manifest)
    if "@PIANO_PARTS_MANIFEST@" in expected:
        raise ValueError("unexpanded Parts template")
    generated = ROOT / "Android.bp"
    if args.check:
        if not generated.is_file() or generated.read_text() != expected:
            raise SystemExit("PianoParts Soong config does not match selected ROM")
    else:
        generated.write_text(expected)
    print("PianoParts manifest:", manifest)


if __name__ == "__main__":
    main()
