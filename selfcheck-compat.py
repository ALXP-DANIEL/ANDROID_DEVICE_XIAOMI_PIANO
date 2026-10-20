#!/usr/bin/env python3
"""Validate the compatibility packager against real staged APK and compiled DEX."""
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import zipfile

root = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("compat_packager", root / "append-compat.py")
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)
stock, dex = map(Path, sys.argv[1:3])
metadata = root / "compat/device_features/piano.xml"
with tempfile.TemporaryDirectory(prefix="notes-compat-check-") as directory:
    first, second = (Path(directory) / name for name in ("first.apk", "second.apk"))
    packager.append(stock, dex, first, metadata)
    packager.append(stock, dex, second, metadata)
    assert hashlib.sha256(first.read_bytes()).digest() == hashlib.sha256(second.read_bytes()).digest()
    with zipfile.ZipFile(stock) as before, zipfile.ZipFile(first) as after:
        assert after.testzip() is None
        assert len(after.namelist()) == len(set(after.namelist()))
        for name in before.namelist():
            upper = name.upper()
            if upper.startswith("META-INF/") and (upper.endswith((".SF", ".RSA", ".DSA", ".EC"))
                    or upper == "META-INF/MANIFEST.MF"):
                continue
            assert before.read(name) == after.read(name), name
        assert after.read("assets/piano-device-features.xml") == metadata.read_bytes()
    try:
        packager.append(first, dex, second, metadata)
    except ValueError:
        pass
    else:
        raise AssertionError("duplicate compatibility payload accepted")
    corrupt = Path(directory) / "corrupt.xml"
    corrupt.write_bytes(metadata.read_bytes() + b"\n")
    try:
        packager.append(stock, dex, second, corrupt)
    except ValueError:
        pass
    else:
        raise AssertionError("unverified feature metadata accepted")
print("PASS: original entries preserved, deterministic APK, duplicate and corrupt metadata rejected")
